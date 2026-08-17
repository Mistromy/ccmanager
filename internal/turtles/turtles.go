package turtles

var turtles = make(map[uint16]*Turtle)

type Turtle struct {
	name string
	id   uint16
	loc
	fuel    int
	storage [16]item
}
type item struct {
	id    string
	name  string
	count uint8
	slot  uint8
}

type loc struct {
	x, y, z int
	rot
}

type rot uint8

const (
	north rot = iota
	east
	south
	west
)
