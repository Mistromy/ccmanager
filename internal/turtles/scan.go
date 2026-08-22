package turtles

import "charm.land/log/v2"

func ProcessScan(raw string, id uint16) {
	var origin Loc
	origin = turtles[id].Loc
	log.Debug("position", "x", origin.X, "y", origin.Y, "z", origin.Z, "rotation", origin.Rot)
	log.Debug("scanData", "raw", raw)
}
