# ComputerCraft Turtle Swarm Manager

A distributed fleet manager for ComputerCraft turtles. Go backend, Lua agents,
Rust viewer. Written by hand, no AI-generated code.

Design rationale, rejected alternatives, and subsystem detail live in
[DESIGN.md](DESIGN.md).

---

## Status

HTTP server up, port validation with a table-driven test suite, skeleton auth.
`RegisterTurtle` is stubbed. Nothing talks to a real turtle yet.

**Unresolved: the fleet has no task that requires a fleet.** On a heavily
modded server every physical job a turtle could do is already done better by
something else:

| Job                              | Already solved by                       |
|----------------------------------|-----------------------------------------|
| Bulk mining                      | dedicated mining rig                    |
| Bulk excavation / building       | quick-build mod (shape + bounds)        |
| Common stone throughput          | Create drills                           |
| Static automation (reactor fuel) | one stationary turtle, no system needed |

X-ray is largely redundant for the same reason — ores get mined by the rig
anyway. It retains value only for **chests, spawners, and structures**.

What turtles uniquely are: **mobile sensors reporting to a central world
model.** Nothing else in the world does that. The jobs that genuinely need this
system are about *knowing*, not *doing* — world map, staleness tracking,
exploration, discrepancy detection.

**Decision:** build v0.1, then park. The system is the point and that's a
legitimate reason to build it, but not a reason to spend months on a task layer
with no payload.

---

## Constraints

### Scan
- Fuel cost scales with radius
- Cube shaped, radius selectable
- **Max radius unverified** — possibly 8, possibly 16. Changes storage
  estimates by 8×. Verify before planning around it.
- Radius *r* means a cube of side `2r+1`, so r=16 is 33³ = 35,937 blocks

### Turtle inventory and peripherals
- 16 inventory slots
- Two upgrade slots, one per side
  - one holds the main tool, the other holds the Peripheralium Hub
- Peripheralium Hub, two tiers:
  - Base — 3 peripherals
  - Netherite — 7 peripherals
- Extra peripherals can be carried in inventory and swapped at runtime
- Baseline non-specialized turtle: ender modem, scanner, chunkloader, tool
  (caps out the base hub)
- Shulker storage system: later

### Network
- CC:Tweaked HTTP runs **off-tick** on a Java thread
- CC:Tweaked Lua runs **on-tick** — serialization is the cost that times you
  out, not the transfer
- Config caps concurrent HTTP requests and websockets, per-computer and
  globally. Check `computercraft-server.toml`.
- **Private IP ranges are denied by default** — loopback, 10.x, 192.168.x, and
  Tailscale's 100.64.0.0/10 CGNAT range. Needs an `[[http.rules]]` allow entry
  above the deny rules. Singleplayer uses the same config (client-side copy).
- Prefer the raw 100.x IP over MagicDNS — the JVM may not use the system
  resolver.
- Websockets support binary frames; no base64 needed.
- Ender modems in use, so rednet range is not a constraint.

---

## Layout

```
main             → wires things together
internal/server  → HTTP shell: routing, auth, decoding
internal/turtles → domain: registry, turtle state, commands
```

**`server` imports `turtles`. `turtles` must never import `server`.** Go
rejects import cycles at compile time. HTTP is glue, turtles are the domain,
and the domain must not know the glue exists — so **do not pass
`*http.Request` into `turtles`**. The handler decodes HTTP into plain values
and calls `turtles.Register(id, name)`.

`internal/` is compiler-enforced, not a convention.

---

## Data model

```go
package turtles

type Turtle struct {
    // declare largest → smallest for padding
    Name    string
    Storage [16]Item // inline, ~640 bytes — arrays are values in Go
    Loc     Loc
    Fuel    int
    Queue   []Command
    Task    TaskID
    Parent  uint16 // adjacency list; children are derived by scan
    ID      uint16
    Rot     Rot // uint8
}

type Item struct{}
type Loc struct{}
type Command struct{}
type TaskID struct{}
type Rot struct{}
```

**Export fields that cross the wire** — `encoding/json` and `gob` use
reflection and cannot read unexported fields, which is why the current struct
would marshal to `{}`.

**Move `Turtle`, `Loc`, `Rot` out of `main` into `turtles`** before the server
needs them.

Registry is an unexported `map[uint16]*Turtle` inside `turtles`, with exported
functions as the only doors in. Rationale in DESIGN.md §1.

---

## Roadmap

### v0.1 — walking skeleton

One turtle, hardcoded where necessary, end-to-end.

- [ ] Registry: map, pointers, exported accessors
- [ ] `RegisterTurtle`: decode, validate, call `turtles.Register`, respond
- [ ] `httptest` tests on handlers; table-driven tests on the decode function
- [ ] CC-side: register, move, report
- [ ] CC config: `[[http.rules]]` allow entry for the backend host

**HTTP bugs to fix along the way:**

- [ ] Auth failure returns **200 with empty body** — a bare `return` writes
  nothing and Go defaults to 200. Use
  `http.Error(w, "unauthorized", http.StatusUnauthorized)` then return.
- [ ] Missing `return` after the 405 in `RegisterPassword` — `http.Error` does
  not stop execution
- [ ] `/register` sets the password with **no authentication** — anyone
  reachable owns the server
- [ ] `bcrypt.MinCost` (cost 4) — raise before this is anything but a toy
- [ ] `/register` is a state-changing GET — make it POST
- [ ] `check()` argument order `(r, w, …)` → `(w, r, …)` to match `net/http`
- [ ] `Setup` returns the package-level `port` set as a side effect of
  `validatePort`. Make the validator pure —
  `strconv.ParseUint(s, 10, 16)` does the range check for free.

### v0.2 — one turtle doing something

- [ ] Opcode enum + `String()`, fixed-width encoding, dispatch table in Lua
- [ ] Dead reckoning, drift reporting, heartbeat + watchdog
- [ ] Scan upload, naive format
- [ ] Chunk map with uniform flag, dense array fallback
- [ ] `GetBlock` / `SetBlock` interface — **design this now, keep the
  implementation stupid.** Palette, bit-packing, RLE, zstd, Morton, and VDB
  levels all slot in behind it without touching callers.

### v0.3 — measurement

- [ ] Dump real scans to disk; encode the same data several ways; print sizes
  (row-major RLE, Y-major RLE, Morton RLE, raw palette, zstd, combinations)
- [ ] Compute **Shannon entropy** as a lower bound — there is no formula for
  "optimal encoding for this dataset"; entropy is the measuring stick and
  testing is the method
- [ ] Time the AE2 full dump and print the string length before designing any
  byte format

### v0.4+ — conditional on the goal question resolving

Pathfinding, exploration, task system, hub, viewer.

**Then park.** Next project: WoT Trainer.

---

## Blocking questions

- **What do the turtles actually do?** Everything else sits on this.
- Max scan radius — 8 or 16?

Design-level open questions, and things to verify before building on them, are
in DESIGN.md §11.