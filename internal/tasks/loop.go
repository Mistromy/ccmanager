package tasks

import (
	"github.com/mistromy/ccmanager/internal/pathfinder"
	"github.com/mistromy/ccmanager/internal/turtles"
	"github.com/mistromy/ccmanager/internal/utils"
)

func start() {
}

func loop() {
	for {
	}
}

func goTo(dest utils.Vector32, id uint16) {
	tLocation := turtles.Get(id).Loc
	tRotation := tLocation.Rot
	tPosition := utils.Vector32{int32(tLocation.X), int32(tLocation.Y), int32(tLocation.Z)}
	pathfinder.Pathfind(tPosition, tRotation, dest)
}
