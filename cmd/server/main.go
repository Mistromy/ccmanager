package main

import (
	"os"

	"charm.land/log/v2"
	"github.com/mistromy/ccmanager/internal/server"
)

func main() {
	var (
		port     uint16
		password string
		err      error
	)

	if len(os.Args) > 1 && os.Args[1] == "-c" {
		port, password, err = server.Setup() // open form to get password and port.
		if err != nil {
			log.Fatal(err)
		}
	}

	err = server.RegisterPassword(password) // send password to http
	if err != nil {
		log.Error("creating password", "error", err)
	}

	log.Info("Starting ComputerCraft Server.", "port", port)
	err = server.Start(port) // start http server
	if err != nil {
		log.Fatal("Http error", "error", err)
	}
}
