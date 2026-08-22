package world

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

}

func applyPalette(msg ScanMsg) {
	for _, block := range msg.Palette {
		if !existsInPalette(block) {
			worldPalette = append(worldPalette, block)
		} else {

		}
	}

}

func existsInPalette(block string) bool {
	for _, worldBlock := range worldPalette {
		if block == worldBlock {
			return true
		}
	}
	return false
}
