package server

import (
	"errors"
	"strconv"

	"charm.land/huh/v2"
	"charm.land/log/v2"
)

var port uint16

func SetPort() (uint16, error) {
	log.Info("Choose to port to run server")

	var tempPort string
	err := huh.NewInput().
		Title("Port").
		Value(&tempPort).
		Validate(func(input string) error {
			err := validatePort(input)
			if err != nil {
				return err
			}
			return nil
		}).
		Run()
	if err != nil {
		return 0, err
	}
	return port, nil
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
