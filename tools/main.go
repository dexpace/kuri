// Copyright (c) 2026 dexpace and Omar Aljarrah
// SPDX-License-Identifier: MIT

// Command codegen regenerates kuri's checked-in Kotlin fixtures and lookup
// tables from their vendored source corpora. Usage:
//
//	codegen <name> [--stdout]
//
// where <name> is one of url, idna-mapping, idna-validity, nfc-tables,
// nfc-test, conformance. With --stdout the generated source is printed instead
// of written to its fixture path.
package main

import (
	"fmt"
	"os"

	"github.com/dexpace/kuri/tools/internal/codegen"
)

func main() {
	if err := codegen.Main(os.Args[1:]); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
