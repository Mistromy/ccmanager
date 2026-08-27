local host = "http://100.106.228.50:6767"
local password = "myPassword"

term.clear()
term.setCursorPos(1, 1)

-- The glasses are worn and carry the boost hotkey, so they must run whether or
-- not the server exists. Update from the server when it is reachable, keep a
-- local copy, and always run the local copy.
local res = http.get({
    url = host .. "/source/client/main.lua",
    headers = { Authorization = password },
    timeout = 3,
})

if res then
    local source = res.readAll()
    res.close()

    if load(source, "main.lua") then
        local f = fs.open("main.lua", "w")
        f.write(source)
        f.close()
        print("client updated")
    else
        print("update does not compile, keeping current")
    end
else
    print("server unreachable, running local copy")
end

if not fs.exists("main.lua") then
    print("no local main.lua and no server")
    return
end

shell.run("main.lua")
