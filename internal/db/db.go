package db

import (
	"database/sql"

	"charm.land/log/v2"
	_ "modernc.org/sqlite"
)

var mainDB *sql.DB
var worldDB *sql.DB

func init() {
	log.Info("Starting Databases...")
	log.Info("Starting Main Database (1/2)")
	err := startMainDB()
	if err != nil {
		log.Error("start main database", "error", err)
		return
	}
	log.Info("Starting World Database (2/2)")
	//err = startWorldDB()
	//if err != nil {
	//	log.Error("start world database", "error", err)
	//	return
	//}
	log.Info("All Databases Running.")
}

func startMainDB() error {
	var err error
	mainDB, err = sql.Open("sqlite", "main.db")
	if err != nil {
		return err
	}
	err = mainDB.Ping()
	if err != nil {
		return err
	}
	err = createMainTables()
	if err != nil {
		return err
	}
	return nil
}

//func startWorldDB() error {
//	var err error
//	worldDB, err = sql.Open("sqlite", "world.db")
//	if err != nil {
//		return err
//	}
//	err = worldDB.Ping()
//	if err != nil {
//		return err
//	}
//	err = createWorldTables()
//	if err != nil {
//		return err
//	}
//	return nil
//}

func createMainTables() error {
	_, err := mainDB.Exec(`CREATE TABLE IF NOT EXISTS turtles (
    id    INTEGER PRIMARY KEY,
    name  TEXT    NOT NULL,
    x     INTEGER NOT NULL DEFAULT 0,
    y     INTEGER NOT NULL DEFAULT 0,
    z     INTEGER NOT NULL DEFAULT 0,
    rot   INTEGER NOT NULL DEFAULT 0 CHECK (rot BETWEEN 0 AND 3),
    fuel  INTEGER NOT NULL DEFAULT 0
) STRICT;

CREATE TABLE IF NOT EXISTS storages (
    id         INTEGER PRIMARY KEY,         -- auto-assigned
    kind       TEXT    NOT NULL,            -- 'chest', 'barrel', 'turtle', 'ae2', ...
    name       TEXT    NOT NULL DEFAULT '',
    x          INTEGER,                     -- NULL for turtles (they move)
    y          INTEGER,
    z          INTEGER,
    updated_at INTEGER NOT NULL DEFAULT 0   -- unix time of the last full read
) STRICT;

CREATE TABLE IF NOT EXISTS storage_slots (       -- chests, barrels, turtles
    storage_id INTEGER NOT NULL REFERENCES storages(id) ON DELETE CASCADE,
    slot       INTEGER NOT NULL,
    item       TEXT    NOT NULL,                  -- 'minecraft:cobblestone'
    nbt        TEXT    NOT NULL DEFAULT '',
    count      INTEGER NOT NULL,
    PRIMARY KEY (storage_id, slot)
) STRICT;

CREATE TABLE IF NOT EXISTS storage_items (       -- AE2 / pooled networks
    storage_id INTEGER NOT NULL REFERENCES storages(id) ON DELETE CASCADE,
    item       TEXT    NOT NULL,
    nbt        TEXT    NOT NULL DEFAULT '',
    count      INTEGER NOT NULL,
    PRIMARY KEY (storage_id, item, nbt)
) STRICT;`)
	if err != nil {
		return err
	}
	return nil
}

//func createWorldTables() error {
//	_, err := worldDB.Exec(`CREATE TABLE IF NOT EXISTS storage
//`)
//	if err != nil {
//		return err
//	}
//	return nil
//}
