local host = "100.106.228.50:6767"
local password = "myPassword"
local brokerChannel = 0
local allChannel = 65533

local heartbeat = 0.5    -- seconds between fleet pings
local staleAfter = 500  -- ms without a pong before a turtle reads as down 1500 to avoid blips
local connectTimeout = 3 -- cap on a single websocket attempt
local volume = 3         -- CC:Tweaked playNote volume range is 0-3

-- backoff: 0.5s for 20 attempts, 1s through attempt 80, then growth to a cap.
-- about 70 seconds of near-instant retries before it starts stretching.
local growth = 1.3
local maxDelay = 30

-- Self-update. Fetch this script from the server; if it differs and compiles,
-- write it and run the new copy instead of continuing. The fresh copy repeats
-- the fetch, finds itself identical, and falls through - so this cannot loop.
local res = http.get({
    url = "http://" .. host .. "/source/broker/startup.lua",
    headers = { Authorization = password },
    timeout = 5,
})
term.clear()
term.setCursorPos(1, 1)
print("checking if up to date...")
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

local ws = nil        -- current socket, nil while disconnected
local status = "down" -- the server link only
local phase = nil     -- what it is doing about it, when down
local known = {}      -- id -> true, from the registry or from a pong
local lastSeen = {}   -- id -> epoch ms of its last pong

-- turtle liveness is the modem heartbeat and nothing else. it does not depend
-- on the server link, and stays accurate while the server is gone.
local function isUp(tid)
    local t = lastSeen[tid]
    return t ~= nil and (os.epoch("utc") - t) < staleAfter
end

local function draw()
    if not monitor then
        return
    end
    local colour = monitor.isColour and monitor.isColour()

    -- label stays white, only the value carries the state colour
    local function line(row, label, value, good)
        monitor.setCursorPos(1, row)
        if colour then
            monitor.setTextColour(colours.white)
        end
        monitor.write(label)
        if colour then
            monitor.setTextColour(good and colours.green or colours.red)
        end
        monitor.write(value)
    end

    monitor.clear()
    line(1, "server: ", status, status == "ok")

    if phase then
        monitor.setCursorPos(1, 2)
        if colour then
            monitor.setTextColour(colours.white)
        end
        monitor.write(phase)
    end

    local ids = {}
    for tid in pairs(known) do
        ids[#ids + 1] = tid
    end
    table.sort(ids)

    for i, tid in ipairs(ids) do
        local up = isUp(tid)
        line(i + 3, tid .. ": ", up and "ok" or "down", up)
    end

    if colour then
        monitor.setTextColour(colours.white)
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

local function delay(attempt)
    if attempt <= 20 then
        return 0.5
    elseif attempt <= 80 then
        return 1
    end
    return math.min(growth ^ (attempt - 80), maxDelay)
end

local function connect()
    local attempt = 0
    while true do
        phase = "connecting..."
        draw()

        -- bounded, or a failing attempt blocks for the config http timeout and
        -- the backoff numbers stop meaning anything.
        local sock, err = http.websocket({
            url = "ws://" .. host .. "/ws",
            headers = { Authorization = password },
            timeout = connectTimeout,
        })
        if sock then
            status = "ok"
            phase = nil
            draw()
            return sock
        end

        attempt = attempt + 1
        status = "down"
        local remaining = delay(attempt)
        print("connect failed (" .. tostring(err) .. "), retry in " .. remaining .. "s")

        -- plain sleep: under parallel the heartbeat coroutine keeps running
        while remaining > 0 do
            phase = "retrying in " .. math.ceil(remaining) .. "s"
            draw()
            local step = math.min(remaining, 1)
            sleep(step)
            remaining = remaining - step
        end
        phase = nil
    end
end

-- relay runs until the socket dies, then returns so the caller reconnects.
local function relay()
    while true do
        -- the payload sits at a different position per event type.
        -- modem_message: side, channel, replyChannel, message, distance
        -- websocket_message: url, message, isBinary
        local e = { os.pullEvent() }
        local event = e[1]

        if event == "websocket_closed" then
            return
        elseif event == "modem_message" then
            local message = e[5]
            if type(message) == "string" then
                local tid = tonumber(message:match("^pong (%d+)$"))
                if tid then
                    -- liveness is the broker's own business; the server gets
                    -- one aggregated list per round instead of n messages.
                    known[tid] = true
                    lastSeen[tid] = os.epoch("utc")
                    draw()
                elseif not pcall(ws.send, message) then
                    return
                end
            end
        elseif event == "websocket_message" then
            local text = tostring(e[3])

            if text:match("^%s*%[") then
                -- the turtle registry, as a list of ids
                for n in text:gmatch("%d+") do
                    known[tonumber(n)] = true
                end
                draw()
            else
                local words = {}
                for w in string.gmatch(text, "%S+") do
                    words[#words + 1] = w
                end

                -- a modem never hears its own transmission, so anything
                -- addressed to the broker is handled here rather than sent.
                local id = tonumber(words[1])
                if id == brokerChannel then
                    if words[2] == "reboot" then
                        os.reboot()
                    else
                        print("unknown command: " .. tostring(words[2]))
                    end
                elseif id then
                    modem.transmit(id, brokerChannel, table.concat(words, " ", 2))
                else
                    print("server: " .. text)
                end
            end
        end
    end
end

local function connectionLoop()
    while true do
        ws = connect()
        ws.send("broker " .. os.getComputerID() .. " online")
        ws.send("getAll")
        chime(true)
        print("connected, listening on channel " .. brokerChannel)

        relay()

        pcall(ws.close)
        ws = nil
        status = "down"
        draw()
        chime(false)
        print("disconnected")
    end
end

-- independent of the connection, so turtle status stays accurate while the
-- server is down. sleep() here does not block the other coroutine.
local function heartbeatLoop()
    while true do
        modem.transmit(allChannel, brokerChannel, "ping")

        if ws then
            local ids = {}
            for tid in pairs(known) do
                if isUp(tid) then
                    ids[#ids + 1] = tid
                end
            end
            table.sort(ids)
            pcall(ws.send, "heartbeat " .. textutils.serialiseJSON({ Alive = ids }))
        end

        draw()
        sleep(heartbeat)
    end
end

parallel.waitForAny(connectionLoop, heartbeatLoop)
