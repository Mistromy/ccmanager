package world

import (
	"bytes"
	"encoding/binary"
	"errors"

	"charm.land/log/v2"
	"github.com/mistromy/ccmanager/internal/db"
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
	worldPalette = append(worldPalette, "minecraft:air")
	loadWorld()
}

func loadWorld() {
	log.Debug("Loading Palette")
	var err error
	worldPalette, err = db.LoadPalette()
	if err != nil {
		log.Error("loading world palette", "error", err)
		return
	}

	log.Debug("Loading chunks")
	chunksAsBytes, err := db.LoadAllChunks()
	if err != nil {
		log.Error("", "error", err)
		return
	}
	for key, chunkBytes := range chunksAsBytes {
		if len(chunkBytes) != 8192 {
			log.Warn("Wrong Chunk Length. skipping chunk...", "key", key)
			continue
		}
		chunk := new([4096]uint16)
		err := binary.Read(bytes.NewReader(chunkBytes), binary.LittleEndian, chunk)
		if err != nil {
			log.Warn("Error reading chunk", "key", key)
			return
		}
		chunks[key] = chunk
	}
	log.Info("Loaded world")
}

func generateMortonLUT() {
	for v := range 16 {
		var out uint16
		for i := range 4 {
			if v>>i&1 == 1 {
				out |= 1 << (3 * i)
			}
		}
		mortonLUT[v] = out
	}
}
