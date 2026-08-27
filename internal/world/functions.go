package world

import (
	"errors"

	"github.com/mistromy/ccmanager/internal/utils"
)

func GetBlock(position utils.Vector32) (string, error) {
	chunk, ok := chunks[getKey(position)]
	if !ok {
		return "", errors.New("block doesn't exist")
	}
	blockID, ok := chunk[inChunkPos(position)]
	if !ok {
		return "", errors.New("block doesn't exist")
	}
	block := worldPalette[blockID]
	return block, nil
}
