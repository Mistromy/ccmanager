# Lua notes

Known issues and things to add later. Kept short on purpose.

## Known issues

- **Uplink is dropped while the broker is disconnected.** The relay coroutine
  is not running between connections, so anything a turtle transmits in that
  window is discarded rather than queued. Pongs survive because the heartbeat
  coroutine keeps its own state.
- **`0 reboot` closes as 1006, and that is now accepted.** CC's `ws.close()`
  does not complete a closing handshake, so no delay or close call helps. The
  broker just reboots. Treat a 1006 as an ordinary disconnect server-side.
- **Offline detection takes `staleAfter` plus up to one `heartbeat`.** At the
  current 1500/0.5 that is 1.5-2s. Tighter values detect faster but a single
  dropped modem message starts flickering a healthy turtle to down.
- **Channel constants are duplicated.** `brokerChannel = 0` lives in both
  scripts and `allChannel = 65535` in the turtle. Nothing enforces agreement.
- **A broadcast reaches turtles only.** The broker does not hear its own
  transmissions, so rebooting everything is `65535 reboot` then `0 reboot`.
- **`allChannel` 65535 is also rednet's broadcast channel.** Turtles listening
  there receive every rednet broadcast in range. Rednet wraps payloads in a
  table, so a `type(message) == "string"` guard filters all of it — but a raw
  modem sending plain strings on 65535 would still be parsed as a command.
  Moving the fleet broadcast to an unused channel is the real fix; the wire
  address can stay `65535` with the broker mapping it.
- **A turtle that is off or out of range silently misses commands.** No ack, no
  correlation ID, no retry.
- **The turtle blocks in its event loop.** It can do nothing else until this
  becomes a `parallel` call.
- **A repeat makes the turtle deaf for its duration.** `turtle.forward()` has a
  cooldown, so `forward 16` is several seconds during which no command is
  processed — including `reboot`. That is the real cost of raising `maxRepeat`,
  not the fuel.
- **Out-of-range counts are clamped silently.** `forward 40` moves 16 and says
  nothing about the difference.
- **Startup scripts are installed by hand.** Neither script updates its own
  `startup.lua` from a disk any more; the broker updates itself from the server
  instead, and the turtle's bootstrap is small enough to rarely change.
- **`timeout` in the `http.get` options table needs a recent CC:Tweaked.** If it
  errors on an older build, drop the field and the server config's `http.timeout`
  applies instead.

- **Websocket messages are capped at `http.max_websocket_message`**, default
  131072 bytes. Measured: r3 = 126169 through, r4 = 269356 rejected. The palette
  format puts r8 well under; r16 will need the config raised.
- **`rot` is -1 until the turtle has moved forward or back once.** Turns are
  tracked from there. A scan taken before that first move cannot be rotated
  into world space.
- **An empty scan serialises `palette`/`blocks` as `{}`, not `[]`.**
  `textutils.serialiseJSON` cannot tell an empty array from an empty object.
- **A crash reboots the turtle after 5s.** Self-healing, since the reboot
  re-fetches `main.lua` — but a bug that crashes before the modem opens gives a
  turtle that reboots forever and never accepts a command. The escape is ctrl+t
  or pulling the disk.
- **The broker has no equivalent crash handler.** It runs `main.lua` through
  `shell.run`, so a runtime error leaves it sitting at a prompt.

- **Heartbeat stops while the broker is reconnecting.** The backoff sleeps, so
  no ping goes out and the monitor keeps showing the last completed round.
- **`seen` only grows.** A turtle removed from the server's registry keeps
  showing as "down" on the monitor until the broker reboots.
- **The registry reply must be prefixed `list`.** The broker matches the verb,
  like every other message. A bare `[5 32 6]` is ignored.
- **Heartbeat at 1s is 1 pong per turtle per second through one broker.** Fine
  now; at 100+ turtles this is the first thing to batch or slow down.
- **`update` after a move sends a partial turtle.** Only `ID` and `Loc`. This
  needs the server to merge rather than replace, or `Name`, `Fuel` and
  `Storage` get zeroed.

## Glasses client (lua/client)

Mod-level quirks and the position problem are written up in
[PLETHORA.md](PLETHORA.md). Only project-specific items belong here.

- **Nothing here may depend on the server.** The glasses carry the boost
  hotkey. `startup.lua` keeps a local `main.lua` and runs it whether or not the
  server answers; missing glasses disable rendering but not input; the link
  loop retries forever and never throws.
- **canvas3d frames are world anchored at creation.** The renderer draws only
  when the data changes, never on a timer. Walking around inside one chunk
  redraws nothing.
- **canvas3d has no line primitive.** Every edge is a thin box, so a chunk
  outline costs 12 boxes.
- **The canvas anchors at the player's position the instant `create()` runs.**
  The gps fix must be taken inside `redraw`, not reused from the position loop,
  or geometry is displaced by however far the player moved in between.
- **`gps.locate()` returns block-quantised coordinates ending in .5**, which is
  the gps hosts' own block-centre offset surviving trilateration. Mixing it
  with the canvas's exact anchor is what made geometry jump a whole block when
  stepping onto a path block.
- **Modem distance is block quantised too - measured, not assumed.** Every
  distance `gpsprobe.lua` returned reconstructs exactly from the block-centre
  position `gps.locate()` reports, e.g. host 288,32,-417 from 269.5,32.5,-436.5
  gives sqrt(722.75) = 26.884010. The six decimals are irrational roots, not
  sub-block information, so a custom trilateration would gain nothing.
- **Open question: is the player's exact position reachable at all?**
  `getMetaOwner` is the last candidate, since plethora's entity metadata is a
  separate path from CC's modem positioning. If it is also quantised, world
  anchored rendering is not viable here and only player-relative overlays are.
- **Groups outlive the script.** Without `canvas.clear()` at startup, a restart
  inherits every group the previous run drew.
- **Colours look RGBA** (`0xRRGGBBAA`), matching the plethora docs and not
  `head.lua`'s constants. Alpha on thin edge boxes reads close to opaque
  regardless.
- **`/client` is not routed yet.** `clientWs` exists but `Start` never
  registers it, so the link loop will retry forever until it is.
- **Position comes from `gps.locate()` at 2 Hz**, so geometry snaps to block
  positions rather than gliding. Smooth tracking needs a sub-block source.
- **Two server messages are assumed and not yet implemented**: `getChunk x y z`
  replying `chunk {origin, palette, blocks}`, and `getTurtles` replying
  `turtles {Turtles:[{ID, Name, Loc}]}`.
- **`showBlocks` is off by default** and capped at `maxBlocks`. A full 16^3
  chunk is up to 4096 boxes, which is very unlikely to render at 2 Hz.

## Later

- Envelope on the uplink so the server can tell a register from an update from
  a failure report. Currently all three arrive as bare strings.
- Queue uplink messages while disconnected instead of dropping them.
- A server command that triggers a broker update, rather than only on boot.
- Broker wakes the fleet on server reconnect.
- `face n|e|s|w` wrapping the rotations, once the turtle tracks its heading.
- Chunkloader control, if the fleet should toggle it at runtime rather than
  having it always on.
- Dig, place, suck, drop, inspect. These take operands, so the single-word
  command format stops being enough at that point.
