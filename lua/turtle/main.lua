local brokerChannel = 0
local allChannel = 65535
local maxRepeat = 16

local moves = {
    forward = turtle.forward,
    back = turtle.back,
    up = turtle.up,
    down = turtle.down,
    left = turtle.turnLeft,
    right = turtle.turnRight,
}

local id = os.getComputerID()
local name = os.getComputerLabel() or ("turtle-" .. id)

local modem = peripheral.find("modem")
if not modem then
    print("no modem")
    return
end

local x, y, z = gps.locate()
if not x then
    print("no gps fix")
    return
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

    -- allChannel is also rednet's broadcast channel, and rednet payloads are
    -- tables. Anything that is not a string is not ours.
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
            end
        elseif cmd == "reboot" then
            modem.transmit(replyChannel, id, id .. " rebooting")
            os.reboot()
        else
            print("unknown command: " .. tostring(cmd))
        end
    end
end
