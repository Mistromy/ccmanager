package server

import (
	"bufio"
	"fmt"
	"os"
	"strconv"
	"strings"

	"charm.land/log/v2"
	"github.com/gorilla/websocket"
	"github.com/mistromy/ccmanager/internal/turtles"
	"github.com/mistromy/ccmanager/internal/utils"
	"github.com/mistromy/ccmanager/internal/world"
)

func parse(s string) {
	parts := strings.Fields(s)
	if len(parts) == 0 {
		return
	}
	if id, err := strconv.ParseUint(parts[0], 10, 16); err == nil {
		if brokerConn == nil {
			log.Info("broker not connected")
			return
		}
		turtleCommand(uint16(id), parts[1:])
	} else {
		switch parts[0] {
		case "-1":
			turtleCommand(65533, parts[1:])
		case "getblock":
			if len(parts) < 4 {
				log.Error("Not enough arguments")
				return
			}
			if len(parts) > 4 {
				log.Error("Too many arguments")
				return
			}
			coords := utils.Vector32{
				X: utils.ToInteger(parts[1]),
				Y: utils.ToInteger(parts[2]),
				Z: utils.ToInteger(parts[3]),
			}
			block, err := world.GetBlock(coords)
			if err != nil {
				log.Error(err)
				return
			}
			log.Info(block)
		case "list":
			log.Info("list", turtles.GetAll())
		case "commands":
		//	TODO add commands list command
		case "update":
			var s []string
			s = append(s, "reboot")
			turtleCommand(65533, s)
			turtleCommand(0, s)
		case "clear":
			fmt.Print("\033[H\033[2J")
		default:
			log.Info("unknown command: %s", parts[0])
		}
	}
}

func turtleCommand(id uint16, args []string) string {
	err := brokerConn.WriteMessage(websocket.TextMessage, []byte(fmt.Sprintf("%d %v", id, strings.Join(args, " "))))
	log.Debug("sent message", "id", id, "message", fmt.Sprintf("%d %v", id, strings.Join(args, " ")))
	if err != nil {
		log.Error("sending command", "error", err)
		return err.Error()
	}
	return ""
}

func TuiInput() {
	scanner := bufio.NewScanner(os.Stdin)
	for scanner.Scan() {
		parse(scanner.Text())
	}
}
