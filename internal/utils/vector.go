package utils

func Int32VectorAdd(a [3]int32, b [3]int32) [3]int32 {
	return [3]int32{a[0] + b[0], a[1] + b[1], a[2] + b[2]}
}
