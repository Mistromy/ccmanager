package server

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"golang.org/x/crypto/bcrypt"
)

func Test_check(t *testing.T) {
	tests := []struct {
		name          string // name of the test
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
	type args struct {
		w http.ResponseWriter
		r *http.Request
	}
	tests := []struct {
		name string
		args args
	}{
		// TODO: Add test cases.
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			RegisterTurtle(tt.args.w, tt.args.r)
		})
	}
}
