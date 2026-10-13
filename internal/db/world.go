package db

import (
	"time"
)

func SaveChunk(key uint64, chunk []byte) error {
	_, err := worldDB.Exec(`INSERT INTO chunks (key, data, updated_at)
VALUES (?, ?, ?)
ON CONFLICT (key) DO UPDATE SET
    data = excluded.data,
    updated_at = excluded.updated_at`, key, chunk, time.Now().Unix())
	if err != nil {
		return err
	}
	return nil
}

func LoadPalette() ([]string, error) {
	palette := make([]string, 0)
	query, err := worldDB.Query(`SELECT name FROM worldPalette ORDER BY id`)
	if err != nil {
		return nil, err
	}
	defer query.Close()
	for query.Next() {
		var block string
		err = query.Scan(&block)
		if err != nil {
			return nil, err
		}
		palette = append(palette, block)
	}
	err = query.Err()
	if err != nil {
		return nil, err
	}
	return palette, nil
}

func SavePalette(palette []string) error {
	tx, err := worldDB.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	for id, name := range palette {
		_, err = tx.Exec(`INSERT OR IGNORE INTO worldPalette (id, name)
       VALUES (?, ?)`, id, name)
		if err != nil {
			return err
		}
	}
	err = tx.Commit()
	if err != nil {
		return err
	}
	return nil
}

func LoadAllChunks() (map[uint64][]byte, error) {
	query, err := worldDB.Query(`SELECT key, data FROM chunks`)
	defer query.Close()
	if err != nil {
		return nil, err
	}
	chunks := make(map[uint64][]byte)
	for query.Next() {
		var key uint64
		var chunkBytes []byte
		_ = query.Scan(&key, &chunkBytes)
		chunks[key] = chunkBytes
	}
	err = query.Err()
	if err != nil {
		return nil, err
	}
	return chunks, nil
}

func getChunk(key uint64) (chunk []byte, err error) {
	row := worldDB.QueryRow(`SELECT data FROM chunks where KEY = ?`, key)
	err = row.Scan(&chunk)
	if err != nil {
		return nil, err
	}
	return chunk, nil
}
