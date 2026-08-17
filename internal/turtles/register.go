package turtles

import "charm.land/log/v2"

func Register(id uint16, name string) *Turtle {
	log.Info("Registering turtle", "id", id, "name", name)
	t := Turtle{id: id, name: name}
	turtles[id] = &t
	log.Info("Registered Turtle", "turtle", turtles[id])
	return &t

}

func Get(id uint16) *Turtle {
	return turtles[id]
}
