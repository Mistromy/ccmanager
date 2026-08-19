package main

import (
	"charm.land/log/v2"
	"github.com/mistromy/ccmanager/internal/server"
)

func main() {
	var (
		port uint16
		err  error
	)

	port, err = server.Setup() // open form to get password and port.
	if err != nil {
		log.Fatal(err)
	}

	log.Info("Starting ComputerCraft Server.", "port", port)
	err = server.Start(port) // start http server
	if err != nil {
		log.Fatal("Http error", "error", err)
	}
}
