package turtles

var turtles = make(map[uint16]*Turtle)

type Turtle struct {
	ID      uint16   `json:"id"`
	Name    string   `json:"name"`
	Loc     Loc      `json:"loc"`
	Fuel    int      `json:"fuel"`
	Storage [16]Item `json:"storage"`
}

// Item is all the data a single slot / Item can hold.
type Item struct {
	Id    string
	Name  string
	Count uint8
	Slot  uint8
}

// Loc stores the coordinates and a rotation
type Loc struct {
	X, Y, Z int
	Rot
}

// Rot is an enum storing the direction a turtle is facing
type Rot uint8

const (
	North Rot = iota
	East
	South
	West
)
