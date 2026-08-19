local host = "http://100.106.228.50:6767"
local password = "myPassword"

local id = os.getComputerID()
local name = os.getComputerLabel() or ("turtle-" .. id)

local x, y, z = gps.locate()
if not x then
    print("no gps fix")
    return
end
local ubody = textutils.serialiseJSON({
    ID = id,
    Name = name,
    Loc = {
        X = x,
        Y = y,
        Z = z
        },
    Fuel = turtle.getFuelLevel(),
})

local rbody = textutils.serialiseJSON({ ID = id, Name = name })
local headers = { Authorization = password, ["Content-Type"] = "application/json" }

res, err, errRes = http.post(host .. "/registerTurtle", rbody, headers)
res = res or errRes

if res then
    print(res.getResponseCode())
    print(res.readAll())
    res.close()
else
    print(err)
end

res, err, errRes = http.post(host .. "/updateTurtle", ubody, headers)
res = res or errRes

if res then
    print(res.getResponseCode())
    print(res.readAll())
    res.close()
else
    print(err)
end