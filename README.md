# ComputerCraft Turtle Swarm Manager

A distributed fleet manager for ComputerCraft turtles. Go backend, Lua agents,
Rust viewer. Written by hand, no AI-generated code.

Design rationale, rejected alternatives, and subsystem detail live in
[DESIGN.md](DESIGN.md).

---

## Goal

**Software-defined logistics for a modded base.**

Pipes and belts are static infrastructure — every layout change is a rebuild,
and every route needs physical construction and manual sorting. Turtles are
software-defined: a route is a config change, not a construction project.
Throughput is lower per unit but scales with turtle count, and nothing has to
be built.

Define a task in the UI — input blocks, output blocks, rules — and the fleet
pathfinds and hauls. Machines that fill up or run dry generate tasks on their
own. Throughput measured over time gives storage estimates, so the system knows
when a buffer is about to matter rather than reacting once it already does.

Longer term, the same task layer drives mixed agent types: turtles, entities
via the neural interface, Create contraptions, physics vehicles. The
coordination layer doesn't care what's executing, only what it's capable of.

**The unique property turtles have** is being mobile sensors reporting to a
central world model. Nothing else in a modded world does that. So the world
map, staleness tracking, exploration, and discrepancy detection are the
foundation, and logistics is the thing they enable.

---

## Status

Server runs. Port validation with a table-driven test suite. Password auth with
a shared middleware-style `check`. Turtle registration works end to end — HTTP
POST, JSON decode, validation and duplicate detection in the domain package,
error-to-status mapping in the handler, registry as a `map[uint16]*Turtle`.

Nothing talks to a real turtle yet. No persistence. No commands.

---

## Constraints

### Scan
- **Radius 8 is free. Radius up to 16 costs fuel.** That splits scanning in
  two: cheap high-frequency passive scans while traveling, and deliberate
  paid scans where the information gain justifies it.
- Cube shaped. Radius *r* means side `2r+1` — r=8 is 17³ = 4,913 blocks,
  r=16 is 33³ = 35,937.
- Two scanners available: Plethora and Unlimited Peripheral Works. Different
  return shapes; normalize at the Lua layer.

### Turtle inventory and peripherals
- 16 inventory slots
- Two upgrade slots, one per side — one holds the main tool, the other holds
  the Peripheralium Hub
- Peripheralium Hub: base tier 3 peripherals, netherite tier 7
- Extra peripherals carried in inventory and swapped at runtime
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
parts/server        → entry point, wiring
internal/server   → HTTP shell: routing, auth, decoding, status mapping
internal/turtles  → domain: registry, turtle state, validation, commands
internal/utils    → shared helpers
lua/              → turtle-side agent
```

**`server` imports `turtles`. `turtles` must never import `server`.** Go
rejects import cycles at compile time. HTTP is glue, turtles are the domain,
and the domain must not know the glue exists — so **do not pass
`*http.Request` into `turtles`**. The handler decodes HTTP into plain values
and calls `turtles.Register(id, name)`.

Validation lives in the domain, not the handler, so it applies no matter who
calls — HTTP, websocket, CLI, or tests.

`internal/` is compiler-enforced, not a convention.

---

## Data model

```go
package turtles

type Turtle struct {
    // declare largest → smallest for padding
    Name    string
    Storage [16]item // inline, ~640 bytes — arrays are values in Go
    Loc     Loc
    Fuel    int
    Queue   []Command
    Task    TaskID
    Parent  uint16 // adjacency list; children are derived by scan
    ID      uint16
    Rot     Rot // uint8
}
```

Registry is an unexported `map[uint16]*Turtle` inside `turtles`, with exported
functions as the only doors in. Pointers rather than values because map values
aren't addressable, the struct is ~700 bytes, and multiple callers need to see
the same turtle. Rationale in DESIGN.md §1.

**Export fields that cross the wire** — `encoding/json` and `gob` use
reflection and cannot read unexported fields.

---

## Proposed versions

**v0.1 — walking skeleton.** One turtle, end to end. Registration over the
network from a real turtle in-game. CC config allow-rule for the backend host.
Persistence for config (port, password hash) and turtle state.

**v0.2 — one turtle doing something.** Opcode enum and fixed-width command
encoding, dispatch table in Lua, dead reckoning with drift reporting,
heartbeat and watchdog, scan upload in a naive format, chunk map with uniform
flag and dense array fallback. `GetBlock`/`SetBlock` interface designed now
with a deliberately stupid implementation behind it.

**v0.3 — measurement.** Dump real scans, encode the same data several ways,
compare sizes against Shannon entropy as a lower bound. Time the AE2 full dump
before designing any byte format. Benchmark Morton encoding methods inside the
actual pathfinder rather than trusting published numbers.

**v0.4 — pathfinding.** A* with orientation in the node, cost model measured
from real in-game timings, incremental replanning, hierarchical pathing for
long distances.

**v0.5 — tasks.** The actual goal. Task queue, capability matching,
inventory-aware task generation from machine buffers, multi-turtle
coordination.

**v0.6+ — the rest.** Exploration and rescan scheduling, the broker, the Rust
viewer, AE2 integration, heatmaps, public read-only stats.

**Later, unscheduled.** Entity control via the neural interface. Mixed agent
types. Physics vehicles if the pack ever migrates.

---

## Open questions

Design-level questions and things to verify before building on them are in
DESIGN.md §11.