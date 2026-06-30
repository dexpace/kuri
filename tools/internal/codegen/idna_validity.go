// Copyright (c) 2026 dexpace and Omar Aljarrah
// SPDX-License-Identifier: MIT

// The idna-validity generator ports tools/idna/generate_idna_validity_tables.py:
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
//
// Hex starts/ends use uppercase no-pad %X; the only escape applied to a chunk is
// the record-separating newline, rendered as the literal lowercase six-byte
// sequence backslash-u-000a. The two casings are intentional and must not be
// unified.

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
	marks, viramas, joining, err := ucd.LoadValidity()
	if err != nil {
		return "", err
	}
	markChunks := validityChunkBlob(encodeSet(marks))
	viramaChunks := validityChunkBlob(encodeSet(viramas))
	joiningChunks := validityChunkBlob(encodeTyped(joining))
	return validityRender(markChunks, viramaChunks, joiningChunks), nil
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

// validityChunkBlob slices the blob at whole-escape-unit boundaries so each
// chunk's escaped form fits validityEscapedLineBudget columns. It iterates one
// code point at a time and greedily packs each escaped unit: break before
// appending when the running width would exceed the budget and the current chunk
// is non-empty, mirroring the Python chunk_blob exactly. The blob is ASCII, so the
// only multi-byte escape is the newline.
func validityChunkBlob(blob string) []string {
	var chunks []string
	var current strings.Builder
	width := 0
	for _, point := range blob {
		escaped := validityEscapeChar(point)
		if width+len(escaped) > validityEscapedLineBudget && current.Len() > 0 {
			chunks = append(chunks, current.String())
			current.Reset()
			width = 0
		}
		current.WriteString(escaped)
		width += len(escaped)
	}
	if current.Len() > 0 {
		chunks = append(chunks, current.String())
	}
	return chunks
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
// the plain block comment, then the three chunked-literal properties (Mark,
// Virama, Joining_Type) separated by single blank lines, terminated by one
// newline.
func validityRender(markChunks, viramaChunks, joiningChunks []string) string {
	lines := []string{
		LicenseHeader,
		"",
		"package org.dexpace.kuri.idna",
		"",
		"/*",
		" * Compact, generated label-validity range tables (Unicode 16.0.0).",
		" *",
		" * GENERATED by tools/idna/generate_idna_validity_tables.py from Unicode's",
		" * DerivedJoiningType.txt and UnicodeData.txt. Do not edit by hand; re-run the generator.",
		" *",
		" * Each blob concatenates to newline-joined inclusive-range records decoded by",
		" * [IdnaValidity]: <START>-<END> for the Mark/Virama sets and <START>-<END>;<TYPE>",
		" * for Joining_Type (SPEC 7.4; UTS-46 leading-combining-mark; RFC 5892 A.1/A.2).",
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
	return JoinLines(lines)
}
