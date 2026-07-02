// Copyright (c) 2026 dexpace and Omar Aljarrah
// SPDX-License-Identifier: MIT

// The idna-validity generator ports the former Python generator:
// it renders the merged label-validity ranges (loaded by ucd from two vendored
// Unicode UCD files) into the compact UTS-46 label-validity data file
// (IdnaValidityData.kt) byte-for-byte.
//
// Three datasets are emitted as newline-joined inclusive-range record blobs,
// each sliced into Kotlin string-literal chunks:
//
//   - Mark:   General_Category in {Mn, Mc, Me}                 -> <START>-<END>.
//   - Virama: Canonical_Combining_Class == 9                   -> <START>-<END>.
//   - Joining_Type: types L/D/R/T only                         -> <START>-<END>;<TYPE>.
//   - Bidi_Class: classes the RFC 5893 rule consults           -> <START>-<END>;<CODE>.
//
// Hex starts/ends use uppercase no-pad %X; the only escape applied to a chunk is
// the record-separating newline, rendered as the literal lowercase six-byte
// sequence backslash-u-000a. The two casings are intentional and must not be
// unified. Each multi-letter Bidi_Class is folded to a single record code (see
// [bidiClassCode]) so the blob reuses the one-char Joining_Type record layout.

package codegen

import (
	"fmt"
	"strings"

	"github.com/dexpace/kuri/tools/internal/ucd"
)

// validityEscapedLineBudget caps each chunk's escaped width so the emitted
// string-literal line stays within the 120-column limit: 120 - 8 (indent) - 2
// (quotes) - 1 (comma) = 109; 100 leaves slack and never splits a 6-char escape
// across lines.
const validityEscapedLineBudget = 100

// newlineEscape is the literal Kotlin escape the record-separating newline is
// rendered as inside a chunk: the six ASCII bytes backslash, u, 0, 0, 0, a. The
// lowercase hex here is deliberate and distinct from the uppercase data hex.
const newlineEscape = "\\u000a"

// generateIdnaValidity loads the merged ranges and returns the complete Kotlin
// source string.
func generateIdnaValidity() (string, error) {
	version, err := ucd.BundledUnicodeVersion()
	if err != nil {
		return "", err
	}
	marks, viramas, joining, err := ucd.LoadValidity()
	if err != nil {
		return "", err
	}
	bidi, err := ucd.LoadBidi()
	if err != nil {
		return "", err
	}
	bidiBlob, err := encodeBidi(bidi)
	if err != nil {
		return "", err
	}
	markChunks := ChunkBlobByEscape(encodeSet(marks), validityEscapedLineBudget, validityEscapeChar)
	viramaChunks := ChunkBlobByEscape(encodeSet(viramas), validityEscapedLineBudget, validityEscapeChar)
	joiningChunks := ChunkBlobByEscape(encodeTyped(joining), validityEscapedLineBudget, validityEscapeChar)
	bidiChunks := ChunkBlobByEscape(bidiBlob, validityEscapedLineBudget, validityEscapeChar)
	return validityRender(version.String(), markChunks, viramaChunks, joiningChunks, bidiChunks), nil
}

// bidiClassCode folds a multi-letter Bidi_Class to the single record code shared
// by the Go generator and IdnaValidity's decoder, keeping the blob's one-char
// Joining_Type record layout. The eleven classes the RFC 5893 rule consults each
// get a distinct code; any other class is a generator bug (unreachable in a valid
// label) and is reported rather than silently mis-encoded.
func bidiClassCode(class string) (byte, bool) {
	codes := map[string]byte{
		"L": 'L', "R": 'R', "AL": 'A', "AN": 'N', "EN": 'E', "ES": 'S',
		"ET": 'T', "CS": 'C', "NSM": 'M', "BN": 'B', "ON": 'O',
	}
	code, ok := codes[class]
	return code, ok
}

// encodeBidi renders Bidi_Class ranges to the newline-joined `<START>-<END>;<CODE>`
// blob, uppercase no-pad hex with each class folded to its single record code.
func encodeBidi(ranges []ucd.TypedRange) (string, error) {
	records := make([]string, len(ranges))
	for index, current := range ranges {
		code, ok := bidiClassCode(current.Type)
		if !ok {
			return "", fmt.Errorf("codegen: unexpected Bidi_Class %q", current.Type)
		}
		records[index] = fmt.Sprintf("%X-%X;%c", current.Start, current.End, code)
	}
	return strings.Join(records, "\n"), nil
}

// encodeSet renders plain ranges to the newline-joined `<START>-<END>` blob,
// uppercase no-pad hex throughout.
func encodeSet(ranges []ucd.PlainRange) string {
	records := make([]string, len(ranges))
	for index, current := range ranges {
		records[index] = fmt.Sprintf("%X-%X", current.Start, current.End)
	}
	return strings.Join(records, "\n")
}

// encodeTyped renders typed ranges to the newline-joined `<START>-<END>;<TYPE>`
// blob, uppercase no-pad hex with the literal L/D/R/T type letter.
func encodeTyped(ranges []ucd.TypedRange) string {
	records := make([]string, len(ranges))
	for index, current := range ranges {
		records[index] = fmt.Sprintf("%X-%X;%s", current.Start, current.End, current.Type)
	}
	return strings.Join(records, "\n")
}

// validityEscapeChar renders one blob code point for a Kotlin string literal: the
// record-separating newline becomes newlineEscape; every other (ASCII hex
// digit, '-', ';', or type letter) byte is emitted verbatim.
func validityEscapeChar(point rune) string {
	if point == '\n' {
		return newlineEscape
	}
	return string(point)
}

// renderProperty renders one `internal val <name>: List<String>` chunked-literal
// property: a KDoc line, the declaration, `listOf(`, the 8-space-indented quoted
// chunks (trailing comma on every chunk including the last), then `    )`.
func renderProperty(name, kdoc string, chunks []string) []string {
	lines := make([]string, 0, len(chunks)+4)
	lines = append(lines,
		"/** "+kdoc+" */",
		"internal val "+name+": List<String> =",
		"    listOf(",
	)
	for _, chunk := range chunks {
		lines = append(lines, "        \""+chunk+"\",")
	}
	lines = append(lines, "    )")
	return lines
}

// validityRender assembles the complete Kotlin source: license header, package,
// the plain block comment, then the four chunked-literal properties (Mark,
// Virama, Joining_Type, Bidi_Class) separated by single blank lines, terminated
// by one newline.
func validityRender(version string, markChunks, viramaChunks, joiningChunks, bidiChunks []string) string {
	lines := []string{
		LicenseHeader,
		"",
		"package org.dexpace.kuri.idna",
		"",
		"/*",
		" * Compact, generated label-validity range tables (Unicode " + version + ").",
		" *",
		" * GENERATED by ./gradlew generateIdnaValidityTables from Unicode's",
		" * DerivedJoiningType.txt and UnicodeData.txt. Do not edit by hand; re-run the generator.",
		" *",
		" * Each blob concatenates to newline-joined inclusive-range records decoded by",
		" * [IdnaValidity]: <START>-<END> for the Mark/Virama sets, <START>-<END>;<TYPE> for",
		" * Joining_Type, and <START>-<END>;<CODE> for Bidi_Class (SPEC 7.4; UTS-46",
		" * leading-combining-mark; RFC 5892 A.1/A.2; RFC 5893 Bidi rule).",
		" */",
		"",
	}
	lines = append(lines, renderProperty(
		"MARK_RANGE_CHUNKS",
		"General_Category Mark (Mn/Mc/Me) code-point ranges; the leading-combining-mark rule.",
		markChunks,
	)...)
	lines = append(lines, "")
	lines = append(lines, renderProperty(
		"VIRAMA_RANGE_CHUNKS",
		"Canonical_Combining_Class == 9 (Virama) code-point ranges; ContextJ Virama check.",
		viramaChunks,
	)...)
	lines = append(lines, "")
	lines = append(lines, renderProperty(
		"JOINING_TYPE_CHUNKS",
		"Joining_Type ranges (types L/D/R/T only); RFC 5892 ContextJ ZWNJ join context.",
		joiningChunks,
	)...)
	lines = append(lines, "")
	lines = append(lines, renderProperty(
		"BIDI_CLASS_CHUNKS",
		"Bidi_Class ranges (L/R/AL/AN/EN/ES/ET/CS/NSM/BN/ON, folded to a one-char code); "+
			"RFC 5893 Bidi rule.",
		bidiChunks,
	)...)
	return JoinLines(lines)
}
