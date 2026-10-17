package utils

type Rot3D uint8

const (
	North Rot3D = iota // -Z
	East
	South
	West
	Up
	Down
	Unknown
)

func (r Rot3D) String() string {
	switch r {
	case North:
		return "North"
	case East:
		return "East"
	case South:
		return "South"
	case West:
		return "West"
	case Up:
		return "Up"
	case Down:
		return "Down"
	case Unknown:
		return "Unknown"
	}
	return "invalid"
}
