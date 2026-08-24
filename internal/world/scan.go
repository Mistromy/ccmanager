package world

import (
	"charm.land/log/v2"
	"github.com/mistromy/ccmanager/internal/utils"
)

var (
	worldPalette []string
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
		// TODO setBlock()
		log.Debug("processed scan", "coords", coords, "idMap", idMap)
	}
	for i, block := range worldPalette {
		log.Debug("added block:", "index", i, "block", block)
	}
}

func convert(b [4]int8, p [3]int32, r int8, d []uint16) ([3]int32, uint16) {
	return transform(b, p, r), d[b[3]]
}

func setBlock(blockType uint16, pos [3]int32) {
}

func transform(b [4]int8, t [3]int32, r int8) [3]int32 {
	return translate(rotate(b, r), t)
}

func translate(b [3]int8, t [3]int32) [3]int32 {
	a := [3]int32{int32(b[0]), int32(b[1]), int32(b[2])}
	return utils.Int32VectorAdd(a, t)
}

func rotate(b [4]int8, rotation int8) [3]int8 {
	switch rotation {
	case 0:
		return [3]int8{b[2], b[1], -b[0]}
	case 1:
		return [3]int8{b[0], b[1], b[2]}
	case 2:
		return [3]int8{-b[2], b[1], b[0]}
	case 3:
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
