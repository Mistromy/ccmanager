package server

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"

	"charm.land/log/v2"
	"github.com/gorilla/websocket"
	"github.com/mistromy/ccmanager/internal/turtles"
	"golang.org/x/crypto/bcrypt"
)

var hash []byte
var upgrader = websocket.Upgrader{}
var brokerConn *websocket.Conn

func handleWs(w http.ResponseWriter, r *http.Request) {
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		log.Error("upgrade failed", "error", err)
		return
	}
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
		}
		log.Debug("received", "msg", string(msg))
		err = conn.WriteMessage(websocket.TextMessage, []byte("ok"))
		parts := strings.Fields(string(msg))
		switch parts[0] {
		case "update":
			updateTurtle(parts[1])
		case "register":
			RegisterTurtle(parts[1])
		case "getAll":
			tl := fmt.Sprintf("%v", turtles.GetAll())
			err := conn.WriteMessage(websocket.TextMessage, []byte(tl))
			if err != nil {
				return
			}
		case "scan":
			//var id uint16 = 5
			//turtles.ProcessScan(fmt.Sprintf(parts[1:]), id) // TODO make it send all parts after the value or whatever, or fix the thing before where it only looks at the first space.
		}
		if err != nil {
			return
		}
	}
}

func setHash(h []byte) {
	hash = h
}

func RegisterPassword(password string) ([]byte, error) {
	var err error
	hash, err = bcrypt.GenerateFromPassword([]byte(password), bcrypt.MinCost)
	if err != nil {
		return nil, err
	}
	return hash, nil
}

func RegisterTurtle(s string) {

	var data turtles.Turtle
	err := json.Unmarshal([]byte(s), &data)
	if err != nil {
		log.Debug("bad json", "error", err)
		return
	} // handle malformatted json

	err = turtles.Register(data.ID, data.Name)
	if err != nil {
		log.Error("register failed", "error", err)
	} // handle register error

	err = turtles.SaveData("turtles.json")
	if err != nil {
		log.Error("saving data", "path", "turtles.json")
	}
}

func GetTurtle(w http.ResponseWriter, r *http.Request) {
	type rr struct {
		ID uint16 `json:"ID"`
	}
	var req rr
	err := json.NewDecoder(r.Body).Decode(&req)
	if err != nil {
		log.Debug("bad json", "error", err)
		msg := fmt.Sprintf("error: %v", err)
		http.Error(w, msg, http.StatusBadRequest)
		return
	} // handle json error

	turtle := turtles.Get(req.ID)
	w.Header().Set("Content-Type", "application/json")
	err = json.NewEncoder(w).Encode(turtle)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
	}
}

func GetTurtleList(w http.ResponseWriter, _ *http.Request) {
	_, _ = fmt.Fprint(w, turtles.GetAll())
}

func updateTurtle(s string) {
	var data turtles.Turtle
	err := json.Unmarshal([]byte(s), &data) // decode json
	if err != nil {
		return
	}

	err = turtles.Update(data.ID, data) // update pointer with value from data
	if err != nil {
		return
	}
}

func sendCommand(w http.ResponseWriter, r *http.Request) {
	data, err := io.ReadAll(r.Body)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	s := string(data)
	parse(s)
	w.WriteHeader(http.StatusNoContent)
}

func Start(port uint16) error {
	fs := http.StripPrefix("/source/", http.FileServer(http.Dir("../lua")))
	http.HandleFunc("/source/", check(http.MethodGet, fs.ServeHTTP))
	http.HandleFunc("/getTurtle", check(http.MethodGet, GetTurtle))
	http.HandleFunc("/getAll", check(http.MethodGet, GetTurtleList))
	http.HandleFunc("/ws", check(http.MethodGet, handleWs))
	http.HandleFunc("/sendCommand", check(http.MethodPost, sendCommand))
	return http.ListenAndServe(fmt.Sprintf(":%d", port), nil)
}

// check verifies that the method of the request is correct.
//
// also performs authentication comparing password to store password hash
func check(method string, next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method != method {
			http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
			log.Debug("method not allowed", "method", method)
			return
		}
		p := r.Header.Get("Authorization")
		err := bcrypt.CompareHashAndPassword(hash, []byte(p))
		if err != nil {
			http.Error(w, "Invalid password", http.StatusUnauthorized)
			log.Debug(err)
			return
		}
		next(w, r)
	}
}
