package pathfinder

import (
	"math"

	"charm.land/log/v2"
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

func getH(start utils.Vector32, end utils.Vector32) uint32 { // distance to end
	return uint32(math.Abs(float64(start.X-end.X)) + math.Abs(float64(start.Y-end.Y)) + math.Abs(float64(start.Z-end.Z)))
}

func indexToRot(i int) turtles.Rot {
	switch i {
	case 0: // -z
		return turtles.North
	case 1: // +x
		return turtles.East
	case 2: // +z
		return turtles.South
	case 3: // -x
		return turtles.West
	}
	return turtles.Unknown
}

func findLowestF(frontier []*frontierNode) (*frontierNode, uint32) {
	bestNode := frontier[0]
	var bestIndex uint32 = 0
	for i, v := range frontier {
		currentF := v.g + v.h
		if currentF < bestNode.g+bestNode.h || currentF == bestNode.g+bestNode.h && v.h < bestNode.h {
			bestNode = v
			bestIndex = uint32(i)
		}
	}
	return bestNode, bestIndex
}

func pathfind(start utils.Vector32, rot turtles.Rot, end utils.Vector32) {
	var frontier []*frontierNode
	parent := &frontierNode{
		pos:       start,
		direction: rot,
		parent:    nil,
		g:         0,
		h:         getH(start, end),
	}
	frontier = append(frontier, parent)

	for {
		activeNode, activeIndex := findLowestF(frontier)
		if activeNode.pos == end {
			log.Debug("Found Path")
			return
		}

		for i, v := range world.GetNeighbors(activeNode.pos) {
			block, _ := world.GetBlockID(v)
			if block == 1 {
				frontier = append(frontier, &frontierNode{
					pos:       v,
					direction: indexToRot(i),
					parent:    activeNode,
					g:         1 + countRotations(activeNode.direction, indexToRot(i)) + activeNode.g,
					h:         getH(v, end),
				})
			}
		}
		frontier[activeIndex] = frontier[len(frontier)-1]
		frontier = frontier[:len(frontier)-1]
	}
}
