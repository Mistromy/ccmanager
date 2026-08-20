local host = "100.106.228.50:6767"
local password = "myPassword"
local brokerChannel = 0

local volume = 3   -- CC:Tweaked playNote volume range is 0-3
local growth = 1.5 -- backoff multiplier once the flat stages are done
local maxDelay = 30

-- Self-update. Fetch this script from the server; if it differs and compiles,
-- write it and run the new copy instead of continuing. The fresh copy repeats
-- the fetch, finds itself identical, and falls through - so this cannot loop.
-- If the server is unreachable, carry on with the existing code.
local res = http.get({
    url = "http://" .. host .. "/source/broker/startup.lua",
    headers = { Authorization = password },
    timeout = 5,
})
term.clear()
term.setCursorPos(1,1)
print("checking if up to date... ")
if res then
    local source = res.readAll()
    res.close()

    local current = ""
    if fs.exists("startup.lua") then
        local f = fs.open("startup.lua", "r")
        current = f.readAll()
        f.close()
    end

    if source ~= current then
        local new = load(source, "startup.lua")
        if new then
            local f = fs.open("startup.lua", "w")
            f.write(source)
            f.close()
            term.clear()
            term.setCursorPos(1,1)
            print("updated, restarting...")
            return new()
        end
        print("update does not compile, keeping current")
    end
end

local modem = peripheral.find("modem")
if not modem then
    print("no modem")
    return
end
modem.open(brokerChannel)

local speaker = peripheral.find("speaker")

local function chime(up)
    if not speaker then
        return
    end
    local first, second = 1, 4
    if not up then
        first, second = 4, 1
    end
    speaker.playNote("bell", volume, first)
    sleep(0.15)
    speaker.playNote("bell", volume, second)
end

-- delay returns the backoff for a given attempt number.
-- 20 attempts at 0.5s, then 20 at 1s, then growing, capped at maxDelay.
local function delay(attempt)
    if attempt <= 20 then
        return 0.5
    elseif attempt <= 40 then
        return 1
    end
    return math.min(growth ^ (attempt - 40), maxDelay)
end

local function connect()
    local attempt = 0
    while true do
        local ws, err = http.websocket("ws://" .. host .. "/ws", { Authorization = password })
        if ws then
            return ws
        end
        attempt = attempt + 1
        local d = delay(attempt)
        print("connect failed (" .. tostring(err) .. "), retrying in " .. d .. "s")
        sleep(d)
    end
end

while true do
    print("connecting to server...")
    local ws = connect()
    ws.send("broker " .. os.getComputerID() .. " online")
    chime(true)
    print("connected, listening on channel " .. brokerChannel)

    local connected = true
    while connected do
        local event, a, b, c, message = os.pullEvent()

        if event == "modem_message" then
            -- a = side, b = channel, c = replyChannel, message = payload.
            -- non-strings are not ours; forwarding one would error and be
            -- misread as a dropped socket.
            if type(message) == "string" then
                if not pcall(ws.send, message) then
                    connected = false
                end
            end
        elseif event == "websocket_message" then
            -- a = url, b = message. first word is the target turtle id.
            local words = {}
            for w in string.gmatch(tostring(b), "%S+") do
                words[#words + 1] = w
            end

            -- a modem never hears its own transmission, so anything addressed
            -- to the broker has to be handled here rather than sent.
            local id = tonumber(words[1])
            if id == brokerChannel then
                if words[2] == "reboot" then
                    pcall(ws.send, brokerChannel .. " rebooting")
                    os.reboot()
                else
                    print("unknown command: " .. tostring(words[2]))
                end
            elseif id then
                modem.transmit(id, brokerChannel, table.concat(words, " ", 2))
            else
                print("server: " .. tostring(b))
            end
        elseif event == "websocket_closed" then
            connected = false
        end
    end

    chime(false)
    print("disconnected")
end
