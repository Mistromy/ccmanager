local host = "http://100.106.228.50:6767"
local password = "mypassword"

local id = os.getComputerID()
local name = os.getComputerLabel() or ("turtle-" .. id)

local body = textutils.serialiseJSON({ ID = id, Name = name })
local headers = { Authorization = password, ["Content-Type"] = "application/json" }

local res, err, errRes = http.post(host .. "/registerTurtle", body, headers)
res = res or errRes

if res then
    print(res.getResponseCode())
    print(res.readAll())
    res.close()
else
    print(err)
end