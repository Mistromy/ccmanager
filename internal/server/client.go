package server

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"

	"charm.land/log/v2"
	"github.com/gorilla/websocket"
	"github.com/mistromy/ccmanager/internal/turtles"
)

func clientWs(w http.ResponseWriter, r *http.Request) {
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		log.Error("upgrade failed", "error", err)
		return
	} // upgrade error
	clientConn = conn
	defer func() { clientConn = nil }()
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
			//log.Debug("received", "msg", string(msg))
		} // log received messages
		//err = conn.WriteMessage(websocket.TextMessage, []byte("ok"))
		switch string(msg[:i]) {
		case "getIDs":
			tl := fmt.Sprintf("list %v", turtles.GetAllIDs())
			err := conn.WriteMessage(websocket.TextMessage, []byte(tl))
			if err != nil {
				return
			}
		case "getBlocks":
			//TODO: implement the get world thing
			//coords := string(msg[i+1:])

		case "getTurtles":
			sendTurtleData()
		case "command":
			parse(string(msg[i+1:]))
		}

	}
}

func sendTurtleData() {
	turtleList := turtles.GetAllData()
	data, err := json.Marshal(turtleList)
	//log.Debug(turtleList)
	if err != nil {
		return
	}
	err = clientConn.WriteMessage(websocket.TextMessage, append([]byte("turtles "), data...))
	if err != nil {
		return
	}
}
