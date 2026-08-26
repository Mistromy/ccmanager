package world

import (
	"errors"

	"github.com/mistromy/ccmanager/internal/utils"
)

func GetBlock(position utils.Vector32) (string, error) {
	blockID, ok := worldMap[position]
	if !ok {
		return "", errors.New("block doesn't exist")
	}
	block := worldPalette[blockID]
	return block, nil
}
