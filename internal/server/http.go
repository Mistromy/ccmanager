package server

import (
	"fmt"
	"net/http"

	"charm.land/log/v2"
	"golang.org/x/crypto/bcrypt"
)

var hash []byte

func handleStatus(w http.ResponseWriter, r *http.Request) {
	ok := check(r, w, http.MethodGet)
	if !ok {
		return
	}
	_, err := fmt.Fprint(w, "ok")
	if err != nil {
		log.Error(err)
	}
}

func RegisterPassword(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
	}
	p := r.Header.Get("Authorization")
	var err error
	hash, err = bcrypt.GenerateFromPassword([]byte(p), bcrypt.MinCost)
	if err != nil {
		log.Error("creating password", "error", err)
	}
}

func RegisterTurtle(w http.ResponseWriter, r *http.Request) {
	ok := check(r, w, http.MethodPost)
	if !ok {
		return
	}
	_, err := fmt.Fprint(w, "Registered Turtle")
	if err != nil {
		log.Error("registering turtle", "error", err)
	}
}

func Start(port uint16) error {
	http.HandleFunc("/status", handleStatus)
	http.HandleFunc("/register", RegisterPassword)
	http.HandleFunc("/registerTurtle", RegisterTurtle)
	return http.ListenAndServe(fmt.Sprintf(":%d", port), nil)
}

func check(r *http.Request, w http.ResponseWriter, method string) bool {
	if r.Method != method {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		log.Error("method not allowed", "method", method)
		return false
	}
	p := r.Header.Get("Authorization")
	err := bcrypt.CompareHashAndPassword(hash, []byte(p))
	if err != nil {
		http.Error(w, "Invalid password", http.StatusUnauthorized)
		log.Error(err)
		return false
	}
	return true
}
