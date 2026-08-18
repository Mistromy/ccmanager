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
	name string
	id   uint16
	loc
	fuel    int
	storage [16]item
}

// item is all the data a single slot / item can hold.
type item struct {
	id    string
	name  string
	count uint8
	slot  uint8
}

// loc stores the coordinates and a rotation
type loc struct {
	x, y, z int
	rot
}

// rot is an enum storing the direction a turtle is facing
type rot uint8

const (
	north rot = iota
	east
	south
	west
)
