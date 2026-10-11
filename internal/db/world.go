package db

import "time"

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
