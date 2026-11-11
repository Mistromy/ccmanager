package utils

import "strconv"

func ToInteger(i string) (int32, error) {
	result, err := strconv.Atoi(i)
	if err != nil {
		return 0, err
	}
	return int32(result), nil
}
