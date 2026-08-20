local brokerChannel = 0
local allChannel = 65535

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

modem.transmit(brokerChannel, id, register)
modem.transmit(brokerChannel, id, update)

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

        if words[1] == "forward" then
            local ok, reason = turtle.forward()
            if not ok then
                modem.transmit(replyChannel, id, id .. " " .. reason)
            end
        elseif words[1] == "reboot" then
            modem.transmit(replyChannel, id, id .. " rebooting")
            os.reboot()
        else
            print("unknown command: " .. tostring(words[1]))
        end
    end
end
