package server

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"golang.org/x/crypto/bcrypt"
)

func Test_check(t *testing.T) {
	tests := []struct {
		name          string // Name of the test
		requestMethod string // method used in the request GET, POST, etc
		password      string // password sent with the request
		requireMethod string //
		wantOK        bool   // desired output of the actual function. true for pass; function carries on.
		wantStatus    int    // wanted http return status
	}{
		{"good", http.MethodPost, "correct", http.MethodPost, true, http.StatusOK},
		{"wrong method", http.MethodGet, "correct", http.MethodPost, false, http.StatusMethodNotAllowed},
		{"bad password", http.MethodPost, "wrong", http.MethodPost, false, http.StatusUnauthorized},
		{"no password", http.MethodPost, "", http.MethodPost, false, http.StatusUnauthorized},
	}
	hash, _ = bcrypt.GenerateFromPassword([]byte("correct"), bcrypt.MinCost)

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			r := httptest.NewRequest(tt.requestMethod, "/", nil)
			r.Header.Set("Authorization", tt.password)
			w := httptest.NewRecorder()
			if got := check(r, w, tt.requireMethod); got != tt.wantOK {
				t.Errorf("check() = %v, requireMethod %v", got, tt.requireMethod)
			}
			if w.Result().StatusCode != tt.wantStatus {
				t.Errorf("check() = %v, wantStatus %v", w.Result().StatusCode, tt.wantStatus)
			}
		})
	}
}

func TestRegisterTurtle(t *testing.T) {
	type data struct {
		ID   uint64 `json:"id"`
		Name string `json:"name"`
	}
	tests := []struct {
		name       string // Name of the test
		data       data
		wantStatus int // wanted http return status
	}{
		{"good", data{ID: 1, Name: "Name"}, http.StatusNoContent},
		{"ID zero", data{ID: 0, Name: "Name"}, http.StatusBadRequest},
		{"already exists", data{ID: 1, Name: "Name"}, http.StatusConflict},
		{"max ID", data{ID: 65535, Name: "Name"}, http.StatusNoContent},
		{"ID too big", data{ID: 65536, Name: "Name"}, http.StatusBadRequest},
		{"no Name", data{ID: 2, Name: ""}, http.StatusBadRequest},
		{"50 character Name", data{ID: 3, Name: "12345678901234567890123456789012345678901234567890"}, http.StatusNoContent},
		{"49 character Name", data{ID: 4, Name: "1234567890123456789012345678901234567890123456789"}, http.StatusNoContent},
		{"50 character Name", data{ID: 5, Name: "123456789012345678901234567890123456789012345678901"}, http.StatusBadRequest},
		{"no ID at all", data{Name: "Name"}, http.StatusBadRequest},
		{"no Name at all", data{ID: 6}, http.StatusBadRequest},
		{"no data", data{}, http.StatusBadRequest},
	}
	hash, _ = bcrypt.GenerateFromPassword([]byte("correct"), bcrypt.MinCost)

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			body, _ := json.Marshal(tt.data)
			r := httptest.NewRequest(http.MethodPost, "/", bytes.NewReader(body))
			r.Header.Set("Authorization", "correct")
			w := httptest.NewRecorder()
			RegisterTurtle(w, r)
			if w.Code != tt.wantStatus {
				t.Errorf("RegisterTurtle() code = %v, wantStatus %v", w.Code, tt.wantStatus)
			}
		})
	}
}
