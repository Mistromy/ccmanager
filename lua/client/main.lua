local host = "100.106.228.50:6767"
local password = "myPassword"

local chunkSize = 16
local showVertical = true -- floor and ceiling rings of the neighbouring chunks
local showBlocks = false  -- world blocks from the server. expensive, see maxBlocks
local maxBlocks = 512
local posEvery = 0.5      -- seconds between gps fixes
local launchPower = 3
local reconnectEvery = 5
local edgeThickness = 0.05
local throughWalls = true -- draw over terrain instead of being occluded by it
local debugKey = keys.rightAlt
local hudScale = 0.5 -- text is large by default at most gui scales
-- Residual hand tune, measured against a freecam. Exactly one block on y, not
-- the 1.62 an eye-height correction would imply - gps and withinBlock disagree
-- by a whole block about which block the player occupies.
local offset = { x = 0, y = 1, z = 0 }

local colBound = 0x33FF3380
local colVertical = 0x33FF3340
local colTurtleUp = 0x33FF33AA   -- server reports it as live
local colTurtleDown = 0xFF3333AA -- registered but silent, may not be there
local colLabel = 0xFFFFFFFF

--------------------------------------------------------------------------------
-- peripherals. only the interface is required: no glasses means no rendering,
-- no server means no turtles or blocks, and the hotkey works regardless.
--------------------------------------------------------------------------------

local modules = peripheral.find("neuralInterface")
if not modules then
    error("must have a neural interface", 0)
end

local canvas = modules.canvas3d and modules.canvas3d() or nil
local hud = modules.canvas and modules.canvas() or nil
if not canvas then
    print("no overlay glasses, rendering disabled")
end

-- plethora builds disagree on these names, and on whether they take self
local function try(obj, name, ...)
    if type(obj) ~= "table" then
        return false
    end
    local fn = obj[name]
    if type(fn) ~= "function" then
        return false
    end
    if pcall(fn, obj, ...) then
        return true
    end
    return pcall(fn, ...)
end

local function seeThrough(obj)
    if not throughWalls then
        return
    end
    try(obj, "setDepthTested", false)
    try(obj, "setDepthTest", false)
end

-- groups outlive the script, so a restart inherits everything the last run
-- drew. clear both surfaces before drawing anything.
if canvas then
    try(canvas, "clear")
end
if hud then
    try(hud, "clear")
end

--------------------------------------------------------------------------------
-- state
--------------------------------------------------------------------------------

local ws
local meta = {}
local origin       -- {x, y, z} gps block position, only to anchor meta
local chunk        -- {x, y, z} chunk the player is in
local chunkData
local turtles = {}
local dirty = true
local root         -- current canvas group

-- Go's json tags are lowercase on Turtle but Loc's fields carry none, so the
-- payload mixes casing. Take whichever spelling is present and normalise once,
-- so the draw code never has to care which the server chose.
local function pick(t, ...)
    if type(t) ~= "table" then
        return nil
    end
    for _, k in ipairs({ ... }) do
        if t[k] ~= nil then
            return t[k]
        end
    end
    return nil
end

-- one turtle into the normalised record. key is the map key when the payload
-- was an object rather than an array, since a marshalled map[uint16]*Turtle
-- carries the id only as the key.
local function addTurtle(out, t, key)
    if type(t) ~= "table" then
        return
    end
    local id = tonumber(pick(t, "ID", "id")) or tonumber(key)
    if not id then
        return
    end

    -- merge rather than replace, so a single-turtle push carrying only
    -- position does not blank the name and fuel it did not mention
    local r = out[id] or { id = id, up = true }
    local name = pick(t, "Name", "name")
    local fuel = tonumber(pick(t, "Fuel", "fuel"))
    local status = pick(t, "Status", "status", "Alive", "alive")
    local loc = pick(t, "Loc", "loc")

    if name ~= nil and name ~= "" then
        r.name = name
    end
    if fuel ~= nil then
        r.fuel = fuel
    end
    if status ~= nil then
        r.up = status ~= false
    end
    if loc then
        r.x = tonumber(pick(loc, "X", "x")) or r.x
        r.y = tonumber(pick(loc, "Y", "y")) or r.y
        r.z = tonumber(pick(loc, "Z", "z")) or r.z
    end

    out[id] = r
end

-- accepts an array, an object wrapping an array, or an object keyed by id
local function readTurtles(payload)
    local out = {}
    if type(payload) ~= "table" then
        return out
    end

    local list = payload
    if payload[1] == nil then
        list = pick(payload, "Turtles", "turtles")
    end

    if list and list[1] ~= nil then
        for _, t in ipairs(list) do
            addTurtle(out, t, nil)
        end
    else
        -- keyed object: the key is the id
        for k, v in pairs(list or payload) do
            addTurtle(out, v, k)
        end
    end
    return out
end

-- The canvas anchors at the player's EXACT position when create() runs, so the
-- offsets must come from that same exact position.
--
-- Neither source is enough alone. gps reports the centre of the block, so it
-- jumps a whole block the moment the floor changes - stepping onto a path block
-- does exactly that. meta.x/y/z are interface-relative and always zero. But
-- meta.withinBlock carries the 0-1 fraction, so corner plus fraction is exact.
--
-- Both are sampled here rather than reused from the loops, so they describe the
-- same moment. A stale pair disagrees by a whole block at a boundary.
local function playerPos()
    local gx, gy, gz = gps.locate()
    if not gx then
        return nil
    end

    local m = modules.getMetaOwner() or {}
    local w = m.withinBlock or {}
    local fx = tonumber(w.x) or 0.5
    local fy = tonumber(w.y) or 0.5
    local fz = tonumber(w.z) or 0.5

    return (gx - 0.5) + fx + offset.x,
        (gy - 0.5) + fy + offset.y,
        (gz - 0.5) + fz + offset.z
end

--------------------------------------------------------------------------------
-- rendering. the group is world anchored once created, so this only runs when
-- the data changes, never on a timer.
--------------------------------------------------------------------------------

local function box(x, y, z, sx, sy, sz, colour)
    local b = root.addBox(x, y, z, sx, sy, sz, colour)
    seeThrough(b)
    return b
end

-- canvas3d has no line primitive, so every edge is a thin box
local function ring(ox, oy, oz, s, colour)
    local t = edgeThickness
    box(ox, oy - t / 2, oz - t / 2, s, t, t, colour)
    box(ox, oy - t / 2, oz + s - t / 2, s, t, t, colour)
    box(ox - t / 2, oy - t / 2, oz, t, t, s, colour)
    box(ox + s - t / 2, oy - t / 2, oz, t, t, s, colour)
end

local function drawChunk(px, py, pz)
    local s = chunkSize
    local ox = chunk.x * s - px
    local oy = chunk.y * s - py
    local oz = chunk.z * s - pz
    local t = edgeThickness

    ring(ox, oy, oz, s, colBound)
    ring(ox, oy + s, oz, s, colBound)
    box(ox - t / 2, oy, oz - t / 2, t, s, t, colBound)
    box(ox + s - t / 2, oy, oz - t / 2, t, s, t, colBound)
    box(ox - t / 2, oy, oz + s - t / 2, t, s, t, colBound)
    box(ox + s - t / 2, oy, oz + s - t / 2, t, s, t, colBound)

    if showVertical then
        ring(ox, oy - s, oz, s, colVertical)
        ring(ox, oy + s * 2, oz, s, colVertical)
    end
end

local function colourFor(name)
    local h = 0
    for i = 1, #name do
        h = (h * 31 + name:byte(i)) % 0xFFFFFF
    end
    return h * 256 + 0x60
end

local function drawBlocks(px, py, pz)
    if not chunkData or not chunkData.blocks then
        return
    end
    local o = chunkData.origin
        or { chunk.x * chunkSize, chunk.y * chunkSize, chunk.z * chunkSize }

    for i, b in ipairs(chunkData.blocks) do
        if i > maxBlocks then
            break
        end
        box(o[1] + b[1] - px, o[2] + b[2] - py, o[3] + b[3] - pz,
            1, 1, 1, colourFor(chunkData.palette[b[4] + 1] or "?"))
    end
end

local function drawTurtles(px, py, pz)
    for id, t in pairs(turtles) do
        if t.x then
            local x, y, z = t.x - px, t.y - py, t.z - pz
            box(x, y, z, 1, 1, 1, t.up and colTurtleUp or colTurtleDown)

            if root.addFrame then
                local text = t.name or ("turtle " .. id)
                if t.fuel then
                    text = text .. "  " .. t.fuel
                end
                local label = root.addFrame({ x + 0.5, y + 1.4, z + 0.5 })
                seeThrough(label)
                label.addText({ 0, 0 }, text, colLabel)
            end
        end
    end
end

local function redraw()
    if not canvas or not chunk then
        return
    end

    local px, py, pz = playerPos()
    if not px then
        return
    end

    local fresh = canvas.create()
    seeThrough(fresh)

    local previous = root
    root = fresh

    drawChunk(px, py, pz)
    if showBlocks then
        drawBlocks(px, py, pz)
    end
    drawTurtles(px, py, pz)

    -- swap then drop, so there is no frame with nothing on screen. both names
    -- are tried because builds disagree and a miss leaks a group every redraw.
    if previous then
        try(previous, "clear")
        try(previous, "remove")
    end
end

-- the flat hud is a separate, much cheaper surface than canvas3d, and it works
-- with no turtle data at all because the link state is worth showing on its own
local function drawHud()
    if not hud then
        return
    end
    hud.clear()

    local step = 10 * hudScale
    local function text(row, str, colour)
        local o = hud.addText({ 5, 5 + row * step }, str, colour)
        try(o, "setScale", hudScale)
        return o
    end

    local row = 0
    text(row, "fleet: " .. (ws and "connected" or "offline"),
        ws and 0x33FF33FF or 0xFF5555FF)

    local ids = {}
    for id in pairs(turtles) do
        ids[#ids + 1] = id
    end
    table.sort(ids)

    for _, id in ipairs(ids) do
        row = row + 1
        local t = turtles[id]
        local line = id .. "  " .. (t.name or "?")
        if t.fuel then
            line = line .. "  " .. t.fuel
        end
        text(row, line, t.up and 0xFFFFFFFF or 0xFF5555FF)
    end
end

--------------------------------------------------------------------------------
-- server link. entirely optional.
--------------------------------------------------------------------------------

local function send(text)
    if ws then
        pcall(ws.send, text)
    end
end

local function handle(text)
    local verb, rest = text:match("^(%S+)%s*(.*)$")

    if verb == "chunk" then
        chunkData = textutils.unserialiseJSON(rest)
        dirty = true
    elseif verb == "turtles" then
        local payload = textutils.unserialiseJSON(rest)
        if not payload then
            print("turtles: bad json, " .. #rest .. " bytes")
        else
            turtles = readTurtles(payload)
            local n = 0
            for _ in pairs(turtles) do
                n = n + 1
            end
            print("turtles: parsed " .. n)
            dirty = true
        end
    elseif verb == "update" then
        -- a single turtle, merged into what is already known
        local payload = textutils.unserialiseJSON(rest)
        if payload then
            addTurtle(turtles, payload, nil)
            dirty = true
        else
            print("update: bad json, " .. #rest .. " bytes")
        end
    elseif verb == "reboot" then
        -- startup.lua refetches main.lua, so a reboot is also an update
        print("rebooting")
        os.reboot()
    elseif verb == "list" then
        send("getTurtles")
    else
        print("server: " .. text)
    end
end

local function linkLoop()
    while true do
        local sock = http.websocket({
            url = "ws://" .. host .. "/client",
            headers = { Authorization = password },
            timeout = 3,
        })

        if not sock then
            sleep(reconnectEvery)
        else
            ws = sock
            dirty = true
            print("connected")
            send("getTurtles")

            while true do
                local e = { os.pullEvent() }
                if e[1] == "websocket_closed" then
                    break
                elseif e[1] == "websocket_message" then
                    handle(tostring(e[3]))
                end
            end

            pcall(ws.close)
            ws = nil
            turtles = {}
            chunkData = nil
            dirty = true
            print("disconnected")
        end
    end
end

--------------------------------------------------------------------------------
-- loops
--------------------------------------------------------------------------------

-- the one thing that must never stop working
local function inputLoop()
    while true do
        local _, key = os.pullEvent("key")
        if key == keys.leftAlt then
            modules.launch(meta.yaw, meta.pitch, launchPower)
        elseif key == debugKey then
            local gx, gy, gz = gps.locate()
            local w = meta.withinBlock or {}
            print(("gps    %s %s %s"):format(tostring(gx), tostring(gy), tostring(gz)))
            print(("within %s %s %s"):format(
                tostring(w.x), tostring(w.y), tostring(w.z)))
            local px, py, pz = playerPos()
            if px then
                print(("exact  %.4f %.4f %.4f"):format(px, py, pz))
            end
            if hud and hud.getSize then
                local w, h = hud.getSize()
                print(("hud    %s x %s"):format(tostring(w), tostring(h)))
            end
            local n = 0
            for _ in pairs(turtles) do
                n = n + 1
            end
            print("turtles " .. n)
        end
    end
end

local function metaLoop()
    while true do
        meta = modules.getMetaOwner() or {}
        sleep(0.1)
    end
end

-- only a change of chunk moves the grid, so nothing redraws while you walk
-- around inside one
local function positionLoop()
    while true do
        local x, y, z = gps.locate()
        if x then
            origin = { x = x, y = y, z = z }
            local c = {
                x = math.floor(x / chunkSize),
                y = math.floor(y / chunkSize),
                z = math.floor(z / chunkSize),
            }
            if not chunk or c.x ~= chunk.x or c.y ~= chunk.y or c.z ~= chunk.z then
                chunk = c
                chunkData = nil
                dirty = true
                if showBlocks then
                    send(("getChunk %d %d %d"):format(x, y, z))
                end
            end
        end
        sleep(posEvery)
    end
end

-- gps takes a tick or two to answer and the player keeps moving during it, so a
-- redraw taken mid-sprint anchors to where they were. Redrawing repeatedly does
-- not fix that, it just produces a fresh error every time - which reads as
-- jitter. Instead draw once, and redraw once more when the player stops, which
-- is the moment an accurate anchor is available.
local function moving()
    local mx = math.abs(tonumber(meta.motionX) or 0)
    local my = math.abs(tonumber(meta.motionY) or 0)
    local mz = math.abs(tonumber(meta.motionZ) or 0)
    return mx + my + mz > 0.01
end

local function renderLoop()
    local wasMoving = false
    while true do
        local now = moving()
        if wasMoving and not now then
            dirty = true -- settled, so re-anchor accurately
        end
        wasMoving = now

        if dirty then
            dirty = false
            redraw()
            drawHud()
        end
        sleep(0.2)
    end
end

-- renderLoop runs whenever either surface exists, since the hud is useful on
-- its own. positionLoop only matters for world geometry.
local loops = { inputLoop, metaLoop, linkLoop }
if canvas then
    loops[#loops + 1] = positionLoop
end
if canvas or hud then
    loops[#loops + 1] = renderLoop
end

parallel.waitForAny(table.unpack(loops))
