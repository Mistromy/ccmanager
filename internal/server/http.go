package server

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http"

	"charm.land/log/v2"
	"github.com/mistromy/ccmanager/internal/turtles"
	"golang.org/x/crypto/bcrypt"
)

var hash []byte

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
	ok := check(r, w, http.MethodPost)
	if !ok {
		return
	}

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
	ok := check(r, w, http.MethodGet)
	if !ok {
		return
	} // return if auth check fails.

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

func GetTurtleList(w http.ResponseWriter, r *http.Request) {
	ok := check(r, w, http.MethodGet)
	if !ok {
		return
	}
	_, _ = fmt.Fprint(w, turtles.GetAll())
}

func updateTurtle(w http.ResponseWriter, r *http.Request) {
	ok := check(r, w, http.MethodPost)
	if !ok {
		return
	}

	var req turtles.Turtle
	err := json.NewDecoder(r.Body).Decode(&req) // decode json
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
	}

	err = turtles.Update(req.ID, req) // update pointer with value from req
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
	}
	w.WriteHeader(http.StatusNoContent)
}

func Start(port uint16) error {
	http.HandleFunc("/registerTurtle", RegisterTurtle)
	http.HandleFunc("/getTurtle", GetTurtle)
	http.HandleFunc("/getAll", GetTurtleList)
	http.HandleFunc("/updateTurtle", updateTurtle)
	return http.ListenAndServe(fmt.Sprintf(":%d", port), nil)
}

// check verifies that the method of the request is correct.
//
// also performs authentication comparing password to store password hash
func check(r *http.Request, w http.ResponseWriter, method string) bool {
	if r.Method != method {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		log.Debug("method not allowed", "method", method)
		return false
	}
	p := r.Header.Get("Authorization")
	err := bcrypt.CompareHashAndPassword(hash, []byte(p))
	if err != nil {
		http.Error(w, "Invalid password", http.StatusUnauthorized)
		log.Debug(err)
		return false
	}
	return true
}
