# Design notes

Rationale, subsystem detail, and rejected alternatives for the ComputerCraft
Turtle Swarm Manager. Status, constraints, layout, and roadmap are in
[README.md](README.md).

Most of this is ahead of the implementation. It exists so decisions don't get
re-litigated from scratch, not because any of it is built.

---

## Contents

1. [Turtle registry](#1-turtle-registry)
2. [Command protocol](#2-command-protocol)
3. [World storage](#3-world-storage)
4. [Pathfinding](#4-pathfinding)
5. [Exploration and scheduling](#5-exploration-and-scheduling)
6. [Task system](#6-task-system)
7. [Heatmap](#7-heatmap)
8. [Networking](#8-networking)
9. [AE2 storage subsystem](#9-ae2-storage-subsystem)
10. [Viewer](#10-viewer)
11. [Open questions](#11-open-questions)
12. [Rejected / deferred](#12-rejected--deferred)
13. [References](#13-references)

---

## 1. Turtle registry

**Store:** unexported `map[uint16]*Turtle` at package level in `turtles`,
created with `make` at startup. Exported functions are the only doors in.

**Why a map, not a slice indexed by id:**
- A slice must be grown to length ≥ 47 before you can write index 46. Every
  registration would need a length check and a fill loop.
- Map keys have no ordering requirement and no gaps to manage.
- CC computer ids climb globally — turtle 4700 on day one is plausible.

**Why pointers, not values:**
- Assignment in Go always copies. `for _, t := range m` gives you a *copy*;
  writing `t.fuel = 500` modifies the copy silently.
- **Map values are not addressable** — `m[7].fuel = 500` doesn't compile. This
  falls out of the growth strategy: when a map exceeds its load factor it
  reallocates buckets and rehashes, physically moving every value. Allowing
  pointers into map values would create dangling pointers.
- A `Turtle` is ~700 bytes (the `[16]Item` array is stored *inline* — arrays
  are values in Go). Copying 8 bytes beats copying 700.
- Same rule as value vs pointer receivers, in a container instead of a method.

**Keep `ID` in the struct even though it's the map key.** A `*Turtle` passed to
a function has no idea what key it was stored under; logging, error messages,
and wire responses all need it. Cost: 2 bytes. Invariant: `m[46]` holds a
turtle whose ID is 46 — set once at creation, never elsewhere.

**Lookup by anything other than id:** scan with a predicate
(`func(*Turtle) bool`). At fleet scale a linear scan is ~1µs, three orders of
magnitude below the network round-trip. Secondary indexes are caches with a
consistency problem and multiply write cost; don't build them until a
measurement demands it.

**Parent/children:** store the parent id only (adjacency list). Derive children
by scan. Storing a `Children []uint16` writes the same fact in two places and
lets them disagree.

**Concurrency, deferred but flagged:** every `net/http` handler runs in its own
goroutine. Two turtles registering simultaneously = concurrent map write = hard
runtime crash ("concurrent map writes"). Concurrent *struct field* writes
corrupt silently. The fix is a `sync.RWMutex` inside the exported functions.

⚠️ **Never hold the lock across a network write.** This is the exact bug
already found in MistAPI's `broadcast`. Copy what's needed under the lock,
release, then do I/O.

Trigger to come back to this: the first "concurrent map" crash. Tooling:
`go test -race`, `go build -race`. GoLand: run configuration → Go tool
arguments → `-race`.

---

## 2. Command protocol

### 2.1 It is a bytecode VM

The turtle is a dumb interpreter running a fetch-decode-execute loop. Go is the
compiler; `A*` is the code generator; the Lua agent is the CPU. The entire body
of literature on bytecode design applies.

Terms: **opcode**, **operand**, **instruction format**, **dispatch table**,
**fetch-decode-execute**.

### 2.2 Encoding

**v1: fixed-width `uint8` opcodes.** One byte per instruction, decode is a
single array index, a 50-step path is 50 bytes.

**Self-delimiting — no terminator byte needed.** The opcode determines how many
operand bytes follow. Read opcode → look up operand count → read that many →
execute → next byte is the next opcode. x86 works this way.

Cost of that property: you cannot decode from the middle, and one corrupt byte
desynchronises everything after it. Acceptable over a reliable connection.

**Bit packing:** `b = (op << 4) | count` to encode, `op = b >> 4` and
`count = b & 0x0F` to decode. Go: `<<`, `>>`, `&`, `|`, `^`. Lua: `bit32` in
CC:Tweaked, possibly native operators depending on version — verify.

**4 bits of opcode is too small.** The turtle API alone (forward, back, up,
down, turnLeft, turnRight, dig×3, place×3, drop×3, suck×3, select, refuel,
attack×3, detect×3, inspect×3, compare, transferTo) exceeds 30 before adding
anything custom.

**Resolution — partition the opcode space by range** rather than using one
fixed bit layout:

```
b < 192   → plain single-byte command (192 available)
b >= 192  → repeat form:
              n   = b - 192
              dir = n >> 4          (4 directions)
              cnt = (n & 0x0F) + 1  (1-16)
```

Only commands that benefit from repeat counts pay for them. Nothing is wasted
on `goLeft 7`. This is what real instruction sets do — the opcode field selects
which *format* the rest follows. Escape hatch: anything that doesn't fit gets a
plain opcode with operand bytes following (`select` = opcode + 1 operand byte).

**Deferred:** run-length encoding of paths beyond the built-in repeat forms.
Real win on straight lines, but complicates mid-sequence position tracking and
interrupt recovery.

**Debugging:** implement `String()` on the opcode type. `fmt` calls it
automatically anywhere `%v`/`%s` appears, so `Opcode(3)` prints as `turnLeft`.
The `stringer` generator writes these from a const block; wire it with a
`//go:generate` directive.

### 2.3 Command granularity

**Rule: the decision goes where the fresh data is.**

| Decision                 | Where  | Why                                               |
|--------------------------|--------|---------------------------------------------------|
| Which slot to place from | turtle | knows its inventory *now*; server's copy is stale |
| Pathfinding              | server | has the world cache; turtle sees only local       |
| Fuel checks              | turtle | ground truth                                      |
| Task assignment          | server | global view                                       |

So send `place <item id>`, not `selectSlot 4; place`. Fewer bytes *and* correct
— not a trade-off.

### 2.4 Lifecycle and reporting

A command is not a function call with a return value. It's a message with a
lifecycle: **queued → sent → acknowledged → completed/failed.**

- Every sequence carries a **correlation id** so a late report from an
  abandoned job isn't applied to the current one. Cannot be a counter you
  reset.
- Failure reports carry an **index into the sequence** plus a reason.

**Reporting policy:** silence on success. Report on error, drift, or bulk data
(a completed scan). Success is signalled by requesting more work.

⚠️ **Hole in that policy:** a dead turtle and a working turtle both produce
silence. Out of fuel, chunk unloaded, creeper, Lua crash. Fix:
- **Heartbeat** — tiny periodic "still here, on step 14"
- **Watchdog** — server presumes death after N seconds and requeues the task
- The server knows how long a sequence should take, so the timeout is
  computable
- Recovery: dispatch another turtle

### 2.5 Positioning

**Dead reckoning, not per-step GPS.**

`gps.locate()` broadcasts on the modem and waits for replies from ≥4 hosts with
a timeout. Real tick cost, can fail. Doing it per block turns a fast movement
loop into a slow one.

Not needed, because `turtle.forward()` **returns a boolean** — movement is
transactional, it either happened or it didn't, and you're told which. Track
position by applying every successful move from a known origin.

GPS becomes **drift correction at boundaries**: on boot, after an anomaly,
after chunk reload, when the server distrusts the position.

Per-step, use `turtle.inspect()` — local, no network round-trip, and it feeds
the world cache.

**Later — scan matching.** Once the world is scanned, overlapping scans are a
positional fingerprint; match against the cache and GPS becomes unnecessary.
This is the SLAM family. **Fails on uniform terrain** — a turtle in the middle
of 200 blocks of stone sees an identical scan from a hundred positions. Keep
GPS as the recovery path.

---

## 3. World storage

### 3.1 Three states, not two

**known-solid** (with type), **known-air**, **unknown**.

If absence means air, the pathfinder routes through unscanned rock and the
X-ray confidently shows no diamonds in regions it has never looked at. Both
failures are silent and look like correct answers.

Implementation: absence of a chunk = unknown. Scanned-and-empty = an explicit
air entry. Scan bounds recorded separately, so "inside a scan boundary, null =
air; outside, null = unknown."

**Unknown ≠ stale.** Previously-known air is very probably still air. Never
collapse the two — a stale base chunk and a never-visited chunk want different
pathfinding costs.

### 3.2 Structure: fixed sparse chunk map

**`map[ChunkCoord]*Chunk`, 16³ chunks.**

**Chunk size is settled at 16³.** Standard, matches Minecraft and most voxel
engines, and lands well on both the sparse-waste curve (smaller chunks waste
less on partial fills) and the per-chunk-overhead curve (bigger chunks amortise
headers better). Do not tie chunk size to scan radius — they are independent,
and a scan simply writes into however many chunks it overlaps.

**Why not an octree:** deep, narrow (2×2×2 per level). On thin irregular
tunnels through mostly-unscanned world, most memory goes to intermediate nodes
that exist only to reach one small populated region — 8 pointers deep, most
children empty.

**Why not dynamic bounded regions** (the intuition that a 1000-block tunnel is
"one region, 2 coordinate bounds"):

- **Lookup becomes search, not arithmetic.** Fixed chunks: `(x>>4, y>>4, z>>4)`
  plus a mask — two shifts. Regions: "which region contains this point" has no
  arithmetic answer. Needs a spatial index (R-tree / BVH), which is a tree you
  write and rebalance. `A*` asks this question millions of times per run.
- **Overlap.** Two scans covering the same blocks at different times need merge
  and split logic. A partially-overlapping scan can shatter an existing region
  into up to 26 pieces.
- **No deduplication.** Rescanning the same tunnel ten times stores it ten
  times unless merged. A chunk map overwrites — it deduplicates for free.
- **Non-convex shapes.** A spiral's AABB is a huge mostly-unscanned cube.
  Either store the waste or decompose into many boxes, which is chunks with
  worse lookup.
- **Header overhead.** 6× int32 bounds + type + timestamp ≈ 40 bytes. A region
  describing a 3-block pocket costs more in header than data.

*(The region model was argued seriously and is not stupid — the per-region
bounds test is genuinely cheap, six branch-predictable integer comparisons. It
fails on the hot path and on bookkeeping, not on the arithmetic.)*

### 3.3 Per-chunk representation

In order of value:

1. **Uniform flag** — whole chunk is one type. 2 bytes for 4,096 blocks.
2. **Palette** — `[]uint16` of the distinct types present in *this* chunk. The
   **array index is the local id**; no separate 1→2 byte mapping table needed.
3. **Bit packing** — `ceil(log2(len(palette)))` bits per block. 5 types = 3
   bits, not 8.
4. **RLE** over the packed indices.
5. **zstd** on cold chunks.

Order matters — palette → bit-pack → RLE beats RLE on raw uint16s, because
you're compressing 3-bit symbols instead of 16-bit ones.

**RLE run length:** 1 byte (max 255), emit a second entry if a run exceeds it.
Keeps the format fixed-width and indexable. Varint compresses better; deferred.

**RLE underperforms on Minecraft geometry** and this is a real limitation.
Terrain is 3D *blobs* (granite/diorite/andesite through stone, air pockets, ore
veins), not 1D stretches. A 5-block granite blob intersects scanlines as ~25
separate runs, each paying a header. Two mitigations:

- **Axis choice** — Y-major runs are often longer than X-major, because terrain
  is layered. One-line change; test both.
- **Morton order** (below) — makes 3D-local blobs linearly contiguous.

**Adaptive strategy switching** (copy from modern Minecraft): pick the
representation per chunk based on distinct type count — single value, linear
array, hash map, or global ids.

### 3.4 Morton / Z-order

Interleave the bits of x, y, z into a single index. Points near each other in
3D end up near each other linearly, so 1D compression works on 3D data.

**For a 16³ chunk you need 12-bit codes** (4 bits per axis, 4,096 values). A
single small lookup table, or a direct 4,096-entry array. The 21-bit / 64-bit
machinery in the literature is for global coordinates and is unnecessary if
chunks are the addressing unit.

**Encoding methods**, from Baert's benchmark (MAX=256 → 16.7M encodes):

| Method                    | 64     | 128    | 256    | per-op @256 |
|---------------------------|--------|--------|--------|-------------|
| For-loop                  | 0.2s   | 1.6s   | 13.13s | ~780ns      |
| Magic bits                | 0.01s  | 0.13s  | 1.06s  | ~63ns       |
| LUT (3×256 entries, ~3KB) | 0.005s | 0.041s | 0.319s | ~19ns       |

⚠️ **Caveats on those numbers:**
- The benchmark **discards the result**, making it very sensitive to
  optimisation level. Low-opt penalises magic bits (many arithmetic ops) far
  more than LUT (three loads). Treat 3× as an upper bound.
- The microbenchmark keeps the 3KB table permanently hot in L1. In `A*` it
  competes with chunk data for cache. Magic bits touch no memory and don't
  degrade under pressure. Classic LUT-vs-compute trap.
- **2013 hardware.** x86 has since gained **BMI2 `PDEP`** — Morton encoding in
  ~3 instructions, no table. Fastest method now. (AMD Zen 1/2 implemented it in
  microcode and it was very slow; Zen 3+ is fast.) **Not available in Go
  without assembly**, so the practical choice is LUT vs magic bits.

**Resolution:** write both, benchmark inside the actual pathfinder with
`go test -bench`, pick the winner.

**Morton's cost for `A*`:** in row-major, x+1 is index+1 — adjacent, same cache
line, ~64 blocks per fetch. In Morton order x+1 jumps by 1, 8, or 512 depending
on curve position, so 6 neighbours can land in 6 cache lines. Neighbour
*computation* is cheap (masked per-axis increment); the *fetches* are the cost.

**But:** a 16³ chunk at 1 byte/block is 4KB, well inside L1 (32–48KB). Once
resident, every access hits regardless of ordering. So the penalty largely
evaporates at this chunk size. Build it Morton-native and switch to row-major
only if profiling says so.

### 3.5 Access tiers

**The load-bearing realisation: `A*` needs random access; RLE only gives
sequential access.** You cannot compute the cost of a coordinate without
knowing what block is there, so a compressed chunk must be decoded before
pathfinding through it.

| Tier          | Form                                | Purpose                |
|---------------|-------------------------------------|------------------------|
| Disk          | zstd over raw palette-indexed bytes | storage                |
| Resident cold | RLE / bit-packed                    | compact, not queryable |
| Hot           | decoded dense array                 | `A*` random access     |

Requires an **LRU cache with eviction** on the hot tier — decode on demand,
drop least-recently-used when over budget. ~50 lines with a map and a linked
list. This is what makes "world larger than RAM" stop being a problem.

Which tier a chunk sits in is driven by the combined player + turtle heatmap.

**zstd:** lossless, byte-identical. ~200–500 MB/s compress, **1–2 GB/s
decompress regardless of level**. Levels 1–22. The asymmetry works in your
favour: compress cold chunks slowly in background, decompress instantly on
access. Decompressing a 4KB chunk is microseconds. Don't compress hot chunks —
microseconds × millions of lookups adds up.

Later: **zstd dictionary compression** — train a dictionary on sample chunks,
large extra win on small similar inputs.

### 3.6 Scan records — separate structure

TTL is a property of the **scan event**, not the block. One scan = one volume
at one timestamp. Per-block timestamps duplicate massively; per-chunk
timestamps are wrong, because a chunk touched by three scans has no single
correct value.

**Two structures, different jobs:**

- **Block storage** — fixed chunks, answers *what is at this coordinate*
- **Scan records** — `{bounds, timestamp, turtle_id}` list, answers *when* and
  *whether*

Scan records don't need to align with chunks because they aren't indexed by
chunk. Staleness queries, rescan priority, "have I ever scanned here" all live
here and are all naturally region-shaped — so the region model *is* right, just
for provenance rather than voxels.

**Compute staleness at read time from a stored timestamp.** No expiry sweep, no
background job. Queries pick their own threshold ("diamonds seen in the last 10
minutes"), tunable live. A read hitting data older than threshold queues a
rescan.

### 3.7 Coordinates and types

- **`int32` for x/y/z.** Legal range is ±30M on X/Z; Y is **-64 to 320** so it
  must be signed. Never go int16 to save 4 bytes.
- **`Rot` should be `uint8`, not `int`** — currently 8 bytes for four states.
- **No quaternions, no complex numbers.** Four axis-aligned orientations,
  changed in 90° steps. `(r+1)%4` is the whole implementation. Quaternions
  solve arbitrary-axis rotation, gimbal lock, and smooth interpolation — none
  of which exist here. Use integers where the domain is discrete; floats would
  introduce rounding error into a lattice that has none.
- **Struct field ordering:** declare largest → smallest. Go lays fields out in
  declaration order and pads for alignment; it does not reorder for you.
  `{uint8, uint64, uint8}` = 24 bytes, `{uint64, uint8, uint8}` = 16. Saves
  bytes *per instance* — irrelevant for 100 turtles, real for millions of chunk
  entries. `unsafe.Sizeof` prints the actual number.

### 3.8 Serialisation

**Chunk payloads: write raw bytes.** The chunk *is* a byte array; there's no
serialisation layer needed. `f.Write(data)` plus a small header.

**Metadata (scan records, turtle state, block registry): `encoding/gob`.**
Native Go binary format, handles structs/maps/slices automatically, far smaller
and less code than JSON. Caveat: **Go-only** (the Rust viewer can't read it —
fine, since it talks to Go over websocket and Go transcodes), and it embeds
type info, so renames can break old files.

**Never `unsafe`-cast a struct to bytes.** That bakes your machine's padding,
alignment, and endianness into the file, and pointers become meaningless
addresses. Works until it doesn't, silently.

**Add a version byte to every chunk header.** The format will change.

**Container:** a single file is fine initially. What forces regions is
fragmentation — a chunk that grows past its slot must move, requiring
free-space management. Copy **Anvil**: group chunks into region files
(Minecraft uses 32×32; 256×256 planned here) with a header table of offsets so
you can seek directly to a chunk.

**Endianness:** pick **big-endian** (network byte order, readable hex dumps)
and write it down. Go: `binary.BigEndian.PutUint16`. Lua: `string.pack` with
`>` (5.3 feature; CC's Cobalt runtime is 5.2-based with some 5.3 backported —
verify). A mismatch produces wrong numbers that look like corruption, not like
a bug.

---

## 4. Pathfinding

### 4.1 Cost model

Nearly all blocks are breakable, so this is not a maze — it's a weighted
traversal where solid blocks cost more (fuel + time), and expensive/rare blocks
cost much more to discourage breaking them.

**Unknown space:** charge the expected cost of a generic solid block, plus a
**small uncertainty penalty** so that given two equal paths the planner prefers
the known one. (The robotics "freespace assumption" doesn't apply — that exists
for robots that can't pass through obstacles at all.)

Later refinement: derive the expected cost from local statistics — average
block cost in nearby scanned chunks, or per-Y-level averages. Same Bayesian
shape as the WoT particle filter.

**Protected zones** (user-defined, e.g. base): non-air blocks are
**impassable**, not infinite-cost. Skip them during neighbour generation.
Infinity in a cost function propagates through addition, behaves oddly in
comparisons, and produces NaN if floats are ever involved. Air in the same zone
routes normally.

⚠️ **Consequence:** a protected zone can make the goal unreachable. `A*` will
exhaust the open set and return nothing. "No path exists" must be a handled
outcome, not a hang.

### 4.2 Performance

**`A*`'s own bookkeeping is usually bigger than the world data.** Open set,
closed set, and came-from map for a long path run to tens of thousands of
nodes. Preallocate, reuse between runs, prefer flat arrays indexed by node over
maps. This matters more than chunk cache behaviour.

Cache sizes for reference (modern desktop): L1d 32–48KB (~4 cycles), L2
512KB–2MB (~14), L3 8–64MB (~40–50), RAM ~200+. A 50-chunk working set at 16³
is ~200KB — comfortably L2, and L2 is not a problem. You cannot pin anything to
L1; the levers are access pattern and working-set size. Go has no prefetch
intrinsics.

### 4.3 Replanning

**Replan on relevance, not on change.** A scan 500 blocks away doesn't affect
the current path. Check whether changed chunks intersect the remaining path or
its neighbourhood; if not, ignore. Kills the vast majority of replans for free.

**When replanning, use an incremental algorithm — `D* Lite` (built on
`LPA*`).** Designed for exactly this: an agent moving through partially-known
terrain, discovering obstacles, reusing the previous search tree and
recomputing only the affected portion.

### 4.4 Long distances — `HPA*`

For a 2,000-block journey, block-level `A*` expands an intractable number of
nodes. Hierarchical Pathfinding `A*`:

1. Precompute per-chunk entry/exit connectivity and costs (cache alongside the
   decoded chunk)
2. Path at the **chunk graph** level — ~100 nodes instead of millions
3. Refine within only the chunks the abstract path uses

Chunk boundaries are already the right abstraction level. This matters far more
than inner-loop cache behaviour.

---

## 5. Exploration and scheduling

### 5.1 One queue, one score

Exploration and rescanning compete for the same turtles, so they must be
comparable. Unify on **expected information gain**:

- Unknown space: high gain (you know nothing)
- Stale known space: gain proportional to likelihood of change (the TTL decay)
- Fresh space: ~zero

Same scale, so they sort together. A very stale base region can legitimately
outrank a distant unexplored one.

### 5.2 Frontier-based exploration

A **frontier** is a known cell adjacent to unknown. That's where information
gain lives — scanning anywhere else reveals nothing. Cluster adjacent frontier
cells into regions; assign turtles by utility:

```
utility = (information gain × priority weight) − travel cost
```

- **Information gain** ≈ frontier size, or unknown volume within scan radius
- **Priority weight** = heatmap (near where you are, near infrastructure)
- **Travel cost** = `A*` distance from the turtle's current position

**Multi-agent:** two turtles must not target the same frontier.
- Cheap: **claim-on-assignment** — reserve the region, others treat as taken
- Better: discount utility by proximity of other turtles already heading there,
  producing natural spreading without hard reservations

**Incremental frontier update** — only recompute in regions that changed. A new
scan creates frontiers at its edges and destroys the ones it consumed.

### 5.3 Passive scanning is the main mechanism

Turtles scan while travelling, so **frequent areas stay fresh for free**. The
heatmap and the traffic distribution are the same distribution, meaning
maintenance is automatically where it's needed. Explicit rescan tasks only fire
where nothing happens to travel.

Passive scans should be **small radius, high frequency** — cheap freshness for
the corridors that matter, not a full scan on every move.

### 5.4 Protected zones need *more* rescanning, not less

Low information gain (nothing to discover) but **high volatility** — the player
changes them constantly without telling the server. The world model of the base
is the thing most likely to be wrong. Volatility should win over information
gain in the priority function.

---

## 6. Task system

**Turtles are workers, not roles.** A turtle isn't "an excavation turtle"; it's
a worker currently assigned an excavation task. One queue holds heterogeneous
work — explore this frontier, rescan this region, excavate this zone — all
scored by the same priority function.

**Capability matching**, forced by the peripheral constraint: a task declares
what it needs (tool, scanner, modem), a turtle declares what it has. Filter
before priority sort.

**Multi-turtle work in one volume** — the hard part isn't dividing the zone,
it's collision and deadlock:
- **Spatial partitioning** — disjoint sub-volumes, one per turtle. Removes most
  conflict.
- **Cell reservation** — claim the target cell before moving into it.
- Remaining deadlocks resolve by timeout and retry.

The general problem is **MAPF** (Multi-Agent Path Finding) and is genuinely
hard in full generality. The above handles the practical case.

**User-defined tasks:** place tool turtles, define a zone from the head
computer, dispatch N turtles. Design is sound; the open question is whether
these tasks are worth doing at all given the status section in the README.

---

## 7. Heatmap

**Two separate numbers, because they answer different questions:**

| Number           | Decay       | Drives                             |
|------------------|-------------|------------------------------------|
| Cumulative total | none        | the visual (2b2t-highway-map look) |
| Decayed recency  | exponential | rescan priority, TTL               |

Decay would erase the highway map, which is the whole point of the visual. But
raw accumulation makes scheduling wrong. So: keep both.

**Exponential decay** for the scheduling number: presence adds, decay subtracts
proportionally to current value, so the score approaches a **ceiling** rather
than growing without bound. Four hours AFK doesn't blow out the map.

**AFK gating:** only add when position/rotation changed since the last sample.
Movement, not presence. Removes the problem at source.

**Log scale on the display colour ramp.** Even with correct data, the base will
be an order of magnitude above everything else; log compresses that so
mid-range activity stays visible instead of crushing to black. Expose the
exponent as a UI slider and tune live — this is a display curve problem, not a
data problem.

---

## 8. Networking

### 8.1 Naming (four things are all called "server")

| Term                 | Meaning                                                   |
|----------------------|-----------------------------------------------------------|
| **host**             | the physical machine                                      |
| **MC server**        | the Minecraft server                                      |
| **backend**          | the Go program                                            |
| **broker** / **hub** | the in-game computer relaying between turtles and backend |

### 8.2 Transport

**v0.1: direct HTTP, one turtle.** No hub. Add the hub when a real limit is
hit, and you'll then know which limit and why.

**v0.2+: gateway pattern** — one turtle holds a single websocket to the backend
and relays to the fleet over rednet. Trade-offs:

- **Rednet is unreliable** — no delivery guarantee, no acknowledgement, dropped
  messages are not retried. Needs an ack-and-retry layer.
- **Single point of failure** — hub dies, fleet goes deaf. Turtles should fall
  back to direct HTTP after N failed contacts. Mitigated by the websocket: the
  backend knows instantly, can freeze or shut down the fleet and alert.
- **Wired modems are physical blocks** connecting a computer to distant
  peripherals — useless for mobile turtles, fine for the AE2 computer.
- Ender modems remove the range concern.
- The chunkloader manager allows remote activation of a turtle.

Routing overhead is 2 bytes (uint16 destination id).

**Websockets structurally:** a websocket starts as a normal HTTP GET with
`Upgrade: websocket`; the server replies `101 Switching Protocols` and the
connection stops being HTTP. HTTP/1.1 is request-response only and the client
always starts — that, not performance, is why polling vs websocket was ever a
question.

### 8.3 HTTP notes

- **401 vs 403:** 401 = "I don't know who you are"; 403 = "I know and you're
  not allowed."
- **Headers must be set before writing any body.** The first byte written sends
  the status line and headers; anything set after is silently ignored.
- `http.HandleFunc` registers on the **package-level global**
  `DefaultServeMux` — any imported package can add routes (this is how
  `net/http/pprof` works). `http.NewServeMux()` gives you your own.
- `http.ListenAndServe` has **no timeouts**. A client that connects and sends
  nothing holds the connection forever. Use `&http.Server{}` with `ReadTimeout`
  / `WriteTimeout`.
- Go 1.22+ mux supports `"POST /register"` patterns and `{id}` path values via
  `r.PathValue("id")` — deletes method checks and simplifies routing.

### 8.4 Middleware

The auth + method block is repeated in every handler. Two options:

- **Simple:** `check(w, r, method) bool`, caller does
  `if !check(...) { return }`. The `return` is still forgettable, and
  forgetting it is silent.
- **Middleware:** a function taking an `http.HandlerFunc` and returning a
  wrapped one. On failure it writes the error and **never calls the inner
  handler**, so unauthenticated execution is structurally impossible.
  `http.HandleFunc("/register", requireAuth(RegisterTurtle))`. Stackable —
  logging wrapping auth wrapping rate-limiting.

Build the simple one first, convert once there are three handlers.

### 8.5 Where request data goes

| Location     | Access                                  | Use for                        |
|--------------|-----------------------------------------|--------------------------------|
| Query string | `r.URL.Query().Get("id")`               | small simple values            |
| Path         | `r.PathValue("id")` (Go 1.22+, own mux) | resource identity              |
| Headers      | `r.Header.Get(...)`                     | credentials, protocol metadata |
| Body         | `json.NewDecoder(r.Body).Decode(&req)`  | anything structured            |

**Never put credentials in a URL** — logged by every proxy and server in the
path, in browser history, in the Referer header. Not a GET-specific rule; a URL
rule. The `Authorization` header is correct.

**JSON decode requires exported struct fields plus tags** — reflection cannot
read unexported fields. Same reason unexported fields marshal to `{}`.

**Bulk turtle data:** `io.ReadAll(r.Body)` → `[]byte` for the binary opcode
format.

---

## 9. AE2 storage subsystem

Separate feature on the same backend: map the ME network, know total storage,
periodically read each disk to find what's where, compute optimal item
placement.

### 9.1 Settled: full snapshot, no diff

The framing that resolves it isn't network-cost vs compute-cost — it's **what
happens when a message is lost or a client restarts.**

- **Snapshots are self-healing / idempotent.** Every message is complete truth.
  Drop one, restart either side, the next snapshot repairs everything.
- **Diffs are fragile.** Each is meaningful only relative to the exact prior
  state. Miss one and you are permanently, silently wrong — a confidently
  reported 4,096 cobblestone that isn't there, with nothing to contradict it.
- Diffs require sequence numbers to detect gaps *and* a resync path — which
  means building the snapshot path anyway.

The general solution where diffs are worth it is **both** — periodic snapshot
plus deltas between (I-frames/P-frames, snapshot interpolation with delta
compression, base backup + WAL). Here, diffs aren't worth it: holding two full
copies in Lua memory to compute a diff on-tick is the expensive path, and
redefining the full storage in Go is cheap.

⚠️ **Correction to the reasoning, though the conclusion holds:** only the
network *transfer* is off-tick. Iterating the ME item list, building the table,
and `textutils.serializeJSON` all run on the Lua thread inside the tick budget.
**Serialisation is the timeout risk, not the send.** That's where `sleep(0)`
yields go, and that's what to measure.

**Scale check:** ~600 unique item types is *small*. A few hundred KB of string,
well under a second. The dreaded scenario was the diff, and it's been dropped.
**Write the naive full-dump version, time it, print the string length** before
designing any byte format.

### 9.2 Item identity

- **Counts need uint32 minimum, realistically uint64.** AE2 stores counts as
  64-bit longs; a single 64k cell of one item blows past uint16 alone.
- **Item stacks ≠ item types.** AE2 stores stacks with components — enchanted
  books, damaged tools, fluids, gas. "Diamond pickaxe with Efficiency V and 12
  damage" is a distinct entry from "diamond pickaxe."
- The raw table includes id, name, count, and **all NBT**, which is where the
  size comes from for non-stackables.

**Enum source:** the mod provides an item registry dump of every item in the
game across all mods. Run it on computer startup (chunkloaded, so a
non-player-triggered restart implies a server reboot) and build the mapping
from that.

**Alternative considered — runtime dictionary / string interning:** the turtle
sends the full string identifier the first time it sees something, the server
assigns a number and returns it, both sides use the number thereafter. This is
what HTTP/2's **HPACK** does with header names. Self-adapts to pack updates
instead of breaking. The registry approach was chosen for less code; interning
noted as the more robust option.

**Lua memory:** `scan = nil`, not `scan = ""`. Setting to an empty string keeps
the variable, just holding nothing useful. `nil` drops the reference; the
collector reclaims on its next pass, or `collectgarbage()` forces one. Watch
for other references keeping it alive — closures, containers, anything you
passed it to.

---

## 10. Viewer

3D inspector for the cached world, a separate client against the backend API.
Local; the backend is in the cloud; talks over websocket (Go transcodes, since
the Rust side can't read gob).

### 10.1 Architecture

**Mesh once, then discard the blocks.** The client only displays the mesh.
Split into mesh regions so a change remeshes one region, not the world — **32³
is the common choice** (64³ = 262,144 blocks per remesh, long enough to want it
off the main thread; 32³ trades more draw calls for cheaper remeshes).

### 10.2 Rendering

- 3D voxel view plus live turtle positions
- **Tunnel visibility:** tunnels are their own block type, rendered as a
  distinct layer. Generic air renders on top with backface culling and flipped
  normals, so tunnel interiors stay visible through terrain. Turtles render
  inside/on top.
- Greedy meshing for chunks, instanced rendering for turtles
- Backface culling, frustum culling, LOD, chunk occlusion culling if needed

**Greedy meshing** — merge coplanar same-type faces into large quads, skip
faces between two solid blocks. Non-negotiable: a naive one-cube-per-block
renderer dies instantly. Adjacent-face culling *is* part of greedy meshing;
frustum culling is built into every engine; backface culling is a rasterizer
setting. Occlusion culling is the only hard one and probably unnecessary at
these sizes.

### 10.3 Inspection

- Filter/colour blocks by type, TTL/staleness, scan age, importance,
  protected/breakable
- Overlay heatmaps: player presence, turtle traffic, rescan priority
- Display planned `A*` paths per turtle
- Highlight cached-vs-last-scanned discrepancies

### 10.4 Turtle management

- Live per-turtle stats: fuel, inventory, task, queue depth
- Parent/child and coalition relationships
- Manual control: reboot, halt, reassign, manual movement
- Task queue inspection and reordering

### 10.5 Stack — decision reopened

Original plan was `wgpu` + `winit` + `egui` + `glam` + `bytemuck`, with Bevy
rejected because ECS is a second architecture to learn.

**That rejection is now doubtful.** The viewer is explicitly a QoL/debugging
tool, not a graphics-learning project, and there isn't time to learn camera
maths and shaders. Bevy gives a render pipeline, camera controls, input, and
asset loading for free, stays code-first (no editor to fight), and runs in
RustRover with normal compile times.

**Rule of thumb:** `wgpu` if learning graphics is the point; **Bevy if the
viewer is the point.** Currently the viewer is the point.

**Game engines considered and rejected for the world layer:**
- UE5 — known well (materials, lighting, level design) but *none of that is
  used here*. Would mean C++ against a large procedural-mesh API, Visual
  Studio, long compiles, Unreal's macro system and containers. Worst iteration
  loop of the options.
- Unity — better than UE5 if going the engine route: C# compiles in seconds,
  simple procedural mesh API.
- **Engines are right for the entity layer** (turtle models, HUD, camera,
  lighting — and loading real turtle models is genuinely awkward in raw wgpu)
  and **wrong for the voxel layer**, because no engine does voxels natively and
  the meshing gets written by hand regardless.
- Voxel plugins exist for UE5 and Unity but are built for terrain generation
  and destruction, not for streaming a live world from a network source with
  LOD and TTL. Licensing and current feature split unverified.

Networking: `tokio-tungstenite` for the live websocket, `serde` for
deserialisation, `reqwest`/`ureq` if any plain HTTP is needed. Read-only by
default with an explicit control mode for anything that moves a turtle.

---

## 11. Open questions

**Design:**
- Does the turtle claim its id, or does the server assign one? Claiming means
  anything reachable can claim to be turtle 46 and overwrite it — same shape as
  the BOLA question on MistAPI.
- Reactive (request-driven) vs tick-driven server loop. Probably **both**:
  handlers are reactive; a tick is needed for what nothing triggers — watchdog
  timeouts, staleness sweeps, task scheduling. Reactive handles events; the
  tick handles the *absence* of events.
- Row-major vs Morton for the hot dense array — benchmark inside the
  pathfinder.
- LUT vs magic bits for Morton encoding — benchmark, don't assume the 2013
  numbers.
- Y-major vs X-major RLE ordering — one-line change, test both.
- Whether hand-rolled RLE earns its place at all once zstd is in the stack.
- Chunk-level connectivity caching for `HPA*` — compute on decode, or lazily?

**Verify before building on:**
- `string.pack` / `string.unpack` availability in CC's Cobalt runtime
- `bit32` vs native bitwise operators in the current CC Lua version
- Websocket concurrency limits in `computercraft-server.toml`
- Whether MagicDNS resolves from inside the JVM

---

## 12. Rejected / deferred

| Thing                                              | Status                       | Why                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
|----------------------------------------------------|------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Octree for world storage                           | **rejected**                 | deep/narrow, pointer overhead dominates on sparse irregular data                                                                                                                                                                                                                                                                                                                                                                                             |
| Dynamic bounded regions + BVH/R-tree               | **rejected for voxels**      | lookup becomes search; overlap merge/split; no dedup; header cost. *Retained for scan records*                                                                                                                                                                                                                                                                                                                                                               |
| Lossy compression of "wild" areas                  | **deferred, probably never** | ~2× on top of a 50–100× stack; irreversible; needs a maintained never-discard list; silent pathfinder wrongness. Better as **display-time LOD** — downsample for rendering, keep the full data                                                                                                                                                                                                                                                               |
| Compressing blocks to a few generic types          | **rejected**                 | real types are needed for X-ray, chests, spawners, ore identification                                                                                                                                                                                                                                                                                                                                                                                        |
| Temporal / video-codec compression across Z slices | **rejected**                 | video works by *motion estimation* between displaced copies of the same scene; Z+1 is a different part of the world, not a displacement. Spatial correlation is already captured by RLE and zstd                                                                                                                                                                                                                                                             |
| Temporal compression **across rescans**            | **valid but unwanted**       | genuinely compresses (few blocks change); only useful for scan *history*                                                                                                                                                                                                                                                                                                                                                                                     |
| Timelapse via stored diffs                         | **rejected**                 | needs a change log instrumenting every block update, not periodic diffs                                                                                                                                                                                                                                                                                                                                                                                      |
| OpenVDB (library or format)                        | **deferred → "tinyvdb"**     | structure is a good fit (shallow wide tree, dense leaves, occupancy bitmasks — essentially the chunk map plus a middle level). But no mature Go implementation, cgo brings a heavy C++ dep, and the *file format* is a large reverse-engineering job. Achievable version: implement the structure in Go, write a minimal subset of the format (one grid type, one codec) so Blender can open it. **Name it tinyvdb; state clearly it is not VDB-compatible** |
| VDB middle levels now                              | **deferred**                 | signal to add them: finding yourself hand-writing range queries and coarse-occupancy checks                                                                                                                                                                                                                                                                                                                                                                  |
| Per-step `gps.locate()`                            | **rejected**                 | modem broadcast + multi-host reply + timeout, per block. Movement primitives return booleans; dead reckoning is exact                                                                                                                                                                                                                                                                                                                                        |
| Infinite cost for protected blocks                 | **rejected**                 | propagates through addition, NaN risk with floats. Use impassable / skip neighbour generation                                                                                                                                                                                                                                                                                                                                                                |
| Building the hub for v0.1                          | **deferred**                 | no measured limit yet                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| Shared memory between Go and viewer                | **rejected**                 | gRPC or websocket over a local socket is far faster than needed. Shared memory remains a *quarantined learning project* only                                                                                                                                                                                                                                                                                                                                 |
| FFI offload (Rust/C++) from a custom mod           | **rejected**                 | the bottleneck is the **tick budget**, not Java's speed. Java is far faster than Lua for serialising 600 entries, and a mod can already spawn a thread — which is exactly what CC's HTTP does. Large work for an unmeasurable speedup                                                                                                                                                                                                                        |
| Custom CC peripheral mod                           | **realistic, later**         | CC:Tweaked has a documented peripheral API for other mods. Good version: **a peripheral that does the expensive work in Java off the main thread and returns a compact result to Lua** — e.g. a pre-serialised binary blob of the whole ME network. Deletes the timeout problem entirely                                                                                                                                                                     |
| Complex numbers / quaternions for rotation         | **rejected**                 | four axis-aligned states, `(r+1)%4`                                                                                                                                                                                                                                                                                                                                                                                                                          |
| Secondary indexes on the turtle registry           | **rejected**                 | scan with a predicate; ~1µs at fleet scale; indexes are caches with a consistency problem                                                                                                                                                                                                                                                                                                                                                                    |
| Anonymous functions for memory scoping             | **rejected**                 | escape analysis decides stack vs heap; closures can *cause* escape. Scope is visibility, heap is lifetime — unrelated                                                                                                                                                                                                                                                                                                                                        |
| `bevy` rejection                                   | **reversed**                 | see §10.5                                                                                                                                                                                                                                                                                                                                                                                                                                                    |

---

## 13. References

- Baert, J. — *Morton encoding/decoding through bit interleaving:
  Implementations* —
  <https://www.forceflow.be/2013/10/07/morton-encodingdecoding-through-bit-interleaving-implementations/>
  (LUT, magic bits, and for-loop implementations with benchmarks; the author
  works on sparse voxel octrees)
- *Hilbert and Morton curves at three levels of recursion* —
  <https://www.researchgate.net/figure/Hilbert-and-Morton-curves-at-three-levels-of-recursion_fig1_220492433>
- *Demand-driven table generation algorithm* —
  <https://www.researchgate.net/figure/Demand-driven-table-generation-algorithm-output-table-row-sets-and-resets-the-output-set_fig3_220492433>

**To read:**
- Nystrom, B. — *Crafting Interpreters* (free online; the second half builds a
  bytecode VM)
- Yamauchi, B. (1997) — frontier-based exploration, original paper
- Koenig & Likhachev — `D* Lite` and `LPA*`
- Botea et al. — `HPA*` (Hierarchical Pathfinding A*)
- Minecraft **Anvil** format and modern palette containers (wiki)
- **Distant Horizons** (open source) — solves "keep a big world cheaply, render
  it at distance"
- **Chunky** — large-scale world data storage
- 2b2t world compression efforts — a 20-year-old heavily-modified world, same
  problem shape
- Sean Eron Anderson — *Bit Twiddling Hacks*

**Searchable terms surfaced here:** brickmap, sparse voxel octree, palette
compression, Morton code / Z-order curve, BMI2 PDEP, Shannon entropy, region of
interest coding, varint, self-delimiting encoding, correlation id, watchdog,
dead reckoning, scan matching, SLAM, freespace assumption, frontier-based
exploration, next-best-view, MAPF, LRU eviction, greedy meshing, HPACK, string
interning, idempotency, functional core / imperative shell, dependency
inversion.