package turtles

import (
	"errors"

	"charm.land/log/v2"
)

var (
	ErrAlreadyRegistered = errors.New("turtle already registered")
	ErrInvalidName       = errors.New("invalid turtle name")
	ErrInvalidID         = errors.New("invalid turtle id")
)

func Register(id uint16, name string) error {
	_, ok := turtles[id]
	if ok {
		return ErrAlreadyRegistered
	} // returns if turtle already exists
	switch {
	case len(name) == 0:
		return ErrInvalidName
	case len(name) > 50:
		return ErrInvalidName
	case id == 0:
		return ErrInvalidID
	}
	t := Turtle{ID: id, Name: name}
	turtles[id] = &t
	log.Info("Registered Turtle", "id", turtles[id].ID, "name", turtles[id].Name)
	return nil
}

func Update(id uint16, t Turtle) error {
	_, ok := turtles[id]
	if !ok {
		return ErrInvalidID
	} // returns if turtle doesnt exist
	turtles[id] = &t
	log.Debug("Updated Turtle", "data", t)
	return nil
}

func Get(id uint16) *Turtle {
	return turtles[id]
}

func GetAll() []uint16 {
	var tl []uint16
	for _, t := range turtles {
		tl = append(tl, t.ID)
	}
	return tl
}
