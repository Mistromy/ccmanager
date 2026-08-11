package turtles

import "charm.land/log/v2"

func Register(id uint16, name string) {
	log.Info("Registered turtle", "id", id, "name", name)
}
