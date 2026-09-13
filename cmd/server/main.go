package main

import (
	"os"
	"os/signal"

	"charm.land/log/v2"
	"github.com/mistromy/ccmanager/internal/server"
	"github.com/mistromy/ccmanager/internal/turtles"
)

const turtleDataPath string = "turtles.json"

func main() {
	log.SetLevel(log.DebugLevel)
	log.SetReportCaller(true)
	var (
		port uint16
		err  error
	)

	c := make(chan os.Signal, 1)
	signal.Notify(c, os.Interrupt)

	port, err = server.Setup() // open form to get password and port.
	if err != nil {
		log.Fatal(err)
	}

	turtles.PrintData()

	turtles.LoadData(turtleDataPath)

	turtles.PrintData()

	log.Info("Starting ComputerCraft Server.", "port", port)
	go func() {
		if err = server.Start(port); err != nil {
			log.Fatal("Http error", "error", err)
		}
	}()
	go server.TuiInput()
	turtles.PrintData()
	go turtles.PeriodicSave(turtleDataPath)
	turtles.PrintData()
	<-c
	turtles.PrintData()
	err = turtles.SaveData(turtleDataPath)
	if err != nil {
		log.Error("saving data", "path", turtleDataPath)
	}
	turtles.PrintData()
	log.Info("Stopping ComputerCraft Server.", "port", port)
}
