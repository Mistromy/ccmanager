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
-- Residual hand tune. Measured as zero: gps corner plus withinBlock already
-- matches whatever the canvas anchors to, so do not "correct" for eye height.
local offset = { x = 0, y = 0, z = 0 }

local colBound = 0x33FF3380
local colVertical = 0x33FF3340
local colTurtle = 0xFF8800AA
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
-- drew. clear the whole canvas before drawing anything.
if canvas then
    try(canvas, "clear")
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
        local loc = t.Loc
        if loc and loc.X then
            local x, y, z = loc.X - px, loc.Y - py, loc.Z - pz
            box(x, y, z, 1, 1, 1, colTurtle)

            if root.addFrame then
                local label = root.addFrame({ x + 0.5, y + 1.4, z + 0.5 })
                seeThrough(label)
                label.addText({ 0, 0 }, t.Name or ("turtle " .. id), colLabel)
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
        if payload and payload.Turtles then
            turtles = {}
            for _, t in ipairs(payload.Turtles) do
                turtles[t.ID] = t
            end
            dirty = true
        end
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
        end
        sleep(0.2)
    end
end

local loops = { inputLoop, metaLoop, linkLoop }
if canvas then
    loops[#loops + 1] = positionLoop
    loops[#loops + 1] = renderLoop
end

parallel.waitForAny(table.unpack(loops))
