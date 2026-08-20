package turtles

import (
	"encoding/json"
	"os"

	"charm.land/log/v2"
)

func SaveData(path string) error {
	var tl []Turtle
	for _, t := range turtles {
		tl = append(tl, *t)
	}

	data, err := json.MarshalIndent(tl, "", "	")
	if err != nil {
		return err
	}
	err = os.WriteFile(path, data, 0600)
	if err != nil {
		return err
	}
	return nil
}

func LoadData(path string) {
	data, _ := os.ReadFile(path)
	var tl []Turtle

	_ = json.Unmarshal(data, &tl)
	for _, t := range tl {
		turtles[t.ID] = &t
		log.Info("Loaded turtle", "id", t.ID, "name", t.Name)
	}
}
