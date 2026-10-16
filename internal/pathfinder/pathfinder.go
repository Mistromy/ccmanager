package pathfinder

import (
	"math"

	"github.com/mistromy/ccmanager/internal/turtles"
	"github.com/mistromy/ccmanager/internal/utils"
	"github.com/mistromy/ccmanager/internal/world"
)

type frontierNode struct {
	pos       utils.Vector32
	direction turtles.Rot
	parent    *frontierNode
	g         uint32 //current cost
	h         uint32 //predicted cost
}

func countRotations(parentRot turtles.Rot, childRot turtles.Rot) uint32 {
	count := uint32(math.Abs(float64(parentRot) - float64(childRot)))
	if count == 3 {
		count = 1
	}
	return count
}

func getH(start utils.Vector32, end utils.Vector32) uint32 {
	return uint32(math.Abs(float64(start.X-end.X)) + math.Abs(float64(start.Y-end.Y)) + math.Abs(float64(start.Z-end.Z)))
}

func indexToRot(i int) turtles.Rot {
	switch i {
	case 0: // +x
		return turtles.East
	case 1: // -x
		return turtles.West
	case 4: // +z
		return turtles.South
	case 5: // -z
		return turtles.North
	}
	return turtles.Unknown
}

func pathfind(start utils.Vector32, rot turtles.Rot, end utils.Vector32) ([]string, error) {
	var frontier []*frontierNode
	parent1 := &frontierNode{
		pos:       start,
		direction: rot,
		parent:    nil,
		g:         0,
		h:         getH(start, end),
	}
	frontier = append(frontier, parent1)
	for _, node := range frontier {
		for i, v := range world.GetNeighbors(node.pos) {
			block, _ := world.GetBlockID(v)
			if block == 1 {
				frontier = append(frontier, &frontierNode{
					pos:       v,
					direction: indexToRot(i),
					parent:    node,
					g:         1 + countRotations(node.direction, indexToRot(i)) + node.g,
					h:         getH(start, end),
				})
			}
		}
	}
	return nil, nil
}
