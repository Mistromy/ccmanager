# Plethora overlay glasses — reference

Everything learned the hard way about rendering in-world geometry from a neural
interface. Written because almost none of this is in the docs, and several
plausible-looking assumptions are wrong in ways that produce *nearly* correct
output — which is worse than an error.

Current consumer: [client/main.lua](client/main.lua).

---

## 1. The two canvases

```lua
local modules = peripheral.find("neuralInterface")
local hud = modules.canvas()    -- 2D, screen space
local world = modules.canvas3d() -- 3D, world space
```

They are **different APIs with different primitives**, and the docs' examples
are mostly about the 2D one. Do not assume a method exists on both.

- **2D has `addLine`. 3D does not.** Every edge in 3D is a thin box. A wireframe
  cube costs 12 boxes.
- 3D confirmed present in this pack: `addBox`, `addFrame`, `addItem`, and on a
  frame, `addText`.

## 2. Anchoring — the thing that breaks everything

**`canvas3d.create()` anchors the group at the player's position at the moment
it is called, and the group stays there in the world afterwards.**

Two consequences, and they pull in opposite directions:

- You **do not** need to redraw as the player moves. Geometry created once stays
  world-correct. Redraw only when the data changes.
- You **do** need the player's exact position *at the instant of the call*, to
  convert world coordinates into the group's local space:
  `local = world − player`.

Everything that has gone wrong here has been an inaccurate `player` term.

### There is a one-block vertical offset, and it is not eye height

Geometry lands exactly **one block** high. Correct by adding 1 to the computed
player Y. Verified against a freecam, which is the only reliable way to judge
this — from first person, a grid one block out looks plausible.

It is tempting to read this as eye height and use 1.62. **That is wrong and
visibly overshoots.** A whole-block value means GPS and `withinBlock` disagree
about which block the player occupies, not that the anchor sits at the eye.

## 3. Getting the player's exact position

This is the hard part. Three candidate sources; only the combination works.

| Source | Gives | Verdict |
|---|---|---|
| `gps.locate()` | block **centre** — always ends in `.5` | integer part only |
| `meta.x/y/z` from `getMetaOwner()` | always `0 0 0` | interface-relative, useless |
| `meta.withinBlock` | `{x, y, z}` in 0..1 | **the fraction** |

```lua
local gx, gy, gz = gps.locate()
local w = modules.getMetaOwner().withinBlock
local x = (gx - 0.5) + w.x   -- corner, then fraction
```

**Sample both in the same place, right before `create()`.** Reusing values from
a polling loop is the single nastiest bug here: a stale GPS integer combined
with a fresh fraction disagrees by a **whole block** at a boundary, so the
geometry jumps a metre for a millimetre of movement.

*Tell-tale:* standing on a path block (1/16 lower) displaces everything by a
full block. GPS floors to the block below, the fraction says 0.94, and if the
two came from different moments they do not compose.

### Modem distance does not help — measured, not assumed

The obvious escape is to skip `gps.locate()` and trilaterate yourself from the
`distance` field of `modem_message`, which prints six decimal places. **Those
decimals are irrational square roots, not precision.** The distance is computed
from the same block-centre position GPS already reports.

Worked example from real data — GPS reported `269.5, 32.5, −436.5`, host at
`288, 32, −417`:

```
dx = −18.5 → 342.25
dy =   0.5 →   0.25
dz = −19.5 → 380.25
             722.75  →  sqrt = 26.884010   ← the reported distance, exactly
```

A custom trilateration would reconstruct the same quantised value. There is no
sub-block information in the radio layer to recover.

### Residual error while moving

`gps.locate()` costs a tick or two — a broadcast plus replies from four hosts —
and the player keeps moving during it. At sprint speed that is a few tenths of a
block of anchor error, unavoidable without dead reckoning.

**Do not redraw repeatedly to compensate.** Each redraw re-anchors with a fresh
error, so a single small static offset becomes a new offset several times a
second — which reads as violent jitter and is far worse than being slightly out.

Practical answer: draw once, and **redraw again on the moving→stopped
transition**, using `meta.motionX/Y/Z`. That is the one moment an accurate
anchor is actually available, and it is also when you are looking at it.

## 4. Method names vary between builds

Depth testing has at least four spellings in the wild, and some builds want
`obj:method()` while others want `obj.method()`. Probe rather than assume:

```lua
local function try(obj, name, ...)
    local fn = type(obj) == "table" and obj[name]
    if type(fn) ~= "function" then return false end
    if pcall(fn, obj, ...) then return true end  -- method form
    return pcall(fn, ...)                        -- plain form
end
```

`setDepthTested(false)` makes an object draw through terrain. Set it on the
group *and* each object; inheritance is not reliable.

⚠️ Because `try` swallows failures, a wrong method name is **silent**. If
`remove` is misspelled you leak a group on every redraw and only notice when
the world fills with ghosts.

## 5. Lifecycle

**Groups outlive the script.** Terminating and restarting inherits everything
the previous run drew. Call `canvas.clear()` at startup or ghosts accumulate
across every edit-and-rerun cycle.

**Swap, then remove.** Create the replacement, assign it, *then* drop the old
one. Removing first leaves a frame with nothing on screen and reads as a flicker.

```lua
local fresh = canvas.create()
local previous = root
root = fresh
-- ... draw into root ...
try(previous, "clear")
try(previous, "remove")
```

## 6. Colours

`0xRRGGBBAA` — RGBA, alpha last. `0xFF0000FF` is opaque red.

Alpha on thin geometry reads as near-opaque regardless, because there is
almost no surface for the blend to work across. A 0.05-thick edge box at 50%
alpha looks solid.

## 7. `getDocs()` returns a table

Not a string. `tostring()` on it gives `table: 3f40943a`, which is how an entire
probe run can produce nothing. Iterate it — keys are method names, values are
doc strings.

## 8. Owner metadata keys

From `getMetaOwner()`, observed in this pack:

```
absorption, armor, deltaPosX, deltaPosY, deltaPosZ, displayName, food, health,
heldItem, heldItemSlot, id, isAirborne, isAlive, isBurning, isChild, isDead,
isElytraFlying, isInWater, isOnLadder, isRiding, isSleeping, isSneaking,
isSprinting, isWet, key, maxHealth, motionX, motionY, motionZ, name, pitch,
potionEffects, trinkets, withinBlock, x, y, z, yaw
```

Useful ones: `withinBlock` (position fraction), `motionX/Y/Z` (velocity),
`yaw`/`pitch` (facing, needed by `modules.launch`), `isSprinting`/`isAirborne`
(cheap movement state), `deltaPos*` (movement since last sample).

## 9. Performance

Objects are retained, not immediate-mode — cost is in *creating* them, not in
frames where nothing changes. A static scene is free.

A 16³ chunk of blocks is up to 4096 boxes and is not realistic to rebuild at
any interactive rate. Cap it, filter server-side to interesting blocks, or
render scan *bounds* rather than contents.

## 10. Things that do not exist

- No world-coordinate mode. Everything is player-relative at creation.
- No line primitive in 3D.
- No sub-block position from GPS or radio.
- No way to keep a group anchored to a moving entity; you re-create it.
