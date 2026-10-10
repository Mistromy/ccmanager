package world

import (
	"errors"

	"github.com/mistromy/ccmanager/internal/utils"
)

func GetBlock(position utils.Vector32) (string, error) {
	chunk, ok := chunks[getKey(position)]
	if !ok {
		return "", errors.New("chunk doesn't exist")
	}
	blockID := chunk[mortonIndex(inChunkPos(position))]
	block := worldPalette[blockID]
	return block, nil
}

func init() {
	generateMortonLUT()
	worldPalette = append(worldPalette, "unknown")

}

func generateMortonLUT() {
	for v := 0; v < 16; v++ {
		var out uint16
		for i := 0; i < 4; i++ {
			if v>>i&1 == 1 {
				out |= 1 << (3 * i)
			}
		}
		mortonLUT[v] = out
	}
}
