-- Does the modem distance carry sub-block precision?
--
-- Run it standing still and note the distances. Then step onto a path block
-- (1/16 lower) or shuffle half a block and run it again.
--   distances change in the third decimal -> precision is there, trilateration
--   distances identical or land on .0 / .5 -> it is block quantised, drop it

local CHANNEL = 65534 -- gps.CHANNEL_GPS

local modem = peripheral.find("modem")
if not modem then
    print("no modem")
    return
end

local wasOpen = modem.isOpen(CHANNEL)
if not wasOpen then
    modem.open(CHANNEL)
end

modem.transmit(CHANNEL, CHANNEL, "PING")

local deadline = os.startTimer(1)
local hosts = 0

while true do
    local e = { os.pullEvent() }

    if e[1] == "timer" and e[2] == deadline then
        break
    elseif e[1] == "modem_message" and e[3] == CHANNEL then
        -- side, channel, replyChannel, message, distance
        local msg, dist = e[5], e[6]
        if type(msg) == "table" and #msg == 3 and dist then
            hosts = hosts + 1
            print(("host %d %d %d   dist %.6f"):format(msg[1], msg[2], msg[3], dist))
        end
    end
end

if not wasOpen then
    modem.close(CHANNEL)
end

print(hosts .. " hosts replied")

local x, y, z = gps.locate()
print(("gps.locate: %s %s %s"):format(tostring(x), tostring(y), tostring(z)))
