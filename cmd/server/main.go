package main

import (
	"charm.land/log/v2"
	"github.com/mistromy/ccmanager/internal/db"
	"github.com/mistromy/ccmanager/internal/server"
	"github.com/mistromy/ccmanager/internal/tui"
	"github.com/mistromy/ccmanager/internal/turtles"
	"github.com/mistromy/ccmanager/internal/world"
)

const turtleDataPath string = "turtles.json"

func main() {
	log.SetLevel(log.DebugLevel)
	log.SetReportCaller(true)

	port, err := server.Setup() // open form to get password and port.
	if err != nil {
		log.Fatal(err)
	}

	go func() {
		db.Start()
		world.Load()
		turtles.LoadData(turtleDataPath)

		log.Info("Starting ComputerCraft Server.", "port", port)
		go func() {
			if err = server.Start(port); err != nil {
				log.Fatal("Http error", "error", err)
			}
		}()
		//go server.TuiInput()
		go turtles.PeriodicSave(turtleDataPath)
	}()

	tui.Run()
	err = turtles.SaveData(turtleDataPath)
	if err != nil {
		log.Error("saving data", "path", turtleDataPath)
	}
	log.Info("Stopping ComputerCraft Server.", "port", port)
}
