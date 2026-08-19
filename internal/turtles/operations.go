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
	log.Info("Registering turtle", "id", id, "name", name)
	_, ok := turtles[id]
	if ok {
		return ErrAlreadyRegistered
	}
	switch {
	case len(name) == 0:
		return ErrInvalidName
	case len(name) > 50:
		return ErrInvalidName
	case id == 0:
		return ErrInvalidID
	}
	t := Turtle{id: id, name: name}
	turtles[id] = &t
	log.Info("Registered Turtle", "id", turtles[id].id, "name", turtles[id].name)
	return nil
}

func Fill(turtle Turtle) {

	return
}

func Get(id uint16) *Turtle {
	return turtles[id]
}
