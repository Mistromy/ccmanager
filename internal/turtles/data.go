package turtles

var turtles = make(map[uint16]*Turtle)

// Turtle holds all data a turtle can hold
//
//	name
//	id
//	loc
//	  x, y, z
//	  rot
//	fuel
//	storage
//	  id
//	  name
//	  count
//	  slot
type Turtle struct {
	Name    string   `json:"name"`
	Storage [16]Item `json:"storage"`
	Loc     Loc      `json:"loc"`
	Fuel    int      `json:"fuel"`
	ID      uint16   `json:"id"`
	Rot     Rot      `json:"rot"`
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
	north Rot = iota
	east
	south
	west
)
