local host = "100.106.228.50:6767"
local password = "myPassword"
local brokerChannel = 0
local allChannel = 65533

local volume = 3     -- CC:Tweaked playNote volume range is 0-3
local growth = 1.5   -- backoff multiplier once the flat stages are done
local maxDelay = 30
local heartbeat = 5  -- seconds between fleet pings

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
term.setCursorPos(1, 1)
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
            term.setCursorPos(1, 1)
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
local monitor = peripheral.find("monitor")

local status = "connecting"
local retryIn = 0
local seen = {}      -- every turtle id that has ever answered a ping
local alive = {}     -- answered during the round in progress
local lastAlive = {} -- answered during the last completed round

-- the monitor is a separate surface from the terminal, so drawing here never
-- fights with the log. with no monitor attached this does nothing at all.
local function draw()
    if not monitor then
        return
    end
    monitor.clear()
    monitor.setCursorPos(1, 1)
    monitor.write("server: " .. status)

    if retryIn > 0 then
        monitor.setCursorPos(1, 2)
        monitor.write("retry in " .. math.ceil(retryIn) .. "s")
    end

    local ids = {}
    for tid in pairs(seen) do
        ids[#ids + 1] = tid
    end
    table.sort(ids)

    for i, tid in ipairs(ids) do
        monitor.setCursorPos(1, i + 3)
        monitor.write(tid .. ": " .. (lastAlive[tid] and "ok" or "down"))
    end
end

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
        status = "connecting"
        retryIn = 0
        draw()

        local ws, err = http.websocket("ws://" .. host .. "/ws", { Authorization = password })
        if ws then
            status = "ok"
            draw()
            return ws
        end

        attempt = attempt + 1
        status = "down"
        local remaining = delay(attempt)
        print("connect failed (" .. tostring(err) .. "), retrying in " .. remaining .. "s")

        -- sleep in steps so the monitor can count down
        while remaining > 0 do
            retryIn = remaining
            draw()
            local step = math.min(remaining, 1)
            sleep(step)
            remaining = remaining - step
        end
        retryIn = 0
    end
end

while true do
    print("connecting to server...")
    local ws = connect()
    ws.send("broker " .. os.getComputerID() .. " online")
    chime(true)
    print("connected, listening on channel " .. brokerChannel)

    local timer = os.startTimer(heartbeat)
    local connected = true

    while connected do
        local event, a, b, c, message = os.pullEvent()

        if event == "modem_message" then
            -- a = side, b = channel, c = replyChannel, message = payload.
            -- non-strings are not ours; forwarding one would error and be
            -- misread as a dropped socket.
            if type(message) == "string" then
                local tid = message:match("^pong (%d+)$")
                if tid then
                    -- liveness is the broker's own business; the server gets
                    -- one aggregated report per round instead of n messages.
                    tid = tonumber(tid)
                    seen[tid] = true
                    alive[tid] = true
                elseif not pcall(ws.send, message) then
                    connected = false
                end
            end
        elseif event == "timer" and a == timer then
            lastAlive = alive
            alive = {}

            local ids = {}
            for tid in pairs(lastAlive) do
                ids[#ids + 1] = tid
            end
            table.sort(ids)

            if not pcall(ws.send, "heartbeat " .. textutils.serialiseJSON({ Alive = ids })) then
                connected = false
            end

            modem.transmit(allChannel, brokerChannel, "ping")
            draw()
            timer = os.startTimer(heartbeat)
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

    -- close before reconnecting. without this a send that failed on size
    -- leaves the old socket open server-side and the next connect duplicates it.
    pcall(ws.close)

    status = "down"
    draw()
    chime(false)
    print("disconnected")
end
