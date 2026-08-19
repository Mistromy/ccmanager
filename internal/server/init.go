package server

import (
	"encoding/json"
	"errors"
	"os"
	"strconv"

	"charm.land/huh/v2"
	"charm.land/log/v2"
)

var port uint16

func OpenForm() (uint16, string, error) {
	log.Info("Choose to port to run server")

	var tempPort string
	var password string
	form := huh.NewForm(
		huh.NewGroup(
			huh.NewInput().
				Title("Port").
				Value(&tempPort).
				Validate(func(input string) error {
					err := validatePort(input)
					if err != nil {
						return err
					}
					return nil
				}),
			huh.NewInput().
				Title("Password").
				Value(&password)))
	err := form.Run()
	if err != nil {
		return 0, "", err
	}
	return port, password, nil
}

func needsForm(path string) bool {
	_, err := os.ReadFile(path)
	if err != nil {
		return true
	} else if len(os.Args) > 1 && os.Args[1] == "-c" {
		return true
	}
	return false
}

func loadConfig(path string) (uint16, []byte) {
	data, _ := os.ReadFile(path)
	cfg := struct {
		Port uint16 `json:"port"`
		Hash []byte `json:"hash"`
	}{
		Port: 0, Hash: nil,
	}
	json.Unmarshal(data, &cfg)
	return cfg.Port, cfg.Hash
}

func Setup() (uint16, error) {
	var (
		port uint16
		err  error
		hash []byte
	)
	const path = "config.json"
	if needsForm(path) {
		var password string
		port, password, err = OpenForm()
		hash, err = RegisterPassword(password)
	} else {
		port, hash = loadConfig(path)
		setHash(hash)
		if err != nil {
			return 0, err
		}
	}
	err = saveConfig(port, hash, path)
	if err != nil {
		return 0, err
	}

	return port, nil
}

func saveConfig(port uint16, hash []byte, path string) error {
	var (
		err  error
		data []byte
	)
	cfg := struct {
		Port uint16 `json:"port"`
		Hash []byte `json:"hash"`
	}{
		Port: port, Hash: hash,
	}

	data, err = json.MarshalIndent(cfg, "", "	")
	if err != nil {
		return err
	}
	err = os.WriteFile(path, data, 0600)
	if err != nil {
		return err
	}
	return nil
}

func validatePort(input string) error {
	i, err := strconv.Atoi(input)
	if err != nil {
		return errors.New("invalid port. Must be a whole number between 1 and 65535")
	}
	if i > 65535 || i < 1 {
		return errors.New("port out of range. Must be between 1 and 65535")
	}
	port = uint16(i)
	return nil
}
