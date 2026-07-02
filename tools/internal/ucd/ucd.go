// Copyright (c) 2026 dexpace and Omar Aljarrah
// SPDX-License-Identifier: MIT

// Package ucd parses the Unicode Character Database corpora the codegen
// generators consume and exposes the in-memory lookup tables the IDNA reference
// reads: semicolon-delimited records, code-point ranges, hex-scalar fields, and
// the merged mapping / label-validity / NFC tables (Unicode 17.0). It is the
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

// unicodeVersionDir is the single source of truth for the vendored Unicode
// release the generators read. Bumping the bundled UTS-46 / NFC tables to a new
// Unicode version is a one-line change here, paired with vendoring that release's
// UCD files under .claude/references/<unicodeVersionDir>/ and regenerating the
// embedded Kotlin tables. See docs/idna-unicode-update.md for the full procedure.
const unicodeVersionDir = "unicode-17.0"

// versionDirPrefix is the fixed prefix of unicodeVersionDir; the dotted Unicode
// version follows it, so BundledUnicodeVersion can recover the release from the
// same pin the loaders resolve their inputs against.
const versionDirPrefix = "unicode-"

// A well-formed version pin carries two dotted components (major.minor, with the
// patch implied 0) or three (major.minor.patch).
const (
	minVersionComponents = 2
	maxVersionComponents = 3
)

// UnicodeVersion is the bundled Unicode release recovered from unicodeVersionDir.
// It exists so the directory pin is the sole place a version bump edits: the
// generators both read unicode-<version>/ and stamp <version> into the headers
// they emit, so the two can no longer drift apart. Its two renderings match the
// two conventions the generated files already use.
type UnicodeVersion struct {
	major, minor, patch int
}

// MajorMinor renders "<major>.<minor>" (e.g. "17.0") — the form the NFC table
// header stamps.
func (v UnicodeVersion) MajorMinor() string {
	return fmt.Sprintf("%d.%d", v.major, v.minor)
}

// String renders the full "<major>.<minor>.<patch>" form (e.g. "17.0.0") — the
// form the IDNA mapping and label-validity headers stamp.
func (v UnicodeVersion) String() string {
	return fmt.Sprintf("%d.%d.%d", v.major, v.minor, v.patch)
}

// BundledUnicodeVersion recovers the bundled release from unicodeVersionDir, so
// the version strings stamped into generated headers derive from the same
// one-line pin the UCD loaders read. A pin that is not
// "unicode-<major>.<minor>[.<patch>]" with numeric components is a programmer
// error and yields an error rather than letting a bogus version reach generated
// code.
func BundledUnicodeVersion() (UnicodeVersion, error) {
	rest, ok := strings.CutPrefix(unicodeVersionDir, versionDirPrefix)
	if !ok {
		return UnicodeVersion{}, fmt.Errorf("ucd: version pin %q lacks the %q prefix", unicodeVersionDir, versionDirPrefix)
	}
	fields := strings.Split(rest, ".")
	if len(fields) < minVersionComponents || len(fields) > maxVersionComponents {
		return UnicodeVersion{}, fmt.Errorf("ucd: version pin %q is not unicode-<major>.<minor>[.<patch>]", unicodeVersionDir)
	}
	var components [maxVersionComponents]int
	for index, field := range fields {
		value, err := strconv.Atoi(field)
		// Require the canonical decimal form: strconv.Atoi also accepts a leading
		// '+' and non-canonical leading zeros ("07" -> 7), which would reconstruct
		// a version string that no longer matches the on-disk directory suffix. The
		// value < 0 arm is still needed because "-5" round-trips through Itoa.
		if err != nil || value < 0 || field != strconv.Itoa(value) {
			return UnicodeVersion{}, fmt.Errorf("ucd: version pin %q has a malformed component %q", unicodeVersionDir, field)
		}
		components[index] = value
	}
	return UnicodeVersion{major: components[0], minor: components[1], patch: components[2]}, nil
}

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
		root, ".claude", "references", unicodeVersionDir, "IdnaMappingTable.txt",
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
		root, ".claude", "references", unicodeVersionDir, "UnicodeData.txt",
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
	dir := filepath.Join(root, ".claude", "references", unicodeVersionDir)
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

// NormalizationTestPath returns the absolute path of the vendored
// NormalizationTest.txt for the bundled Unicode version ([unicodeVersionDir]),
// so the NFC-test fixture generator resolves it through the same version pin as
// every other UCD input.
func NormalizationTestPath() (string, error) {
	root, err := repoRoot()
	if err != nil {
		return "", err
	}
	return filepath.Join(root, ".claude", "references", unicodeVersionDir, "NormalizationTest.txt"), nil
}

// exclusionsPath returns the absolute path of the vendored CompositionExclusions.txt.
func exclusionsPath() (string, error) {
	root, err := repoRoot()
	if err != nil {
		return "", err
	}
	return filepath.Join(root, ".claude", "references", unicodeVersionDir, "CompositionExclusions.txt"), nil
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
