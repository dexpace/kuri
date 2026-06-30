// Copyright (c) 2026 dexpace and Omar Aljarrah
// SPDX-License-Identifier: MIT

// Package repo locates the kuri repository root so generators resolve their
// input and output paths the same way whether they are invoked from Gradle
// (CWD = repo root) or from `cd tools && go run ...` (CWD = tools).
package repo

import (
	"errors"
	"os"
	"path/filepath"
)

// rootMarker is the file that uniquely identifies the repository root. It lives
// only at the top level of the kuri checkout, so the first ancestor containing
// it is the root.
const rootMarker = "settings.gradle.kts"

// Root walks up from the current working directory and returns the first
// ancestor directory that contains settings.gradle.kts. It errors if no such
// directory exists between the CWD and the filesystem root.
func Root() (string, error) {
	dir, err := os.Getwd()
	if err != nil {
		return "", err
	}
	for {
		marker := filepath.Join(dir, rootMarker)
		if info, statErr := os.Stat(marker); statErr == nil && !info.IsDir() {
			return dir, nil
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			return "", errors.New("repo: " + rootMarker + " not found in any parent of the working directory")
		}
		dir = parent
	}
}
