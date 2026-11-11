package utils

import "strconv"

func ToInteger(i string) int32 {
	result, _ := strconv.Atoi(i)
	return int32(result)
}
