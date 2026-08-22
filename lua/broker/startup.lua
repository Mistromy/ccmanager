local host = "100.106.228.50:6767"
local password = "myPassword"
local brokerChannel = 0
local allChannel = 65533

local volume = 3      -- CC:Tweaked playNote volume range is 0-3
local heartbeat = 1   -- seconds between fleet pings
local connectTimeout = 3 -- cap on a single websocket attempt
local listCommand = "getAll" -- rename here if the server verb changes

-- backoff: fast stage, flat stage, then growth. attempts 1-20 at 0.5s and
-- 21-80 at 1s means about 70 seconds of near-instant retries before it starts
-- stretching, so a server restart is picked up immediately.
local fastUntil = 20
local flatUntil = 80
local growth = 1.3
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

local status = "down" -- just up or down
local phase = nil     -- what it is doing about it, when not ok
local seen = {}      -- every turtle the server knows about, or that has pinged
local alive = {}     -- answered during the round in progress
local lastAlive = {} -- answered during the last completed round

-- the monitor is a separate surface from the terminal, so drawing here never
-- fights with the log. with no monitor attached this does nothing at all.
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
    for tid in pairs(seen) do
        ids[#ids + 1] = tid
    end
    table.sort(ids)

    for i, tid in ipairs(ids) do
        local up = lastAlive[tid] or false
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

-- start a new liveness round and return who answered the previous one.
local function pingRound()
    lastAlive = alive
    alive = {}
    modem.transmit(allChannel, brokerChannel, "ping")
    draw()

    local ids = {}
    for tid in pairs(lastAlive) do
        ids[#ids + 1] = tid
    end
    table.sort(ids)
    return ids
end

-- sleep, but keep servicing pongs. plain sleep() discards every event it is
-- not waiting for, which is why the fleet went dark whenever the server did.
local function wait(seconds)
    local t = os.startTimer(seconds)
    while true do
        local event, a, _, _, message = os.pullEvent()
        if event == "timer" and a == t then
            return
        elseif event == "modem_message" and type(message) == "string" then
            local tid = message:match("^pong (%d+)$")
            if tid then
                tid = tonumber(tid)
                seen[tid] = true
                alive[tid] = true
            end
        end
    end
end

local function delay(attempt)
    if attempt <= fastUntil then
        return 0.5
    elseif attempt <= flatUntil then
        return 1
    end
    return math.min(growth ^ (attempt - flatUntil), maxDelay)
end

local function connect()
    local attempt = 0
    while true do
        phase = "connecting..."
        draw()

        -- bounded, or a failing attempt blocks for the config http timeout and
        -- the backoff numbers stop meaning anything.
        local ws, err = http.websocket({
            url = "ws://" .. host .. "/ws",
            headers = { Authorization = password },
            timeout = connectTimeout,
        })
        if ws then
            status = "ok"
            phase = nil
            draw()
            return ws
        end

        attempt = attempt + 1
        status = "down"
        local remaining = delay(attempt)
        print("connect failed (" .. tostring(err) .. "), retrying in " .. remaining .. "s")

        -- keep the heartbeat running while the server is gone, so the monitor
        -- still shows which turtles are up
        while remaining > 0 do
            phase = "retrying in " .. math.ceil(remaining) .. "s"
            pingRound()
            local step = math.min(remaining, 1)
            wait(step)
            remaining = remaining - step
        end
        phase = nil
    end
end

while true do
    print("connecting to server...")
    local ws = connect()
    ws.send("broker " .. os.getComputerID() .. " online")

    -- ask for the registry immediately, and ping immediately, so the first
    -- heartbeat round already has answers rather than showing everything down.
    ws.send(listCommand)
    modem.transmit(allChannel, brokerChannel, "ping")
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
            local ids = pingRound()
            if not pcall(ws.send, "heartbeat " .. textutils.serialiseJSON({ Alive = ids })) then
                connected = false
            end
            timer = os.startTimer(heartbeat)
        elseif event == "websocket_message" then
            -- a = url, b = message. first word is the target turtle id.
            local text = tostring(b)
            local words = {}
            for w in string.gmatch(text, "%S+") do
                words[#words + 1] = w
            end

            -- a modem never hears its own transmission, so anything addressed
            -- to the broker has to be handled here rather than sent.
            local id = tonumber(words[1])
            if id == brokerChannel then
                if words[2] == "reboot" then
                    pcall(ws.send, brokerChannel .. " rebooting")
                    pcall(ws.close)
                    sleep(0.2) -- let the close frame flush before the reboot
                    os.reboot()
                else
                    print("unknown command: " .. tostring(words[2]))
                end
            elseif id then
                modem.transmit(id, brokerChannel, table.concat(words, " ", 2))
            elseif words[1] == listCommand or words[1] == "list" or text:match("^%s*%[") then
                -- the turtle registry. digits are pulled out rather than
                -- assuming a shape, so a go slice print and json both work.
                for n in text:gmatch("%d+") do
                    seen[tonumber(n)] = true
                end
                draw()
            else
                print("server: " .. text)
            end
        elseif event == "websocket_closed" then
            connected = false
        end
    end

    pcall(ws.close)

    status = "down"
    phase = "connecting..."
    draw()
    chime(false)
    print("disconnected")
end
