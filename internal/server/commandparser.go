package server

import (
	"bufio"
	"fmt"
	"os"
	"strconv"
	"strings"

	"charm.land/log/v2"
	"github.com/gorilla/websocket"
	"github.com/mistromy/ccmanager/internal/pathfinder"
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
		command, args := parts[0], parts[1:]
		switch command {
		case "-1":
			turtleCommand(65533, args)
		case "getblock":
			if len(args) < 3 {
				log.Error("Not enough arguments")
				return
			}
			if len(args) > 3 {
				log.Error("Too many arguments")
				return
			}
			coords := utils.Vector32{
				X: utils.ToInteger(args[0]),
				Y: utils.ToInteger(args[1]),
				Z: utils.ToInteger(args[2]),
			}
			block, err := world.GetBlock(coords)
			if err != nil {
				log.Error(err)
				return
			}
			log.Info(block)
		case "list":
			log.Info("list", "turtles", turtles.GetAllIDs())
		case "help":
			log.Print("Commands:\nnumber - specifies turtle id to execute command\n0 is broker\n1n is all turtles\ngetblock x y z\nlist\nhelp\nupdate\nclear")
		case "update":
			var s []string
			s = append(s, "reboot")
			turtleCommand(65533, s)
			turtleCommand(0, s)
		case "clear":
			fmt.Print("\033[H\033[2J")
		case "pathfind":
			id := uint16(utils.ToInteger(args[0]))
			startPost := utils.Vector32{int32(turtles.Get(id).Loc.X), int32(turtles.Get(id).Loc.Y), int32(turtles.Get(id).Loc.Z)}
			traceback, explored, frontier, err := pathfinder.Pathfind(startPost, turtles.Get(id).Loc.Rot, utils.Vector32{int32(utils.ToInteger(args[1])), int32(utils.ToInteger(args[2])), int32(utils.ToInteger(args[3]))})
			if err != nil {
				log.Error(err)
				return
			}
			commands := pathfinder.TranslateTraceback(traceback)
			sendPathfindData(explored, frontier)
			turtleCommand(id, commands)
		default:
			log.Error("unknown command", "command", command)
		}
		sendTurtleData()
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
