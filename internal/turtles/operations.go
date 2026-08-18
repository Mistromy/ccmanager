package turtles

import "charm.land/log/v2"

func Register(id uint16, name string) {
	log.Info("Registering turtle", "id", id, "name", name)
	t := Turtle{id: id, name: name}
	turtles[id] = &t
	log.Info("Registered Turtle", "turtle", turtles[id])
}

func Fill(turtle Turtle) {
	return
}

func Get(id uint16) *Turtle {
	return turtles[id]
}
