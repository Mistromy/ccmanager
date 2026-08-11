package server

import (
	"errors"
	"strconv"

	"charm.land/huh/v2"
	"charm.land/log/v2"
)

func SetPort() (uint16, error) {
	log.Info("Choose to port to run server")
	var port uint16
	var tempPort string
	err := huh.NewInput().
		Title("Port").
		Value(&tempPort).
		Validate(func(input string) error {
			i, err := strconv.Atoi(input)
			if err != nil {
				return err
			}
			if i > 65535 || i < 1 {
				return errors.New("port out of range. Must be between 1 and 65535")
			}
			port = uint16(i)
			return nil
		}).
		Run()
	if err != nil {
		return 0, err
	}
	return port, nil
}
