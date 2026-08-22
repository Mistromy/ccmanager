local host = "http://100.106.228.50:6767"
local password = "myPassword"
term.clear()
term.setCursorPos(1,1)

-- self-install. on a blank turtle the disk's own startup.lua is what runs, and
-- this copies it to the turtle so it boots without the disk from then on.
if fs.exists("disk/startup.lua") then
    fs.delete("startup.lua")
    fs.copy("disk/startup.lua", "startup.lua")
    print("installed from disk")
end

print("installing script...\n")
local res, err = http.get({
    url = host .. "/source/turtle/main.lua",
    headers = { Authorization = password },
    timeout = 5,
})
if not res then
    print("no source: " .. tostring(err))
    sleep(5)
    os.shutdown()
end

local source = res.readAll()
res.close()

local main, loadErr = load(source, "main.lua")
if not main then
    print("bad source: " .. tostring(loadErr))
    sleep(5)
    os.shutdown()
end

main()
