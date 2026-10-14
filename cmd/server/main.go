package main

import (
	"os"
	"os/signal"

	"charm.land/log/v2"
	_ "github.com/mistromy/ccmanager/internal/db"
	"github.com/mistromy/ccmanager/internal/server"
	"github.com/mistromy/ccmanager/internal/turtles"
)

const turtleDataPath string = "turtles.json"

func main() {
	log.SetLevel(log.DebugLevel)
	log.SetReportCaller(true)

	c := make(chan os.Signal, 1)
	signal.Notify(c, os.Interrupt)

	port, err := server.Setup() // open form to get password and port.
	if err != nil {
		log.Fatal(err)
	}

	turtles.LoadData(turtleDataPath)

	log.Info("Starting ComputerCraft Server.", "port", port)
	go func() {
		if err = server.Start(port); err != nil {
			log.Fatal("Http error", "error", err)
		}
	}()
	go server.TuiInput()
	go turtles.PeriodicSave(turtleDataPath)
	<-c
	err = turtles.SaveData(turtleDataPath)
	if err != nil {
		log.Error("saving data", "path", turtleDataPath)
	}
	log.Info("Stopping ComputerCraft Server.", "port", port)
}
