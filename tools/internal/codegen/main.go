// Copyright (c) 2026 dexpace and Omar Aljarrah
// SPDX-License-Identifier: MIT

// Package codegen regenerates kuri's checked-in Kotlin fixtures and lookup
// tables from their vendored source corpora. It loads the Unicode/WPT data via
// internal/ucd, derives the IDNA known-failures baseline via internal/idnaref,
// and emits each fixture as byte-identical Kotlin. Main is the CLI entry point.
package codegen

import (
	"errors"
	"flag"
	"fmt"
	"os"
	"path/filepath"
)

// usage describes the command line for error messages.
const usage = "usage: codegen <name> [--stdout]\n" +
	"  names: url, idna-mapping, idna-validity, nfc-tables, nfc-test, conformance, setters, percent-encoding\n" +
	"  conformance also accepts --stdout-data / --stdout-known to print one of its two files"

// singleGenerator pairs a single-file generator's source builder with a resolver
// for the fixture path it writes to.
type singleGenerator struct {
	generate func() (string, error)
	output   func() (string, error)
}

// singleGenerators is the dispatch table for the five generators that emit one
// fixture each. conformance is handled separately in Main because it emits two
// files and accepts its own stdout selectors.
var singleGenerators = map[string]singleGenerator{
	"url": {
		generate: generateURL,
		output:   outputPath("kuri", "src", "commonTest", "kotlin", "org", "dexpace", "kuri", "parser", "UrlTestData.kt"),
	},
	"idna-mapping": {
		generate: generateIdnaMapping,
		output:   outputPath("kuri", "src", "commonMain", "kotlin", "org", "dexpace", "kuri", "idna", "IdnaMappingTableData.kt"),
	},
	"idna-validity": {
		generate: generateIdnaValidity,
		output:   outputPath("kuri", "src", "commonMain", "kotlin", "org", "dexpace", "kuri", "idna", "IdnaValidityData.kt"),
	},
	"nfc-tables": {
		generate: generateNfcTables,
		output:   outputPath("kuri", "src", "commonMain", "kotlin", "org", "dexpace", "kuri", "idna", "NfcData.kt"),
	},
	"nfc-test": {
		generate: generateNfcTest,
		output:   outputPath("kuri", "src", "commonTest", "kotlin", "org", "dexpace", "kuri", "idna", "NfcTestData.kt"),
	},
	"setters": {
		generate: generateSetters,
		output:   outputPath("kuri", "src", "commonTest", "kotlin", "org", "dexpace", "kuri", "parser", "SetterTestData.kt"),
	},
	"percent-encoding": {
		generate: generatePercentEncoding,
		output:   outputPath("kuri", "src", "commonTest", "kotlin", "org", "dexpace", "kuri", "percent", "PercentEncodingTestData.kt"),
	},
}

// Main dispatches to the named generator. The generator name is the first
// positional argument; the remaining arguments are its flags. Every single-file
// generator accepts --stdout; conformance additionally accepts --stdout-data and
// --stdout-known.
func Main(args []string) error {
	if len(args) == 0 {
		return errors.New(usage)
	}
	name := args[0]
	if name == "conformance" {
		return runConformance(args[1:])
	}
	gen, ok := singleGenerators[name]
	if !ok {
		return fmt.Errorf("codegen: unknown generator %q\n%s", name, usage)
	}
	flags := flag.NewFlagSet("codegen "+name, flag.ContinueOnError)
	stdout := flags.Bool("stdout", false, "print generated source to stdout instead of writing the fixture file")
	if err := flags.Parse(args[1:]); err != nil {
		return err
	}
	source, err := gen.generate()
	if err != nil {
		return err
	}
	out, err := gen.output()
	if err != nil {
		return err
	}
	return writeOrStdout(out, source, *stdout)
}

// runConformance parses the conformance subcommand flags and either writes both
// generated files to their fixture paths or prints a selected file to stdout.
// --stdout-data and --stdout-known print exactly one file (handy for
// byte-diffing); --stdout prints both; the default writes both.
func runConformance(args []string) error {
	flags := flag.NewFlagSet("codegen conformance", flag.ContinueOnError)
	stdout := flags.Bool("stdout", false, "print both generated files to stdout instead of writing them")
	stdoutData := flags.Bool("stdout-data", false, "print only IdnaConformanceData.kt to stdout")
	stdoutKnown := flags.Bool("stdout-known", false, "print only IdnaConformanceKnownFailures.kt to stdout")
	if err := flags.Parse(args); err != nil {
		return err
	}
	dataSource, knownSource, err := generateConformance()
	if err != nil {
		return err
	}
	switch {
	case *stdoutData:
		_, err = os.Stdout.WriteString(dataSource)
		return err
	case *stdoutKnown:
		_, err = os.Stdout.WriteString(knownSource)
		return err
	case *stdout:
		if _, err = os.Stdout.WriteString(dataSource); err != nil {
			return err
		}
		_, err = os.Stdout.WriteString(knownSource)
		return err
	default:
		return writeConformanceFixtures(dataSource, knownSource)
	}
}

// outputPath returns a resolver for a fixture path expressed as repo-root-relative
// segments, deferring the repo-root lookup until the generator actually runs.
func outputPath(segments ...string) func() (string, error) {
	return func() (string, error) {
		root, err := Root()
		if err != nil {
			return "", err
		}
		return filepath.Join(append([]string{root}, segments...)...), nil
	}
}

// writeOrStdout prints source to stdout when stdout is set, otherwise writes it
// to path, creating the parent directory if needed.
func writeOrStdout(path, source string, stdout bool) error {
	if stdout {
		_, err := os.Stdout.WriteString(source)
		return err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	return os.WriteFile(path, []byte(source), 0o644)
}
