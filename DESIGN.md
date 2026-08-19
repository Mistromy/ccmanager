# Design notes

Rationale, subsystem detail, and rejected alternatives for the ComputerCraft
Turtle Swarm Manager. Goal, status, constraints, layout, and versions are in
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
8. [Networking and API](#8-networking-and-api)
9. [AE2 storage subsystem](#9-ae2-storage-subsystem)
10. [Viewer](#10-viewer)
11. [Open questions](#11-open-questions)
12. [Testing philosophy](#12-testing-philosophy)
13. [Go conventions and libraries](#13-go-conventions-and-libraries)
14. [Rejected / deferred](#14-rejected--deferred)
15. [References](#15-references)

---

## 1. Turtle registry

**Store:** unexported `map[uint16]*Turtle` at package level in `turtles`,
created with `make` at startup. Exported functions are the only doors in.

**Why a map, not a slice indexed by ID:**
- A slice must be grown to length ≥ 47 before you can write index 46. Every
  registration would need a length check and a fill loop.
- Map keys have no ordering requirement and no gaps to manage.
- CC computer ids climb globally — turtle 4700 on day one is plausible.

**Why pointers, not values:**
- Assignment in Go always copies. `for _, t := range m` gives you a *copy*;
  writing `t.Fuel = 500` modifies the copy silently.
- **Map values are not addressable** — `m[7].Fuel = 500` doesn't compile. This
  falls out of the growth strategy: when a map exceeds its load factor it
  reallocates buckets and rehashes, physically moving every value. Allowing
  pointers into map values would create dangling pointers.
- A `Turtle` is ~700 bytes (the `[16]Item` array is stored *inline* — arrays
  are values in Go). Copying 8 bytes beats copying 700.
- The value-map alternative — read a copy, modify, write back — works but has
  two failure modes: the write-back you forget on an error branch, and **lost
  updates** when two handlers read-modify-write concurrently. A mutex doesn't
  fix the second, because the operation spans multiple statements.

**Keep `ID` in the struct even though it's the map key.** A `*Turtle` passed to
a function has no idea what key it was stored under; logging, error messages,
and wire responses all need it. Cost: 2 bytes. Invariant: `m[46]` holds a
turtle whose ID is 46 — set once at creation, never elsewhere.

**Lookup by anything other than ID:** scan with a predicate
(`func(*Turtle) bool`). At fleet scale a linear scan is ~1µs, three orders of
magnitude below the network round-trip. Secondary indexes are caches with a
consistency problem and multiply write cost; don't build them until a
measurement demands it.

**Parent/children:** store the parent ID only (adjacency list). Derive children
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

**Known design smell:** both the registry map and the password `hash` are
package-level globals. That works, and it makes tests awkward — they have to
reach into package state to set up, and test order starts to matter. The
eventual fix is a struct holding the state with methods on it, instantiated
per server. Not worth doing yet; worth noticing it has now come up twice.

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

**The same rule applied to timing** decides where the control loop lives when
physics vehicles or entities are involved: anything with a real-time deadline
runs locally in Lua, anything without one runs in Go. Fast local controller,
slower planner above it, fleet layer above that. That's the standard autonomous
vehicle architecture and it falls out of the same principle.

### 2.4 Lifecycle and reporting

A command is not a function call with a return value. It's a message with a
lifecycle: **queued → sent → acknowledged → completed/failed.**

- Every sequence carries a **correlation ID** so a late report from an
  abandoned job isn't applied to the current one. Cannot be a counter you
  reset.
- Failure reports carry an **index into the sequence** plus a reason.

**Reporting policy:** silence on success. Report on error, drift, or bulk data
(a completed scan). Success is signalled by requesting more work.

**Per-action acknowledgements were considered and rejected** for movement — an
ack per step means one round trip per block, which is the polling storm the
batched protocol exists to avoid, and the heartbeat already carries the same
information on a timer. Acks are worth it only for actions that can silently
half-succeed or must not be repeated: placing valuable blocks, depositing into
chests.

⚠️ **Hole in the silence-on-success policy:** a dead turtle and a working
turtle both produce silence. Out of fuel, chunk unloaded, creeper, Lua crash.
Fix:
- **Heartbeat** — tiny periodic "still here, on step 14"
- **Watchdog** — server presumes death after N seconds and requeues the task
- The server knows how long a sequence should take, so the timeout is
  computable
- Recovery: dispatch another turtle

⚠️ **Cargo recovery is a distinct case.** A turtle dying mid-haul holding 64
iron means the items exist but the task is incomplete. "Recover the cargo" and
"redo the task" are different responses, and conflating them either duplicates
or loses items.

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
the world cache. `turtle.forward()` returning false *is* the path verification;
no separate check is needed, only the report on failure.

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
fails on the hot path and on bookkeeping, not on the arithmetic. It is retained
for scan records, §3.6.)*

### 3.3 Per-chunk representation

In order of value:

1. **Uniform flag** — whole chunk is one type. 2 bytes for 4,096 blocks.
2. **Palette** — `[]uint16` of the distinct types present in *this* chunk. The
   **array index is the local ID**; no separate mapping table needed.
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
- **Scan records** — `{bounds, timestamp, turtle_id, cost}` list, answers
  *when* and *whether*

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
- **`Rot` is `uint8`** — four states, don't spend 8 bytes on it.
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
- **Integer `abs`** — Go has no builtin for ints (`math.Abs` is float64 only).
  A three-line function compiles to a conditional move. `sqrt(x*x)` is two
  float ops for a sign flip.

### 3.8 Serialisation

**Chunk payloads: write raw bytes.** The chunk *is* a byte array; there's no
serialisation layer needed. `f.Write(data)` plus a small header.

**Metadata (scan records, turtle state, block registry): `encoding/gob`.**
Native Go binary format, handles structs/maps/slices automatically, far smaller
and less code than JSON. Caveat: **Go-only** (the Rust viewer can't read it —
fine, since it talks to Go over websocket and Go transcodes), and it embeds
type info, so renames can break old files.

**Config (port, password hash) is a different problem from state.** Config is
written once and read at startup; `os.UserConfigDir()` gives the right path per
platform. State changes constantly and must survive a crash mid-write — write
to a temp file then rename, since rename is atomic.

Note that `encoding/json` marshals `[]byte` as base64, so a stored bcrypt hash
round-trips correctly but won't look like what you expect in the file.

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

### 4.1 What A* is doing

`f = g + h`. **`g`** is the real cost accumulated so far. **`h`** is the
heuristic — an estimate of the cost remaining to the goal. A* always expands
the node with the lowest `f`.

Set `h = 0` and A* degrades exactly into Dijkstra, which explores outward in
all directions because it has no idea where the goal is. Make `h` dominate `g`
and it becomes greedy best-first — fast, often wrong.

Two properties of `h` matter:

- **Admissible** — never overestimates the true remaining cost. If it does, A*
  still returns *a* path, just not necessarily the optimal one.
- **Consistent** — the estimate never drops by more than the cost of the step
  taken. Guarantees you never need to reopen a closed node.

**Manhattan distance** (`|Δx| + |Δy| + |Δz|`) is the natural heuristic here and
is admissible, because turtles cannot move diagonally — you can never beat the
axis-sum.

**`g` and `h` are separate and should stay separate.** `g` is real resource
expenditure; `h` is a guess about the future. Rule of thumb: **if it costs the
turtle something, it goes in `g`. If it's only a hint about where to look, it
goes in `h`.** Rescan cost for unknown terrain is `g`, not `h`.

But `h` is not free — pushing it past admissibility changes the *answer*, not
just the search speed. **Weighted A\*** is the formal version of that trade:
multiply `h` by ε > 1 and you get a **bounded suboptimal** path, guaranteed
within ε× optimal. **ARA\*** (Anytime Repairing A*) starts with a high ε,
returns a path fast, then lowers ε to improve it while time allows — directly
applicable to a fleet where a turtle can start moving on a rough path while the
server refines it.

### 4.2 Orientation belongs in the node

Plain A* over `(x, y, z)` produces **staircase paths** in open space. When it
expands a node it has no idea which way the turtle was facing to get there, so
every zigzag path and the straight L-shaped path cost identically and the
tie-break picks arbitrarily. Rotations cost real time; the search can't see
them.

**Fix: the node is `(x, y, z, facing)`.** The state space is 4× larger but
turns become visible actions with their own cost, and A* naturally minimises
them. This is **state lattice planning** — standard for any robot with a
heading.

Consequence: the successor function changes shape. From `(x,y,z,north)` the
successors are *forward* (one cell north, same facing), *turn left*, *turn
right* — plus up and down, which turtles do without turning. Three to five
actions, not six neighbours. **The path A\* returns is then already a command
sequence**, with no post-processing from coordinates to turtle instructions.

Manhattan remains admissible. A minimum-turns term can be added to tighten it,
but get it wrong and optimality goes.

### 4.3 Search shaping

**The bounding box between start and goal contains every optimal Manhattan
path.** Any move outside it is strictly wasted, because you'd have to come
back. That is a real property and biasing the search toward it is a real
optimisation.

⚠️ **Bias, never restrict.** If a 20-block wall sits between you and a target
three blocks away, the optimal path leaves the box. Hard-restricting makes it
unfindable.

**Tie-breaking:** adding a small term proportional to distance from the
straight start-goal line breaks ties toward the direct route and dramatically
shrinks the explored region. It must be *small* — scaled by something like
1/1000 — so it only ever decides between otherwise-equal options. Larger and
it's no longer admissible.

Note this becomes unnecessary once orientation is in the node: with turn costs,
the staircase genuinely costs more than the L-path, so there's no tie left to
break.

### 4.4 Cost model

Nearly all blocks are breakable, so this is not a maze — it's a weighted
traversal. Solid blocks cost more (fuel + time), expensive/rare blocks cost
much more to discourage breaking them.

**Measure real timings rather than guessing weights.** Rotation costs time but
no fuel. Breaking is instant but costs fuel and still burns a tick for the
call. Moving costs both. Two things to get right:

- **Time and fuel must collapse to one scalar**, because A* minimises one
  number. That exchange rate is a *policy* decision, not a measurement — make
  it a tunable constant and the whole fleet shifts between "fast and wasteful"
  and "slow and cheap" with one value.
- **Use the median, not the mean.** Break times vary with hardness and tool,
  and server tick lag adds outliers.

`turtle.forward()` has a built-in cooldown beyond the tick — likely the
dominant cost, and a fixed number worth measuring once.

**Unknown space:** charge the expected cost of a generic solid block, plus a
**small uncertainty penalty** so that given two equal paths the planner prefers
the known one. (The robotics "freespace assumption" doesn't apply — that exists
for robots that can't pass through obstacles at all.)

Later refinement: derive the expected cost from local statistics — average
block cost in nearby scanned chunks, or per-Y-level averages. Same Bayesian
shape as the WoT particle filter.

**Protected zones** (user-defined, e.g. base): non-air blocks are
**impassable**, not infinite-cost. Skip them during successor generation.
Infinity propagates through addition, behaves oddly in comparisons, and
produces NaN if floats are ever involved. Air in the same zone routes normally.

⚠️ **Consequence:** a protected zone can make the goal unreachable. `A*` will
exhaust the open set and return nothing. "No path exists" must be a handled
outcome, not a hang.

### 4.5 Performance

**`A*`'s own bookkeeping is usually bigger than the world data.** Open set,
closed set, and came-from map for a long path run to tens of thousands of
nodes. Preallocate, reuse between runs, prefer flat arrays indexed by node over
maps. This matters more than chunk cache behaviour.

Cache sizes for reference (modern desktop): L1d 32–48KB (~4 cycles), L2
512KB–2MB (~14), L3 8–64MB (~40–50), RAM ~200+. A 50-chunk working set at 16³
is ~200KB — comfortably L2, and L2 is not a problem. You cannot pin anything to
L1; the levers are access pattern and working-set size. Go has no prefetch
intrinsics.

### 4.6 Replanning

**Replan on relevance, not on change.** A scan 500 blocks away doesn't affect
the current path. Check whether changed chunks intersect the remaining path or
its neighbourhood; if not, ignore. Kills the vast majority of replans for free.

**When replanning, use an incremental algorithm — `D* Lite` (built on
`LPA*`).** Designed for exactly this: an agent moving through partially-known
terrain, discovering obstacles, reusing the previous search tree and
recomputing only the affected portion. A block dropped in a corridor
invalidates a local detour and leaves the rest of the path untouched.

The turtle doesn't do the fixing. It reports "blocked at step 47"; the server
runs the incremental update and sends a patched tail.

### 4.7 Long distances — `HPA*`

For a 2,000-block journey, block-level `A*` expands an intractable number of
nodes. Hierarchical Pathfinding `A*`:

1. Precompute per-chunk entry/exit connectivity and costs (cache alongside the
   decoded chunk)
2. Path at the **chunk graph** level — ~100 nodes instead of millions
3. Refine within only the chunks the abstract path uses

Chunk boundaries are already the right abstraction level. This matters far more
than inner-loop cache behaviour, and it is what makes open-space pathing
viable at all.

**Jump Point Search** was considered and doesn't fit: it assumes uniform costs,
and this world has variable ones (air vs stone vs valuable ore). Weighted
variants exist and lose most of the benefit.

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
- **Travel cost** = `A*` distance from the turtle's current position, **plus
  scan fuel** for anything above radius 8

**Multi-agent:** two turtles must not target the same frontier.
- Cheap: **claim-on-assignment** — reserve the region, others treat as taken
- Better: discount utility by proximity of other turtles already heading there,
  producing natural spreading without hard reservations

**Incremental frontier update** — only recompute in regions that changed. A new
scan creates frontiers at its edges and destroys the ones it consumed.

### 5.3 Passive scanning is the main mechanism

**Radius 8 is free**, so turtles scan continuously while travelling and
frequent areas stay fresh at zero cost. The heatmap and the traffic
distribution are the same distribution, meaning maintenance is automatically
where it's needed. Explicit rescan tasks only fire where nothing happens to
travel.

Paid scans (radius up to 16) are for deliberate exploration where the
information gain justifies the fuel, and that cost belongs in the utility
function.

### 5.4 Protected zones need *more* rescanning, not less

Low information gain (nothing to discover) but **high volatility** — the player
changes them constantly without telling the server. The world model of the base
is the thing most likely to be wrong. Volatility should win over information
gain in the priority function.

---

## 6. Task system

**Turtles are workers, not roles.** A turtle isn't "a hauler"; it's a worker
currently assigned a haul task. One queue holds heterogeneous work — explore
this frontier, rescan this region, move these items — all scored by the same
priority function.

**Capability matching**, forced by the peripheral constraint: a task declares
what it needs (tool, scanner, modem, storage), a turtle declares what it has.
Filter before priority sort. This is also what lets non-turtle agents into the
same queue later.

**Inventory-aware task generation** is the piece that turns dispatch into
automation: something polls machine peripherals, notices an output buffer
filling or an input running dry, and emits a task. Throughput measured over
time turns that from reactive into predictive — the system knows a buffer is
*about to* matter.

**Multi-turtle work in one volume** — the hard part isn't dividing the zone,
it's collision and deadlock:
- **Spatial partitioning** — disjoint sub-volumes, one per turtle. Removes most
  conflict.
- **Cell reservation** — claim the target cell before moving into it.
- Remaining deadlocks resolve by timeout and retry.

The general problem is **MAPF** (Multi-Agent Path Finding) and is genuinely
hard in full generality. The above handles the practical case.

**Turtles need a home/idle position** so they aren't scattered when work
arrives, and so refuelling has somewhere to happen.

---

## 7. Heatmap

**Two separate numbers, because they answer different questions:**

| Number           | Decay       | Drives                             |
|------------------|-------------|------------------------------------|
| Cumulative total | none        | the visual (2b2t-highway-map look) |
| Decayed recency  | exponential | rescan priority, TTL, hot chunks   |

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

## 8. Networking and API

### 8.1 Naming (four things are all called "server")

| Term                 | Meaning                                                   |
|----------------------|-----------------------------------------------------------|
| **host**             | the physical machine                                      |
| **MC server**        | the Minecraft server                                      |
| **backend**          | the Go program                                            |
| **broker** / **hub** | the in-game computer relaying between turtles and backend |

### 8.2 The API is the product

The Rust viewer is one consumer of the API, not the thing the API exists for.
Every user action has to be expressible through it and every piece of state
readable from it. Consequence: **design it for a client that doesn't exist
yet**, and build/test it entirely with `.http` files and `curl` long before any
Rust exists.

### 8.3 Four surfaces, split by who's asking

| Surface       | Auth                                   | Purpose                        |
|---------------|----------------------------------------|--------------------------------|
| `/turtle/*`   | shared credential (or per-turtle later) | fleet traffic                  |
| `/api/*`      | user accounts, scoped permissions      | the Rust client, human actions |
| `/internal/*` | separate service credential            | service-to-service             |
| public stats  | none, read-only                        | portfolio display              |

All on one server, different route prefixes, different middleware per group.
`http.NewServeMux()` plus a prefix-based wrapper — no separate processes
needed.

**Auth on private endpoints too, even behind Tailscale.** Tailscale
authenticates the *device*, not the *action*. Every process on every tailnet
node reaches every endpoint — a dependency in some unrelated project, a browser
extension, a shared node. The threat is lateral movement, not someone breaking
Tailscale. Cost is one line per route. Tailscale ACLs restrict which nodes
reach which ports and complement app-level auth rather than replacing it.

**BOLA sits on top of that** and is the thing the middleware can't do:
authenticated ≠ authorised for *this object*. Once the broker holds one
websocket for the whole fleet, turtle identity inside that stream is a message
field, not a verified credential — turtle 46 claiming to be turtle 12 is just a
number. Fine while everything is yours; worth knowing it's the same shape as
the MistAPI question, one layer down.

**Public exposure changes the model materially.** Behind Tailscale, auth is a
second layer and a bug is survivable. Public means the auth *is* the security,
and automated scanners find weaknesses within days of a DNS record existing.
Prerequisites before anything is public: real bcrypt cost, authenticated
password-setting, rate limiting (bcrypt is slow by design, so unlimited
attempts is a CPU exhaustion attack), server read/write timeouts, and
structured logging on auth failures.

**Better than fully public for remote access:** expose it but gate it at Caddy
with **mTLS or a long random token in the path**, so nothing reaches the auth
handler without already holding a secret. Fraction of the exposure, same
access from anywhere.

**Public stats should be push-only.** A read-only websocket that only ever
emits has nothing to inject into. A request-response API taking parameters and
proxying live into the tailnet is an SSRF-shaped bridge from the internet into
the private network, and a free DoS amplifier.

### 8.4 Transport

**v0.1: direct HTTP, one turtle.** No broker. Add it when a real limit is hit,
and you'll then know which limit and why.

**v0.2+: gateway pattern** — one turtle holds a single websocket to the backend
and relays to the fleet over rednet. Trade-offs:

- **Rednet is unreliable** — no delivery guarantee, no acknowledgement, dropped
  messages are not retried. Needs an ack-and-retry layer.
- **Single point of failure** — broker dies, fleet goes deaf. Turtles should
  fall back to direct HTTP after N failed contacts. Mitigated by the websocket:
  the backend knows instantly, can freeze or shut down the fleet and alert.
- **Wired modems are physical blocks** connecting a computer to distant
  peripherals — useless for mobile turtles, fine for the AE2 computer.
- Ender modems remove the range concern.
- The chunkloader manager allows remote activation of a turtle.

Routing overhead is 2 bytes (uint16 destination ID).

**Websockets structurally:** a websocket starts as a normal HTTP GET with
`Upgrade: websocket`; the server replies `101 Switching Protocols` and the
connection stops being HTTP. HTTP/1.1 is request-response only and the client
always starts — that, not performance, is why polling vs websocket was ever a
question.

*(Terminology: a **webhook** is an HTTP callback someone POSTs to you. A
**websocket** is a persistent bidirectional connection. Server→turtle push
needs the second.)*

### 8.5 HTTP correctness rules

These are the ones that produce silent wrong behaviour rather than errors:

- **Exactly one write per response.** The first write locks in the status code.
  `Fprint` followed by `http.Error` sends 200 with both bodies concatenated and
  logs "superfluous WriteHeader call". Pick one.
- **A bare `return` after logging sends 200.** Every error path needs an
  explicit status.
- **`http.Error` does not stop execution.** It writes and returns; you still
  need `return` after it.
- **Headers must be set before any body.** Anything set after the first write
  is silently ignored.
- **401 vs 403:** 401 = "I don't know who you are"; 403 = "I know and you're
  not allowed."
- **Don't send raw error text to clients.** Decoder errors mention Go types and
  byte offsets. Log the detail, send a short stable message. Internal messages
  becoming the API contract is a maintenance trap.
- `http.HandleFunc` registers on the package-level global `DefaultServeMux` —
  any imported package can add routes (this is how `net/http/pprof` works).
  `http.NewServeMux()` gives you your own.
- `http.ListenAndServe` has **no timeouts**. Use `&http.Server{}` with
  `ReadTimeout` / `WriteTimeout`.
- **`http.MaxBytesReader`** before decoding caps request body size. That, not a
  field-length check, is what stops someone streaming a gigabyte at you.
- Go 1.22+ mux supports `"POST /register"` patterns and `{id}` path values via
  `r.PathValue("id")` — deletes method checks and simplifies routing.

### 8.6 Middleware

The auth + method block repeats in every handler. Two options:

- **Simple:** `check(w, r, method) bool`, caller does
  `if !check(...) { return }`. The `return` is still forgettable, and
  forgetting it is silent.
- **Middleware:** a function taking an `http.HandlerFunc` and returning a
  wrapped one. On failure it writes the error and **never calls the inner
  handler**, so unauthenticated execution is structurally impossible.
  Stackable — logging wrapping auth wrapping rate-limiting.

Currently on the simple form. Convert once there are enough handlers to feel
it.

### 8.7 Errors across the package boundary

Validation lives in `turtles`, status codes live in `server`, and the domain
must not know about HTTP. **Sentinel errors** bridge that:

```go
var (
    ErrAlreadyRegistered = errors.New("turtle already registered")
    ErrInvalidName       = errors.New("invalid turtle Name")
    ErrInvalidID         = errors.New("invalid turtle ID")
)
```

The handler maps them with `errors.Is` — `errors.Is` rather than `==` because
it follows wrapping if context is added with `%w`. Without this, every domain
failure collapses to one status code.

**Validate input before checking state**, or the error returned depends on
unrelated map contents.

### 8.8 Where request data goes

| Location     | Access                                  | Use for                        |
|--------------|-----------------------------------------|--------------------------------|
| Query string | `r.URL.Query().Get("id")`               | small simple values            |
| Path         | `r.PathValue("id")` (Go 1.22+, own mux) | resource identity              |
| Headers      | `r.Header.Get(...)`                     | credentials, protocol metadata |
| Body         | `json.NewDecoder(r.Body).Decode(&req)`  | anything structured            |

**Never put credentials in a URL** — logged by every proxy and server in the
path, in browser history, in the Referer header. Not a GET-specific rule; a URL
rule. The `Authorization` header is correct.

**JSON decoding is reflection-driven**, which explains two rules that otherwise
look arbitrary: it needs a **pointer** because it writes into your struct, and
fields must be **exported** because reflection refuses to write unexported
ones. Struct tags map JSON keys to field names; without a tag it matches
case-insensitively.

**The decoder is permissive.** Unknown fields are ignored and missing fields
silently take their zero value, so `{}` decodes cleanly into ID 0 with an empty
Name. Validation after decoding is mandatory, not optional.
`DisallowUnknownFields()` tightens the first half if wanted.

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
- The raw table includes ID, Name, count, and **all NBT**, which is where the
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
- Display planned `A*` paths per turtle — **and the explored set and frontier**,
  colour-coded by cost. That is the single most useful thing for tuning a
  pathfinder: you stop reasoning about why it chose a route and look at it.
- Highlight cached-vs-last-scanned discrepancies

### 10.4 Turtle management

- Live per-turtle stats: fuel, inventory, task, queue depth
- Parent/child and coalition relationships
- Manual control: reboot, halt, reassign, manual movement
- Task queue inspection and reordering

### 10.5 Stack

Original plan was `wgpu` + `winit` + `egui` + `glam` + `bytemuck`, with Bevy
rejected because ECS is a second architecture to learn.

**That rejection is reversed.** The viewer is explicitly a QoL/debugging tool,
not a graphics-learning project, and there isn't time to learn camera maths and
shaders. Bevy gives a render pipeline, camera controls, input, and asset
loading for free, stays code-first (no editor to fight), and runs in RustRover
with normal compile times.

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
  LOD and TTL.

**Charts:** `egui_plot` for live-updating data (immediate mode, redraws every
frame), `plotters` for anything more complex.

Networking: `tokio-tungstenite` for the live websocket, `serde` for
deserialisation, `reqwest`/`ureq` if any plain HTTP is needed. Read-only by
default with an explicit control mode for anything that moves a turtle.

---

## 11. Open questions

**Design:**
- Does the turtle claim its ID, or does the server assign one? Claiming means
  anything reachable can claim to be turtle 46 and overwrite it.
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
- Time-to-fuel exchange rate in the cost function.

**Verify before building on:**
- `string.pack` / `string.unpack` availability in CC's Cobalt runtime
- `bit32` vs native bitwise operators in the current CC Lua version
- Websocket concurrency limits in `computercraft-server.toml`
- Whether MagicDNS resolves from inside the JVM
- Exact scan return shapes for Plethora vs Unlimited Peripheral Works

**Experiments worth time-boxing:**
- **Entity control via the neural interface.** It can control entities and give
  them peripherals, which would allow a swarm of non-turtle agents — possibly
  including native abilities like a skeleton's bow. Unverified and the API
  surface is unknown. *The assumption that entities can do everything turtles
  can, only better, is the thing to test — turtles have a fixed inventory, a
  documented movement API with boolean returns, fuel as a real constraint, and
  chunkloading. Mob AI fighting a control loop is the expected failure.* One
  evening of `peripheral` calls and printing tables, not a design direction.

---

## 12. Testing philosophy

**Test where you cannot verify the answer by hand.** Not where the code is
important, not where dependencies exist — where *looking at the output doesn't
tell you if it's right*.

By that rule:

- **Leaf domain functions with obvious outcomes — skip.** `Register` has five
  branches and each takes one `curl` to confirm. A test there tells you nothing
  you didn't already know from manually testing the change you just made, and
  it costs an edit every time the function changes.
- **Handlers — worth it.** Status-code mapping is easy to get subtly wrong and
  invisible in a browser. The bugs found this way so far were all of this
  shape: bare returns sending 200, double writes, everything collapsing to one
  status code.
- **Pathfinding — mandatory.** A returned path looks plausible whether or not
  it's optimal. An inadmissible heuristic produces a slightly worse route in
  one configuration out of fifty, forever, with no error.
- **Encoding/decoding — mandatory.** Bit-packing bugs produce plausible-looking
  garbage. A round-trip test catches them instantly.

### How to test a pathfinder

**Don't assert a specific path.** Several are usually equally optimal and
tie-breaking changes whenever the heuristic does.

**Assert properties** that hold for every valid path: starts at start, ends at
goal, every consecutive pair differs by exactly one on exactly one axis, no
step enters an impassable block, reported cost equals the sum of step costs,
every turn is a legal 90° step.

**Write a brute-force reference** — Dijkstra with no heuristic, or BFS if costs
are uniform. Slow and obviously correct. Then assert
`astar(world).cost == reference(world).cost`. Same cost, not the same path.
That is the only thing that catches an inadmissible heuristic.

**Build worlds as ASCII in the test file:**

```go
world := []string{
    "S...#....",
    ".##.#.##.",
    "....#..#G",
}
```

Readable, diffable, twenty cases in the time it takes to construct one
programmatically.

**Fuzz it.** `go test -fuzz` generates random worlds and finds the edge case
you didn't think of — usually start equals goal, or a goal fully enclosed.

**Benchmark it.** `go test -bench` on a fixed large world settles the
LUT-vs-magic-bits question with numbers.

### Mechanics worth remembering

- `httptest.NewRequest(method, target, body)` builds a request with no network.
  Target must parse — `""` panics.
- `httptest.NewRecorder()` is a `ResponseWriter` that records instead of
  sending. `w.Code` for status; `w.Result()` allocates a full response and is
  only needed for headers or a parsed body.
- **Fresh recorder per subtest.** Reusing one leaks the previous case's status.
  Requests too — a body can only be read once.
- Assert **both** the return value and the status code. A function returning
  `false` while writing 200 is the auth-bypass bug, and the bool alone can't
  see it.
- Tests live in the same package, so they can set package-level state
  directly (`hash`, the registry map). That works and it's a smell — it means
  test order can matter.
- Test **status codes, not message text**. Messages change; codes are the
  contract.

---

## 13. Go conventions and libraries

### Naming
- **Error strings: lowercase, no punctuation.** They get wrapped, so yours ends
  up mid-sentence: `registering turtle: invalid port`.
- **Wrapping adds context, not repetition.** `fmt.Errorf("decoding request:
  %w", err)`. "Error" is implied by it being an error.
- **`fmt.Errorf`, not `errors.New(fmt.Sprintf(...))`.** `go vet` flags the
  second.
- **Sentinel errors get the `Err` prefix** and live at package level.
- **Don't repeat the package Name.** `turtles.Register`, not
  `turtles.RegisterTurtle`.
- **Getters have no `Get` prefix.** `t.Name()`. Setters do use `Set`.
- Variable Name length scales with the distance between declaration and use.
  `i`, `t`, `w`, `r`, `err` are correct Go.

### Doc comments
- Start with the Name of the thing, full sentences — `go doc` and pkg.go.dev
  extract them verbatim.
- Indent a line to make a code block. `[Name]` in brackets becomes a doc link.
- **Testable examples** (`ExampleFoo` with a trailing `// Output:` comment) are
  compared against real stdout by `go test` — documentation that fails the
  build when it goes stale. Exported identifiers only.

### Libraries

**In use:** `charmbracelet/log` (with `SetReportCaller(true)` for file:line),
`charmbracelet/huh`, `golang.org/x/crypto/bcrypt`.

**Likely needed:**
- `klauspost/compress` — pure-Go zstd. No cgo, so cross-compilation stays easy.
- `gorilla/websocket` or `coder/websocket` — one of these for the broker
- `joho/godotenv` — `.env` loading, for getting secrets out of source
- `adrg/xdg` — full XDG spec; `os.UserConfigDir()` covers the simple case
- `golang.org/x/sync/errgroup` — goroutines with error propagation

**Later, if ever:**
- `bubbletea` + `lipgloss` + `bubbles` — TUI. Currently unnecessary; a
  `GET /turtles` returning the registry as JSON plus
  `watch -n1 'curl -s ... | jq'` is a live view with no code.
- `spf13/cobra` — subcommands, once there's more than one
- `jackc/pgx` — Postgres, if files stop being enough
- `bbolt` / `badger` — embedded key-value stores
- `google.golang.org/grpc` + protobuf — one-source-of-truth codegen across
  languages, if websocket+JSON stops being enough for the Rust client
- `minio/selfupdate` — self-updating binaries. Probably redundant: the Go
  launcher already does a sparse git pull, which *is* the updater.

**Deliberately not used:** ORMs, web frameworks (`gin`, `echo`, `fiber`),
`testify`. `net/http` and the standard library are enough, and understanding
them beats learning a framework's abstraction over them.

---

## 14. Rejected / deferred

| Thing                                              | Status                       | Why                                                                                                                                                                                                                                                                                              |
|----------------------------------------------------|------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Octree for world storage                           | **rejected**                 | deep/narrow, pointer overhead dominates on sparse irregular data                                                                                                                                                                                                                                 |
| Dynamic bounded regions + BVH/R-tree               | **rejected for voxels**      | lookup becomes search; overlap merge/split; no dedup; header cost. *Retained for scan records*                                                                                                                                                                                                   |
| Lossy compression of "wild" areas                  | **deferred, probably never** | ~2× on top of a 50–100× stack; irreversible; needs a maintained never-discard list; silent pathfinder wrongness. Better as **display-time LOD** — downsample for rendering, keep the full data                                                                                                    |
| Compressing blocks to a few generic types          | **rejected**                 | real types are needed for X-ray, chests, spawners, ore identification                                                                                                                                                                                                                            |
| Temporal / video-codec compression across Z slices | **rejected**                 | video works by *motion estimation* between displaced copies of the same scene; Z+1 is a different part of the world, not a displacement. Spatial correlation is already captured by RLE and zstd                                                                                                  |
| Temporal compression **across rescans**            | **valid but unwanted**       | genuinely compresses (few blocks change); only useful for scan *history*                                                                                                                                                                                                                         |
| Timelapse via stored diffs                         | **rejected**                 | needs a change log instrumenting every block update, not periodic diffs                                                                                                                                                                                                                          |
| OpenVDB (library or format)                        | **deferred → "tinyvdb"**     | structure is a good fit (shallow wide tree, dense leaves, occupancy bitmasks — essentially the chunk map plus a middle level). But no mature Go implementation, cgo brings a heavy C++ dep, and the *file format* is a large reverse-engineering job. Achievable version: implement the structure in Go, write a minimal subset of the format so Blender can open it. **Name it tinyvdb; state clearly it is not VDB-compatible** |
| Jump Point Search                                  | **rejected**                 | assumes uniform grid costs; this world has variable ones. Weighted variants lose most of the benefit                                                                                                                                                                                             |
| GPU pathfinding                                    | **rejected**                 | GPU A* parallelises *many independent searches*, not one. A single search is inherently sequential, and moving world data across PCIe per query makes the real bottleneck worse                                                                                                                   |
| Per-step `gps.locate()`                            | **rejected**                 | modem broadcast + multi-host reply + timeout, per block. Movement primitives return booleans; dead reckoning is exact                                                                                                                                                                            |
| Per-action acknowledgements for movement           | **rejected**                 | one round trip per block is the polling storm the batched protocol avoids; the heartbeat carries the same information on a timer. Kept only for actions that can half-succeed                                                                                                                    |
| Infinite cost for protected blocks                 | **rejected**                 | propagates through addition, NaN risk with floats. Use impassable / skip successor generation                                                                                                                                                                                                    |
| Hard-restricting search to the start-goal box      | **rejected**                 | the optimal path legitimately leaves it when a wall is in the way. Bias only                                                                                                                                                                                                                     |
| Building the broker for v0.1                       | **deferred**                 | no measured limit yet                                                                                                                                                                                                                                                                            |
| A TUI for fleet inspection                         | **rejected for now**         | the interface is the API. `GET /turtles` + `watch` + `jq` is a live view with no code                                                                                                                                                                                                            |
| Shared memory between Go and viewer                | **rejected**                 | gRPC or websocket over a local socket is far faster than needed. Shared memory remains a *quarantined learning project* only                                                                                                                                                                     |
| Python anywhere in the core                        | **rejected**                 | adds a runtime, a dependency chain, and an interface boundary to a concurrency-heavy network service in exchange for nothing. Collaborator work should be **separate and bounded** — a dashboard, a log analyser, a bot against the API — not a module inside it                                  |
| FFI offload (Rust/C++) from a custom mod           | **rejected**                 | the bottleneck is the **tick budget**, not Java's speed. Java is far faster than Lua for serialising 600 entries, and a mod can already spawn a thread — which is exactly what CC's HTTP does                                                                                                    |
| Custom CC peripheral mod                           | **realistic, later**         | CC:Tweaked has a documented peripheral API for other mods. Good version: **a peripheral that does the expensive work in Java off the main thread and returns a compact result to Lua** — e.g. a pre-serialised binary blob of the whole ME network                                                |
| Complex numbers / quaternions for rotation         | **rejected**                 | four axis-aligned states, `(r+1)%4`                                                                                                                                                                                                                                                              |
| Secondary indexes on the turtle registry           | **rejected**                 | scan with a predicate; ~1µs at fleet scale; indexes are caches with a consistency problem                                                                                                                                                                                                        |
| Value map for the registry                         | **rejected**                 | forgotten write-backs on error branches, and lost updates under concurrency that a mutex can't fix because read-modify-write spans statements                                                                                                                                                    |
| Anonymous functions for memory scoping             | **rejected**                 | escape analysis decides stack vs heap; closures can *cause* escape. Scope is visibility, heap is lifetime — unrelated                                                                                                                                                                            |
| `bevy` rejection                                   | **reversed**                 | see §10.5                                                                                                                                                                                                                                                                                        |

### Create Aeronautics / physics vehicles — investigated, blocked on platform

The Simulated Project (Simulated + Aeronautics + Offroad, built on **Sable**,
not Valkyrien Skies) has an exceptional CC integration: Simulated ships
peripheral support in-tree, and **Create: Avionics** (MIT) adds peripherals for
~33 block types with generated Lua docs.

The sensor and actuator surface is a genuine robotics platform —
`gimbal_sensor` gives pitch/roll, angular rates, gravity vector and linear
acceleration on the body frame; `altitude_sensor`, `velocity_sensor`,
`navigation_table`, `laser_pointer`/`laser_sensor` for rangefinding; and on the
write side propeller bearings, `throttle_lever`, `analog_transmission` (a
scriptable variable-ratio gearbox), `wheel_mount` with `setSteering(-1..1)` and
`setBrake(0..1)` bypassing redstone, gas providers, and `physics_assembler` for
assembling contraptions from Lua. The docs' own example is a three-peripheral
P-controller locking heading, with a guide on `mainThread` yielding and a note
that 5 Hz and 20 Hz feel different.

**Blocked: MC 1.21.1, NeoForge only.** The current pack is 1.20.1 Fabric with
335 mods, several of them Fabric-native. That's a rebuild, not a migration.

**And the performance ceiling may kill it regardless** — a server can't
plausibly simulate more than a couple of articulated walkers, and fleet
coordination for a fleet of two isn't a fleet.

Relevant only if the pack is ever rebuilt lean around CC. Iris shader
incompatibility is a known issue and is the same failure mode that caused the
earlier VS/Clockwork removal.

---

## 15. References

- Baert, J. — *Morton encoding/decoding through bit interleaving:
  Implementations* —
  <https://www.forceflow.be/2013/10/07/morton-encodingdecoding-through-bit-interleaving-implementations/>
- *Hilbert and Morton curves at three levels of recursion* —
  <https://www.researchgate.net/figure/Hilbert-and-Morton-curves-at-three-levels-of-recursion_fig1_220492433>
- *Demand-driven table generation algorithm* —
  <https://www.researchgate.net/figure/Demand-driven-table-generation-algorithm-output-table-row-sets-and-resets-the-output-set_fig3_220492433>
- Create: Avionics documentation — <https://solastrius.github.io/CreateAvionics/>
- The Simulated Project — <https://github.com/Creators-of-Aeronautics/Simulated-Project>

**To read:**
- Nystrom, B. — *Crafting Interpreters* (free online; the second half builds a
  bytecode VM)
- Amit Patel / redblobgames — A* pages, including tie-breaking and heuristic
  design, with interactive demos
- Yamauchi, B. (1997) — frontier-based exploration, original paper
- Koenig & Likhachev — `D* Lite`, `LPA*`, and `ARA*`
- Botea et al. — `HPA*` (Hierarchical Pathfinding A*)
- Minecraft **Anvil** format and modern palette containers (wiki)
- **Distant Horizons** (open source) — solves "keep a big world cheaply, render
  it at distance"
- 2b2t world compression efforts — a 20-year-old heavily-modified world, same
  problem shape
- Sean Eron Anderson — *Bit Twiddling Hacks*
- *Effective Go* — style and idiom conventions

**Searchable terms surfaced here:** brickmap, sparse voxel octree, palette
compression, Morton code / Z-order curve, BMI2 PDEP, Shannon entropy, region of
interest coding, varint, self-delimiting encoding, correlation ID, watchdog,
dead reckoning, scan matching, SLAM, freespace assumption, state lattice
planning, weighted A*, bounded suboptimal search, frontier-based exploration,
next-best-view, MAPF, LRU eviction, greedy meshing, HPACK, string interning,
idempotency, sentinel errors, functional core / imperative shell, dependency
inversion, BOLA, SSRF.