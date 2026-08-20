package server

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"

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

func RegisterTurtle(w http.ResponseWriter, r *http.Request) {

	type rr struct {
		ID   uint16 `json:"ID"`
		Name string `json:"Name"`
	}
	var req rr
	err := json.NewDecoder(r.Body).Decode(&req)
	if err != nil {
		log.Debug("bad json", "error", err)
		msg := fmt.Sprintf("error: %v", err)
		http.Error(w, msg, http.StatusBadRequest)
		return
	} // handle malformatted json

	err = turtles.Register(req.ID, req.Name)
	if err != nil {
		log.Debug("register failed", "error", err)
		msg := fmt.Sprintf("error registering turtle %d, '%s'. %v", req.ID, req.Name, err)
		if errors.Is(err, turtles.ErrAlreadyRegistered) {
			http.Error(w, msg, http.StatusConflict)
		} else {
			http.Error(w, msg, http.StatusBadRequest)
		}
		return
	} // handle register error
	w.WriteHeader(http.StatusNoContent)
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

func updateTurtle(w http.ResponseWriter, r *http.Request) {
	var req turtles.Turtle
	err := json.NewDecoder(r.Body).Decode(&req) // decode json
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	err = turtles.Update(req.ID, req) // update pointer with value from req
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	w.WriteHeader(http.StatusNoContent)
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
	http.HandleFunc("/registerTurtle", check(http.MethodPost, RegisterTurtle))
	http.HandleFunc("/getTurtle", check(http.MethodGet, GetTurtle))
	http.HandleFunc("/getAll", check(http.MethodGet, GetTurtleList))
	http.HandleFunc("/updateTurtle", check(http.MethodPost, updateTurtle))
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
