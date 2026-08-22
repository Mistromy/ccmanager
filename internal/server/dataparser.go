package server

import (
	"encoding/json"

	"charm.land/log/v2"
	"github.com/mistromy/ccmanager/internal/world"
)

func parseScan(b []byte) {
	var scan world.ScanMsg
	err := json.Unmarshal(b, &scan)
	if err != nil {
		log.Debug("bad json", "error", err)
		return
	} // incorrect json
	if scan.Rot == -1 {
		log.Error("invalid rot", "rot", scan.Rot)
		return
	} // incorrect rotation
	world.Scan(scan)
}
