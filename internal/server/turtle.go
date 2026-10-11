package server

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"strconv"
	"strings"

	"charm.land/log/v2"
	"github.com/gorilla/websocket"
	"github.com/mistromy/ccmanager/internal/turtles"
	"github.com/mistromy/ccmanager/internal/world"
)

func turtleWs(w http.ResponseWriter, r *http.Request) {
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		log.Error("upgrade failed", "error", err)
		return
	} // upgrade error
	brokerConn = conn
	defer func() { brokerConn = nil }()
	defer func(conn *websocket.Conn) {
		_ = conn.Close()
	}(conn)

	for {
		_, msg, err := conn.ReadMessage()
		if err != nil {
			log.Error("connection closed", "error", err)
			return
		} // close connection
		i := bytes.IndexByte(msg, ' ')
		if i < 0 {
			i = len(msg)
		}
		if string(msg[:i]) != "heartbeat" {
			log.Debug("received", "msg", string(msg))
		} // log received messages
		//err = conn.WriteMessage(websocket.TextMessage, []byte("ok"))
		switch string(msg[:i]) {
		case "update":
			updateTurtle(msg[i+1:])
		case "register":
			RegisterTurtle(msg[i+1:])
		case "getIDs":
			tl := fmt.Sprintf("list %v", turtles.GetAllIDs())
			err := conn.WriteMessage(websocket.TextMessage, []byte(tl))
			if err != nil {
				return
			}
		case "scan":
			parseScan(msg[i+1:])
		case "heartbeat":
			writeStatus(msg[i+1:])
		}
		if err != nil {
			return
		}
	}
}

func writeStatus(b []byte) {
	str := string(b)
	parts := strings.Fields(str)
	if len(parts) == 0 {
		return
	}
	for _, strID := range parts {
		id, _ := strconv.Atoi(strID)
		turtles.WriteStatus(uint16(id))
	}
}

func parseScan(b []byte) {
	var scan world.ScanMsg
	err := json.Unmarshal(b, &scan)
	if err != nil {
		log.Debug("bad json", "error", err)
		return
	} // incorrect json
	if scan.Rot == 0 {
		log.Error("invalid rot", "rot", scan.Rot)
		return
	} // incorrect rotation
	world.Scan(scan)
	scanClientMSG := struct {
		Id     uint16 `json:"id"`
		X      int32  `json:"x"`
		Y      int32  `json:"y"`
		Z      int32  `json:"z"`
		Radius uint8  `json:"radius"`
	}{
		Id:     scan.ID,
		X:      scan.Pos[0],
		Y:      scan.Pos[1],
		Z:      scan.Pos[2],
		Radius: scan.Radius,
	}
	data, err := json.Marshal(scanClientMSG)

	err = clientConn.WriteMessage(websocket.TextMessage, append([]byte("scan "), data...))
	if err != nil {
		return
	}
}
