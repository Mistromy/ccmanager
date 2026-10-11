package world

import (
	"bytes"
	"encoding/binary"

	"charm.land/log/v2"
	"github.com/mistromy/ccmanager/internal/db"
	"github.com/mistromy/ccmanager/internal/utils"
)

var (
	mortonLUT [16]uint16
)

var (
	worldPalette []string
	chunks       = map[uint64]*[4096]uint16{}
)

type ScanMsg struct {
	ID      uint16    `json:"id"`
	Radius  uint8     `json:"radius"`
	Rot     int8      `json:"rot"`
	Pos     [3]int32  `json:"pos"`
	Palette []string  `json:"palette"`
	Blocks  [][4]int8 `json:"blocks"`
}

func Scan(msg ScanMsg) {
	paletteDict := makePalette(msg)
	for _, b := range msg.Blocks {
		coords, idMap := convert(b, msg.Pos, msg.Rot, paletteDict)
		// TODO calculateHashMap and in chunk coords and then write with Morton lut
		setBlock(idMap, coords)
		log.Debug("processed scan", "coords", coords, "idMap", idMap)
	}
	for i, block := range worldPalette {
		log.Debug("added block:", "index", i, "block", block)
	}

	center := utils.Vector32{
		X: msg.Pos[0],
		Y: msg.Pos[1],
		Z: msg.Pos[2],
	}
	corners := findScanExtremes(center, msg.Radius)

	err := saveScanToDB(corners)
	if err != nil {
		log.Error("Save chunk to DB", "error", err)
		log.Warn("Scan recorded but failed to save to DB")
		return
	}
	log.Info("Scan successful")
}

func saveScanToDB(corners [8]utils.Vector32) error {
	chunksInScan := findChunksInScan(corners)
	for _, c := range chunksInScan {
		chunk := *chunks[c]
		chunkBytes := bytes.Buffer{}
		err := binary.Write(&chunkBytes, binary.LittleEndian, &chunk)
		if err != nil {
			return err
		}
		err = db.SaveChunk(c, chunkBytes.Bytes())
		if err != nil {
			return err
		}
	}
	return nil
}

func findChunksInScan(corners [8]utils.Vector32) []uint64 {
	keys := make([]uint64, 0, 8)
CornerLoop:
	for _, c := range corners {
		key := getKey(c)
		for _, k := range keys {
			if k == key {
				continue CornerLoop
			}
		}
		keys = append(keys, key)
	}
	return keys
}

// findScanExtremes finds the 8 corners of a scan to see if they're in the same chunk.
func findScanExtremes(center utils.Vector32, radius uint8) (corners [8]utils.Vector32) {
	corners[0].X = center.X + int32(radius)
	corners[0].Y = center.Y + int32(radius)
	corners[0].Z = center.Z + int32(radius)

	corners[1].X = center.X - int32(radius)
	corners[1].Y = center.Y + int32(radius)
	corners[1].Z = center.Z + int32(radius)

	corners[2].X = center.X + int32(radius)
	corners[2].Y = center.Y + int32(radius)
	corners[2].Z = center.Z - int32(radius)

	corners[3].X = center.X - int32(radius)
	corners[3].Y = center.Y + int32(radius)
	corners[3].Z = center.Z - int32(radius)

	corners[4].X = center.X + int32(radius)
	corners[4].Y = center.Y - int32(radius)
	corners[4].Z = center.Z + int32(radius)

	corners[5].X = center.X - int32(radius)
	corners[5].Y = center.Y - int32(radius)
	corners[5].Z = center.Z + int32(radius)

	corners[6].X = center.X + int32(radius)
	corners[6].Y = center.Y - int32(radius)
	corners[6].Z = center.Z - int32(radius)

	corners[7].X = center.X - int32(radius)
	corners[7].Y = center.Y - int32(radius)
	corners[7].Z = center.Z - int32(radius)
	return corners
}

// convert returns final processed block ready for appending to the world
func convert(b [4]int8, p [3]int32, r int8, d []uint16) ([3]int32, uint16) {
	return transform(b, p, r), d[b[3]]
}

func setBlock(blockType uint16, pos [3]int32) {
	vectorCoords := utils.Vector32{X: pos[0], Y: pos[1], Z: pos[2]}
	key := getKey(vectorCoords)
	chunk, ok := chunks[key]
	if !ok {
		chunk = new([4096]uint16)
		chunks[key] = chunk
	}
	chunk[mortonIndex(inChunkPos(vectorCoords))] = blockType
}

// mortonIndex returns the index of the morton order from the coordinates of a block in the chunk
func mortonIndex(pos utils.UVector8) uint16 {
	return mortonLUT[pos.X] | mortonLUT[pos.Y]<<1 | mortonLUT[pos.Z]<<2
}

func inChunkPos(worldPos utils.Vector32) utils.UVector8 {
	return utils.UVector8{
		X: uint8(worldPos.X & 15),
		Y: uint8(worldPos.Y & 15),
		Z: uint8(worldPos.Z & 15),
	}
}

// getKey takes world coordinates and returns the key for the chunk containing that coordinate.
func getKey(pos utils.Vector32) uint64 {
	var x, y, z uint64
	x = uint64(uint32(pos.X>>4)) & 0x3FFFFF
	y = uint64(uint32(pos.Y>>4)) & 0xFFF
	z = uint64(uint32(pos.Z>>4)) & 0x3FFFFF
	return x<<34 | y<<22 | z
}

// transform returns the world coordinate of a block based on scan position and rotation
func transform(b [4]int8, t [3]int32, r int8) [3]int32 {
	return translate(rotate(b, r), t)
}

// translate moves a block in the world by a given amount
func translate(b [3]int8, t [3]int32) [3]int32 {
	a := [3]int32{int32(b[0]), int32(b[1]), int32(b[2])}
	return utils.Int32VectorAdd(a, t)
}

// rotate rotates blocks in the scan in 90 degree increments based on the turtles rotation
func rotate(b [4]int8, rotation int8) [3]int8 {
	switch rotation {
	case 1:
		return [3]int8{b[2], b[1], -b[0]}
	case 2:
		return [3]int8{b[0], b[1], b[2]}
	case 3:
		return [3]int8{-b[2], b[1], b[0]}
	case 4:
		return [3]int8{-b[0], b[1], -b[2]}
	default:
		return [3]int8{b[2], b[1], -b[0]}
	}
}

func makePalette(msg ScanMsg) []uint16 {
	var paletteDict []uint16
	for _, block := range msg.Palette {
		t, i := existsInPalette(block)
		if !t {
			worldPalette = append(worldPalette, block)
		}
		paletteDict = append(paletteDict, i)

	}
	return paletteDict
}

func existsInPalette(block string) (bool, uint16) {
	var (
		i          int
		worldBlock string
	)
	for i, worldBlock = range worldPalette {
		if block == worldBlock {
			return true, uint16(i)
		}
	}
	return false, uint16(len(worldPalette))
}
