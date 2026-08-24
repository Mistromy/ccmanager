local brokerChannel = 0
local allChannel = 65533 -- 65534 is GPS, 65535 is rednet broadcast. both are CC's.
local maxRepeat = 16
local maxRadius = 16

local moves = {
    forward = turtle.forward,
    back = turtle.back,
    up = turtle.up,
    down = turtle.down,
    left = turtle.turnLeft,
    right = turtle.turnRight,
}

local turns = { left = -1, right = 1 }

-- unit step per facing. minecraft: north is -z, east is +x.
local heading = {
    [0] = { x = 0, z = -1 },
    [1] = { x = 1, z = 0 },
    [2] = { x = 0, z = 1 },
    [3] = { x = -1, z = 0 },
}

local id = os.getComputerID()
local name = os.getComputerLabel() or ("turtle-" .. id)
local rot = -1 -- unknown until a forward/back resolves it

local modem = peripheral.find("modem")
if not modem then
    print("no modem")
    return
end

local scanner = peripheral.find("universal_scanner")

local x, y, z = gps.locate()
if not x then
    print("no gps fix")
    return
end

-- facing derived from a position delta.
local function facingFrom(dx, dz, reversed)
    if reversed then
        dx, dz = -dx, -dz
    end
    if dz < 0 then return 0 end
    if dx > 0 then return 1 end
    if dz > 0 then return 2 end
    if dx < 0 then return 3 end
    return -1
end

local function state()
    return textutils.serialiseJSON({
        ID = id,
        Loc = { X = x, Y = y, Z = z, Rot = rot },
    })
end

local register = textutils.serialiseJSON({ ID = id, Name = name })
local update = textutils.serialiseJSON({
    ID = id,
    Name = name,
    Loc = {
        X = x,
        Y = y,
        Z = z
    },
    Fuel = turtle.getFuelLevel(),
})

modem.transmit(brokerChannel, id, "register " .. register)
modem.transmit(brokerChannel, id, "update " .. update)

modem.open(id)
modem.open(allChannel)
print("listening on channel " .. id)

-- ping lives on its own coroutine so it still answers while a command is
-- running. turtle actions yield internally, so parallel gets a slot mid-move.
local function pingLoop()
    while true do
        local _, _, _, replyChannel, message = os.pullEvent("modem_message")
        if message == "ping" then
            modem.transmit(replyChannel, id, "pong " .. id)
        end
    end
end

local function commandLoop()
while true do
    local _, _, _, replyChannel, message = os.pullEvent("modem_message")

    -- anything that is not a string is not ours.
    if type(message) == "string" then
        local words = {}
        for w in string.gmatch(message, "%S+") do
            words[#words + 1] = w
        end

        local cmd = words[1]
        local move = moves[cmd]

        if move then
            local count = math.floor(tonumber(words[2]) or 1)
            count = math.min(math.max(count, 1), maxRepeat)

            -- two-position facing fix: only on the first xz move, and only
            -- while rot is unknown. after that, turns keep it up to date and
            -- position is dead reckoned.
            local fixing = rot < 0 and (cmd == "forward" or cmd == "back")
            local px, pz
            if fixing then
                px, _, pz = gps.locate()
                fixing = px ~= nil
            end

            for step = 1, count do
                local ok, reason = move()
                if not ok then
                    -- an empty tank reports as obstruction, so say what it
                    -- actually is. turns cost no fuel and cannot hit this.
                    if turtle.getFuelLevel() == 0 then
                        reason = "out of fuel"
                    end
                    -- how many completed, not just that it failed. the server
                    -- tracks position by applying successful moves, so without
                    -- the count it cannot know where the turtle stopped.
                    modem.transmit(replyChannel, id,
                        id .. " " .. cmd .. " " .. (step - 1) .. " " .. tostring(reason))
                    break
                end

                if turns[cmd] then
                    if rot >= 0 then
                        rot = (rot + turns[cmd]) % 4
                    end
                elseif cmd == "up" then
                    y = y + 1
                elseif cmd == "down" then
                    y = y - 1
                elseif fixing then
                    -- the fix re-establishes position and facing together
                    local nx, ny, nz = gps.locate()
                    if nx then
                        rot = facingFrom(nx - px, nz - pz, cmd == "back")
                        x, y, z = nx, ny, nz
                    end
                    fixing = false
                elseif rot >= 0 then
                    local h = heading[rot]
                    local sign = (cmd == "back") and -1 or 1
                    x = x + h.x * sign
                    z = z + h.z * sign
                end
            end

            modem.transmit(replyChannel, id, "update " .. state())
        elseif cmd == "getrot" then
            modem.transmit(replyChannel, id, "update " .. state())
        elseif cmd == "gps" then
            -- authoritative fix, to correct dead reckoning drift. rot is not
            -- recoverable from a single locate, so it is left alone.
            local nx, ny, nz = gps.locate()
            if nx then
                x, y, z = nx, ny, nz
                modem.transmit(replyChannel, id, "update " .. state())
            else
                modem.transmit(replyChannel, id, id .. " gps 0 no fix")
            end
        elseif cmd == "ping" then
            -- answered by pingLoop; ignored here so it is not "unknown"
        elseif cmd == "refuel" then
            local n = math.floor(tonumber(words[2]) or 64)
            local ok, why = turtle.refuel(n)
            modem.transmit(replyChannel, id, "refuel " .. textutils.serialiseJSON({
                ID = id,
                OK = ok,
                Fuel = turtle.getFuelLevel(),
                Reason = (not ok) and tostring(why) or nil,
            }))
        elseif cmd == "scan" then
            local radius = math.floor(tonumber(words[2]) or 8)
            radius = math.min(math.max(radius, 1), maxRadius)

            local started = os.epoch("utc")
            local raw, why
            if not scanner then
                why = "no scanner"
            else
                raw, why = scanner.scan("block", radius)
            end

            if not raw then
                modem.transmit(replyChannel, id, id .. " scan 0 " .. tostring(why))
            else
                -- one pass: intern the name, append four numbers. the raw table
                -- is never serialised, which is where the cost was.
                local palette, seen, blocks = {}, {}, {}
                for i = 1, #raw do
                    local b = raw[i]
                    local index = seen[b.name]
                    if not index then
                        palette[#palette + 1] = b.name
                        index = #palette - 1 -- 0-based on the wire
                        seen[b.name] = index
                    end
                    blocks[#blocks + 1] = { b.x, b.y, b.z, index }
                end

                local payload = textutils.serialiseJSON({
                    id = id,
                    radius = radius,
                    pos = { x, y, z },
                    rot = rot,
                    palette = palette,
                    blocks = blocks,
                })

                print(("scan r%d: %d blocks, %d types, %d bytes, %dms")
                    :format(radius, #blocks, #palette, #payload,
                        os.epoch("utc") - started))

                modem.transmit(replyChannel, id, "scan " .. payload)
            end
        elseif cmd == "reboot" then
            modem.transmit(replyChannel, id, id .. " rebooting")
            os.reboot()
        else
            print("unknown command: " .. tostring(cmd))
        end
    end
end
end

parallel.waitForAny(commandLoop, pingLoop)
