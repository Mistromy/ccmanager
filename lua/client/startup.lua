local modules = peripheral.find("neuralInterface")
peripheral.find("modem", rednet.open)
if not modules then
	error("Must have a neural interface", 0)
end

doorID = 17

local meta = {}
local hover = false
parallel.waitForAny(

	function()
		while true do
			local event, key = os.pullEvent()
			if event == "key" and key == keys.leftAlt then
				modules.launch(meta.yaw, meta.pitch, 3)
			elseif event == "key" and key == keys.numPad9 then
				rednet.send(doorID, "open")
			elseif event == "key" and key == keys.numPad8 then
				rednet.send(doorID, "close")
			end
		end
	end,

	function()
		while true do
			meta = modules.getMetaOwner()
		end
	end


)