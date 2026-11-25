//go:build !windows

package main

import (
	"context"
	"fmt"
)

func chooseCaptureFolder(context.Context, string) (string, error) {
	return "", fmt.Errorf("The native folder picker is available on Windows")
}
