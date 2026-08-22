# Lua notes

Known issues and things to add later. Kept short on purpose.

## Known issues

- **Uplink other than pongs is still dropped while reconnecting.** `wait()` now
  services pongs during backoff, but anything else a turtle transmits in that
  window is discarded rather than queued.
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
- **The registry reply is recognised by shape, not by a verb.** The broker
  accepts `getAll ...`, `list ...`, or anything starting with `[`. Give the
  response a verb prefix server-side and this can drop to one exact match.
- **Heartbeat at 1s is 1 pong per turtle per second through one broker.** Fine
  now; at 100+ turtles this is the first thing to batch or slow down.
- **`update` after a move sends a partial turtle.** Only `ID` and `Loc`. This
  needs the server to merge rather than replace, or `Name`, `Fuel` and
  `Storage` get zeroed.

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
