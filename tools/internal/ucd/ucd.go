// Copyright (c) 2026 dexpace and Omar Aljarrah
// SPDX-License-Identifier: MIT

// Package ucd parses the Unicode Character Database corpora the codegen
// generators consume and exposes the in-memory lookup tables the IDNA reference
// reads: semicolon-delimited records, code-point ranges, hex-scalar fields, and
// the merged mapping / label-validity / NFC tables (Unicode 16.0). It is the
// data layer; it imports nothing else in this module.
package ucd

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

// hexRadix is the base UCD scalar fields are written in.
const hexRadix = 16

// scalarBitSize bounds a parsed scalar; Unicode scalars never exceed 0x10FFFF,
// which fits comfortably in 32 bits and mirrors the Python ports' int(tok, 16).
const scalarBitSize = 32

// Unicode scalar-value bounds: a valid scalar lies in [0, maxScalar] and excludes
// the UTF-16 surrogate block [firstSurrogate, lastSurrogate]. ScalarsToString
// rejects anything outside this set so an invalid code point can never silently
// become U+FFFD via WriteRune.
const (
	maxScalar      = 0x10FFFF
	firstSurrogate = 0xD800
	lastSurrogate  = 0xDFFF
)

// rangeSeparator delimits the inclusive bounds of a UCD code-point range field,
// e.g. the ".." in "0000..002C".
const rangeSeparator = ".."

// scannerBufferSize caps a single scanned line. UCD lines are short, but the
// buffer is raised above bufio's 64 KB default defensively.
const scannerBufferSize = 1 << 20

// fieldCodePoint is the universal first (code-point) column of a UnicodeData.txt
// record, shared by the mark/virama and NFC passes.
const fieldCodePoint = 0

// rootMarker is the file that uniquely identifies the repository root. It lives
// only at the top level of the kuri checkout, so the first ancestor containing
// it is the root.
const rootMarker = "settings.gradle.kts"

// repoRoot walks up from the current working directory and returns the first
// ancestor directory that contains settings.gradle.kts, so the loaders resolve
// their inputs the same way whether invoked from Gradle (CWD = repo root) or
// from `cd tools && go run ...` (CWD = tools). It errors if no such directory
// exists between the CWD and the filesystem root.
func repoRoot() (string, error) {
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
			return "", errors.New("ucd: " + rootMarker + " not found in any parent of the working directory")
		}
		dir = parent
	}
}

// mappingTablePath returns the absolute path of the vendored IdnaMappingTable.txt.
func mappingTablePath() (string, error) {
	root, err := repoRoot()
	if err != nil {
		return "", err
	}
	return filepath.Join(
		root, ".claude", "references", "unicode-16.0", "IdnaMappingTable.txt",
	), nil
}

// unicodeDataPath returns the absolute path of the vendored UnicodeData.txt,
// shared by the label-validity and NFC passes.
func unicodeDataPath() (string, error) {
	root, err := repoRoot()
	if err != nil {
		return "", err
	}
	return filepath.Join(
		root, ".claude", "references", "unicode-16.0", "UnicodeData.txt",
	), nil
}

// joiningPath resolves DerivedJoiningType.txt, mirroring the Python
// first_existing: the extracted/ copy is preferred, the top-level file is the
// fallback. The fallback is what exists in this checkout.
func joiningPath() (string, error) {
	root, err := repoRoot()
	if err != nil {
		return "", err
	}
	dir := filepath.Join(root, ".claude", "references", "unicode-16.0")
	candidates := []string{
		filepath.Join(dir, "extracted", "DerivedJoiningType.txt"),
		filepath.Join(dir, "DerivedJoiningType.txt"),
	}
	for _, candidate := range candidates {
		if info, statErr := os.Stat(candidate); statErr == nil && !info.IsDir() {
			return candidate, nil
		}
	}
	return "", fmt.Errorf("ucd: none of these inputs exist: %v", candidates)
}

// exclusionsPath returns the absolute path of the vendored CompositionExclusions.txt.
func exclusionsPath() (string, error) {
	root, err := repoRoot()
	if err != nil {
		return "", err
	}
	return filepath.Join(root, ".claude", "references", "unicode-16.0", "CompositionExclusions.txt"), nil
}

// ScalarsToString parses a whitespace-separated list of hexadecimal Unicode
// scalar values — the encoding UCD files such as NormalizationTest.txt use for a
// code-point sequence (e.g. "0044 0307") — and returns the concatenated string.
//
// Whitespace handling matches Python's no-argument str.split: runs of whitespace
// are collapsed and empty tokens dropped, so an empty or all-whitespace field
// yields the empty string. Tokens are bare hex (no "0x" prefix); a token that is
// not valid hex, out of range, or a surrogate code point is returned as an error
// rather than silently skipped or folded to U+FFFD by WriteRune.
func ScalarsToString(field string) (string, error) {
	var builder strings.Builder
	for _, token := range strings.Fields(field) {
		code, err := strconv.ParseInt(token, hexRadix, scalarBitSize)
		if err != nil {
			return "", fmt.Errorf("ucd: parsing hex scalar %q: %w", token, err)
		}
		if code > maxScalar || (code >= firstSurrogate && code <= lastSurrogate) {
			return "", fmt.Errorf("ucd: %q is not a valid Unicode scalar value", token)
		}
		builder.WriteRune(rune(code))
	}
	return builder.String(), nil
}

// ParseCodeRange parses a UCD code-point range field — either a single hex
// scalar ("0041") or an inclusive "LO..HI" range ("0000..002C") — and returns
// the inclusive [lo, hi] bounds. For a single scalar lo == hi. A bound that is
// not valid hexadecimal yields an error, mirroring the Python ports' int(_, 16).
func ParseCodeRange(field string) (lo, hi int, err error) {
	if index := strings.Index(field, rangeSeparator); index >= 0 {
		if lo, err = parseScalar(field[:index]); err != nil {
			return 0, 0, err
		}
		if hi, err = parseScalar(field[index+len(rangeSeparator):]); err != nil {
			return 0, 0, err
		}
		return lo, hi, nil
	}
	scalar, err := parseScalar(field)
	if err != nil {
		return 0, 0, err
	}
	return scalar, scalar, nil
}

// parseScalar parses one bare hexadecimal Unicode scalar into an int, using the
// same radix and bit width as the other UCD field parsers.
func parseScalar(token string) (int, error) {
	code, err := strconv.ParseInt(token, hexRadix, scalarBitSize)
	if err != nil {
		return 0, fmt.Errorf("ucd: parsing hex scalar %q: %w", token, err)
	}
	return int(code), nil
}
