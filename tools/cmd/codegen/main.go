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
	"flag"
	"fmt"
	"os"

	"github.com/dexpace/kuri/tools/internal/gen/conformance"
	"github.com/dexpace/kuri/tools/internal/gen/idnamapping"
	"github.com/dexpace/kuri/tools/internal/gen/idnavalidity"
	"github.com/dexpace/kuri/tools/internal/gen/nfctables"
	"github.com/dexpace/kuri/tools/internal/gen/nfctest"
	urlgen "github.com/dexpace/kuri/tools/internal/gen/url"
)

// generators maps a subcommand name to its generator entry point. Each entry
// point writes its fixture (or prints it when stdout is true) and is fully
// self-contained in its own package, so generators evolve independently.
var generators = map[string]func(stdout bool) error{
	"url":           urlgen.Run,
	"idna-mapping":  idnamapping.Run,
	"idna-validity": idnavalidity.Run,
	"nfc-tables":    nfctables.Run,
	"nfc-test":      nfctest.Run,
	"conformance":   conformance.Run,
}

// usage describes the command line for error messages.
const usage = "usage: codegen <name> [--stdout]\n" +
	"  names: url, idna-mapping, idna-validity, nfc-tables, nfc-test, conformance"

func main() {
	if err := run(os.Args[1:]); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

// run dispatches to the named generator. The generator name is the first
// positional argument; --stdout is parsed from the remaining arguments.
func run(args []string) error {
	if len(args) == 0 {
		return fmt.Errorf(usage)
	}
	name := args[0]
	gen, ok := generators[name]
	if !ok {
		return fmt.Errorf("codegen: unknown generator %q\n%s", name, usage)
	}
	flags := flag.NewFlagSet("codegen "+name, flag.ContinueOnError)
	stdout := flags.Bool("stdout", false, "print generated source to stdout instead of writing the fixture file")
	if err := flags.Parse(args[1:]); err != nil {
		return err
	}
	return gen(*stdout)
}
