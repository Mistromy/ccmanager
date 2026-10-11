local launchPower = 3

-- the overlay and hud moved to the minecraft mod. what is left is the dash,
-- which needs nothing but the interface. no server link either: the server
-- holds a single /client socket and the mod owns it now.

local modules = peripheral.find("neuralInterface")
if not modules then
    error("must have a neural interface", 0)
end

-- groups outlive the script, so a pair of glasses that ran the old overlay
-- keeps drawing it until something clears them
for _, name in ipairs({ "canvas3d", "canvas" }) do
    if modules[name] then
        local surface = modules[name]()
        if surface and surface.clear then
            pcall(surface.clear)
        end
    end
end

local meta = {}

-- the one thing that must never stop working
local function inputLoop()
    while true do
        local _, key = os.pullEvent("key")
        if key == keys.leftAlt then
            modules.launch(meta.yaw, meta.pitch, launchPower)
        end
    end
end

local function metaLoop()
    while true do
        meta = modules.getMetaOwner() or {}
        sleep(0.1)
    end
end

parallel.waitForAny(inputLoop, metaLoop)
