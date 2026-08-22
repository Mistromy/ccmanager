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

-- facing derived from a position delta. minecraft: north is -z, east is +x.
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
            -- while rot is unknown. after that, turns keep it up to date.
            local fixing = rot < 0 and (cmd == "forward" or cmd == "back")
            local px, _, pz
            if fixing then
                px, _, pz = gps.locate()
                fixing = px ~= nil
            end

            for step = 1, count do
                local ok, reason = move()
                if not ok then
                    -- how many completed, not just that it failed. the server
                    -- tracks position by applying successful moves, so without
                    -- the count it cannot know where the turtle stopped.
                    modem.transmit(replyChannel, id,
                        id .. " " .. cmd .. " " .. (step - 1) .. " " .. tostring(reason))
                    break
                end

                if turns[cmd] and rot >= 0 then
                    rot = (rot + turns[cmd]) % 4
                end

                if fixing then
                    local nx, _, nz = gps.locate()
                    if nx then
                        rot = facingFrom(nx - px, nz - pz, cmd == "back")
                        print("facing " .. rot)
                    end
                    fixing = false
                end
            end
        elseif cmd == "scan" then
            local radius = math.floor(tonumber(words[2]) or 8)
            radius = math.min(math.max(radius, 1), maxRadius)

            -- gps at scan time, not boot time, so the position and the blocks
            -- describe the same instant.
            local sx, sy, sz
            if scanner then
                sx, sy, sz = gps.locate()
            end

            local started = os.epoch("utc")
            local raw, why
            if not scanner then
                why = "no scanner"
            elseif not sx then
                why = "no gps fix"
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
                    pos = { sx, sy, sz },
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
