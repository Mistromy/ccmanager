package main

import (
	"charm.land/log/v2"
	"github.com/mistromy/ccmanager/internal/server"
)

func main() {
	var port uint16
	p, err := server.Setup()
	if err != nil {
		log.Fatal(err)
	}
	port = p

	log.Info("Starting ComputerCraft Server.", "port", port)
	err = server.Start(port)
	if err != nil {
		log.Fatal("Http error", "error", err)
	}
}
