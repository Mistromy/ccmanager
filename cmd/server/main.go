package main

import (
	"charm.land/log/v2"
	"github.com/mistromy/ccmanager/internal/server"
)

var turtles []Turtle

type Turtle struct {
	name string
	id   uint16
	loc
	fuel    int
	storage []string
}

type loc struct {
	x, y, z int
	rot
}

type rot int

const (
	north rot = iota
	east
	south
	west
)

var (
	port uint16
)

func main() {
	p, err := server.SetPort()
	if err != nil {
		log.Fatal(err)
	}
	port = p
	log.Info("Starting ComputerCraft Server.", "port", port)
}
