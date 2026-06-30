// Copyright (c) 2026 dexpace and Omar Aljarrah
// SPDX-License-Identifier: MIT

// Package conformance regenerates the IDNA conformance test fixture
// (IdnaConformanceData.kt) and the tracked known-failures baseline
// (IdnaConformanceKnownFailures.kt) from the WPT IdnaTestV2 and toascii corpora.
//
// The data fixture is a byte-for-byte port of the Python generator: parse both
// corpora, de-duplicate by (input, expected) in first-seen order, and emit the
// chunked IdnaCase builders. Lone surrogates in the corpus inputs are preserved
// through a UTF-16-unit-aware JSON decoder, since encoding/json would replace
// them with U+FFFD.
//
// The known-failures baseline is DERIVED, not copied: a faithful Go port of
// kuri's Idna.domainToAscii (mapping -> NFC -> per-label Punycode/validate/
// re-encode), built on the same Unicode 16.0 data the runtime tables decode, is
// run over the corpus and the inputs it fails become the tracked set. Because the
// reference matches kuri exactly, that set equals kuri's live failing set, which
// IdnaConformanceTest asserts the baseline against.
package conformance

import (
	"flag"
	"os"
	"path/filepath"
	"sort"

	"github.com/dexpace/kuri/tools/internal/repo"
)

// RunCLI parses the conformance subcommand flags and either writes both generated
// files to their fixture paths or prints a selected file to stdout. --stdout-data
// and --stdout-known print exactly one file (handy for byte-diffing); --stdout
// prints both; the default writes both.
func RunCLI(args []string) error {
	flags := flag.NewFlagSet("codegen conformance", flag.ContinueOnError)
	stdout := flags.Bool("stdout", false, "print both generated files to stdout instead of writing them")
	stdoutData := flags.Bool("stdout-data", false, "print only IdnaConformanceData.kt to stdout")
	stdoutKnown := flags.Bool("stdout-known", false, "print only IdnaConformanceKnownFailures.kt to stdout")
	if err := flags.Parse(args); err != nil {
		return err
	}
	dataSource, knownSource, err := Generate()
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
		return writeFixtures(dataSource, knownSource)
	}
}

// Run satisfies the single-bool generator contract used by the codegen dispatch:
// stdout prints both files, otherwise both fixtures are written.
func Run(stdout bool) error {
	if stdout {
		return RunCLI([]string{"--stdout"})
	}
	return RunCLI(nil)
}

// Generate reads the corpora, emits the data fixture, derives the known-failures
// set via the kuri reference, and returns both Kotlin sources.
func Generate() (dataSource, knownSource string, err error) {
	idnaV2, toascii, err := readCorpora()
	if err != nil {
		return "", "", err
	}
	cases, err := loadCases(idnaV2, toascii)
	if err != nil {
		return "", "", err
	}
	ref, err := newReference()
	if err != nil {
		return "", "", err
	}
	failing := deriveFailures(ref, cases)
	return emitFixture(cases), emitKnownFailures(failing), nil
}

// deriveFailures returns, in sorted order, the de-duplicated inputs the kuri
// reference fails the corpus on.
func deriveFailures(ref *reference, cases []idnaCase) [][]uint16 {
	seen := map[string]bool{}
	var failing [][]uint16
	for _, c := range cases {
		if !ref.caseFails(c) {
			continue
		}
		key := string(unitsToKey(c.input))
		if seen[key] {
			continue
		}
		seen[key] = true
		failing = append(failing, c.input)
	}
	sort.Slice(failing, func(i, j int) bool { return lessUnits(failing[i], failing[j]) })
	return failing
}

// lessUnits orders UTF-16 sequences lexicographically by code unit, matching
// Kotlin's natural String ordering (shorter sequence first on a shared prefix).
func lessUnits(a, b []uint16) bool {
	for index := 0; index < len(a) && index < len(b); index++ {
		if a[index] != b[index] {
			return a[index] < b[index]
		}
	}
	return len(a) < len(b)
}

// unitsToKey serializes a UTF-16 sequence to a collision-free byte key.
func unitsToKey(units []uint16) []byte {
	out := make([]byte, 0, len(units)*2)
	for _, unit := range units {
		out = append(out, byte(unit>>8), byte(unit))
	}
	return out
}

// readCorpora reads both WPT corpus files from the vendored ada reference.
func readCorpora() (idnaV2, toascii []byte, err error) {
	root, err := repo.Root()
	if err != nil {
		return nil, nil, err
	}
	dir := filepath.Join(root, ".claude", "references", "ada", "tests", "wpt")
	idnaV2, err = os.ReadFile(filepath.Join(dir, "IdnaTestV2.json"))
	if err != nil {
		return nil, nil, err
	}
	toascii, err = os.ReadFile(filepath.Join(dir, "toascii.json"))
	if err != nil {
		return nil, nil, err
	}
	return idnaV2, toascii, nil
}

// writeFixtures writes both generated sources to their commonTest fixture paths.
func writeFixtures(dataSource, knownSource string) error {
	root, err := repo.Root()
	if err != nil {
		return err
	}
	dir := filepath.Join(root, "kuri", "src", "commonTest", "kotlin", "org", "dexpace", "kuri", "idna")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	if err := os.WriteFile(filepath.Join(dir, "IdnaConformanceData.kt"), []byte(dataSource), 0o644); err != nil {
		return err
	}
	return os.WriteFile(filepath.Join(dir, "IdnaConformanceKnownFailures.kt"), []byte(knownSource), 0o644)
}
