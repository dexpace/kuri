// Copyright (c) 2026 dexpace and Omar Aljarrah
// SPDX-License-Identifier: MIT

// Package idnavalidity ports tools/idna/generate_idna_validity_tables.py: it
// reads two vendored Unicode UCD files and materializes the compact UTS-46
// label-validity data file (IdnaValidityData.kt) byte-for-byte.
//
// Three datasets are emitted as newline-joined inclusive-range record blobs,
// each sliced into Kotlin string-literal chunks:
//
//   - Mark:   General_Category in {Mn, Mc, Me} (UnicodeData.txt) -> <START>-<END>.
//   - Virama: Canonical_Combining_Class == 9 (UnicodeData.txt)   -> <START>-<END>.
//   - Joining_Type: types L/D/R/T only (DerivedJoiningType.txt)  -> <START>-<END>;<TYPE>.
//
// Hex starts/ends use uppercase no-pad %X; the only escape applied to a chunk is
// the record-separating newline, rendered as the literal lowercase six-byte
// sequence backslash-u-000a. The two casings are intentional and must not be
// unified.
package idnavalidity

import (
	"bufio"
	"bytes"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"

	"github.com/dexpace/kuri/tools/internal/kotlinlit"
	"github.com/dexpace/kuri/tools/internal/repo"
	"github.com/dexpace/kuri/tools/internal/ucd"
)

// viramaCCC is the Canonical_Combining_Class value that marks a Virama; the
// ContextJ Virama check keys off exactly this class.
const viramaCCC = 9

// escapedLineBudget caps each chunk's escaped width so the emitted string-literal
// line stays within the 120-column limit: 120 - 8 (indent) - 2 (quotes) - 1
// (comma) = 109; 100 leaves slack and never splits a 6-char escape across lines.
const escapedLineBudget = 100

// newlineEscape is the literal Kotlin escape the record-separating newline is
// rendered as inside a chunk: the six ASCII bytes backslash, u, 0, 0, 0, a. The
// lowercase hex here is deliberate and distinct from the uppercase data hex.
const newlineEscape = "\\u000a"

// scannerBufferSize caps a single scanned line. UCD lines are short, but the
// buffer is raised above bufio's 64 KB default defensively.
const scannerBufferSize = 1 << 20

// UnicodeData.txt field indices consumed by the Mark/Virama pass.
const (
	fieldCodePoint = 0
	fieldName      = 1
	fieldCategory  = 2
	fieldCCC       = 3
)

// markCategories is the General_Category set that makes a code point a Mark
// (the leading-combining-mark rule).
var markCategories = map[string]bool{"Mn": true, "Mc": true, "Me": true}

// joiningTypes is the Joining_Type set the RFC 5892 ContextJ rules consult;
// every other type (Non_Joining U, Join_Causing C) is omitted.
var joiningTypes = map[string]bool{"L": true, "D": true, "R": true, "T": true}

// plainRange is one inclusive code-point range for the Mark and Virama sets.
type plainRange struct {
	start int
	end   int
}

// typedRange is one inclusive code-point range carrying its Joining_Type letter.
type typedRange struct {
	start int
	end   int
	jtype string
}

// OutputPath returns the absolute path of the generated IdnaValidityData.kt.
func OutputPath() (string, error) {
	root, err := repo.Root()
	if err != nil {
		return "", err
	}
	return filepath.Join(
		root, "kuri", "src", "commonMain", "kotlin", "org", "dexpace",
		"kuri", "idna", "IdnaValidityData.kt",
	), nil
}

// unicodeDataPath returns the absolute path of the vendored UnicodeData.txt.
func unicodeDataPath() (string, error) {
	root, err := repo.Root()
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
	root, err := repo.Root()
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
	return "", fmt.Errorf("idnavalidity: none of these inputs exist: %v", candidates)
}

// Generate reads both UCD sources and returns the complete Kotlin source string.
func Generate() (string, error) {
	unicodePath, err := unicodeDataPath()
	if err != nil {
		return "", err
	}
	unicodeData, err := os.ReadFile(unicodePath)
	if err != nil {
		return "", err
	}
	marks, viramas, err := loadUnicodeData(unicodeData)
	if err != nil {
		return "", err
	}

	joinPath, err := joiningPath()
	if err != nil {
		return "", err
	}
	joinData, err := os.ReadFile(joinPath)
	if err != nil {
		return "", err
	}
	joining, err := loadJoining(joinData)
	if err != nil {
		return "", err
	}

	markChunks := chunkBlob(encodeSet(mergeSetRanges(marks)))
	viramaChunks := chunkBlob(encodeSet(mergeSetRanges(viramas)))
	joiningChunks := chunkBlob(encodeTyped(mergeTypedRanges(joining)))
	return render(markChunks, viramaChunks, joiningChunks), nil
}

// Run generates the fixture and either prints it to stdout or writes it to the
// fixture path, creating the parent directory if needed.
func Run(stdout bool) error {
	source, err := Generate()
	if err != nil {
		return err
	}
	if stdout {
		_, err := os.Stdout.WriteString(source)
		return err
	}
	out, err := OutputPath()
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(out), 0o755); err != nil {
		return err
	}
	return os.WriteFile(out, []byte(source), 0o644)
}

// PlainRange is the exported form of one merged inclusive Mark/Virama range.
type PlainRange struct {
	Start int
	End   int
}

// TypedRange is the exported form of one merged Joining_Type range, carrying its
// L/D/R/T type letter.
type TypedRange struct {
	Start int
	End   int
	Type  string
}

// LoadValidity parses the vendored UCD inputs and returns the merged Mark,
// Virama, and Joining_Type ranges (Unicode 16.0) in start order — the same data
// IdnaValidity decodes at runtime, so the conformance reference reproduces the
// leading-combining-mark and ContextJ checks exactly.
func LoadValidity() (marks, viramas []PlainRange, joining []TypedRange, err error) {
	unicodePath, err := unicodeDataPath()
	if err != nil {
		return nil, nil, nil, err
	}
	unicodeData, err := os.ReadFile(unicodePath)
	if err != nil {
		return nil, nil, nil, err
	}
	rawMarks, rawViramas, err := loadUnicodeData(unicodeData)
	if err != nil {
		return nil, nil, nil, err
	}
	joinPath, err := joiningPath()
	if err != nil {
		return nil, nil, nil, err
	}
	joinData, err := os.ReadFile(joinPath)
	if err != nil {
		return nil, nil, nil, err
	}
	rawJoining, err := loadJoining(joinData)
	if err != nil {
		return nil, nil, nil, err
	}
	return toPlain(mergeSetRanges(rawMarks)), toPlain(mergeSetRanges(rawViramas)), toTyped(mergeTypedRanges(rawJoining)), nil
}

// toPlain converts internal plain ranges to their exported form.
func toPlain(ranges []plainRange) []PlainRange {
	out := make([]PlainRange, len(ranges))
	for index, current := range ranges {
		out[index] = PlainRange{Start: current.start, End: current.end}
	}
	return out
}

// toTyped converts internal typed ranges to their exported form.
func toTyped(ranges []typedRange) []TypedRange {
	out := make([]TypedRange, len(ranges))
	for index, current := range ranges {
		out[index] = TypedRange{Start: current.start, End: current.end, Type: current.jtype}
	}
	return out
}

// loadUnicodeData parses the Mark and Virama (start, end) ranges from
// UnicodeData.txt, expanding <..., First>/<..., Last> blocks so the whole
// enclosed run is covered. It mirrors the Python load_unicode_data exactly,
// including the detail that the Last/current row's category and combining class
// (not the First row's, despite the Python docstring) decide membership; the two
// rows always agree, so this is observationally identical.
func loadUnicodeData(data []byte) (marks, viramas []plainRange, err error) {
	scanner := bufio.NewScanner(bytes.NewReader(data))
	scanner.Buffer(make([]byte, 0, scannerBufferSize), scannerBufferSize)
	pendingStart := -1
	for scanner.Scan() {
		fields := strings.Split(scanner.Text(), ";")
		codePoint, parseErr := strconv.ParseInt(fields[fieldCodePoint], 16, 32)
		if parseErr != nil {
			return nil, nil, fmt.Errorf("idnavalidity: parsing code point %q: %w", fields[fieldCodePoint], parseErr)
		}
		point := int(codePoint)
		name := fields[fieldName]
		category := fields[fieldCategory]
		ccc, parseErr := strconv.Atoi(fields[fieldCCC])
		if parseErr != nil {
			return nil, nil, fmt.Errorf("idnavalidity: parsing combining class %q: %w", fields[fieldCCC], parseErr)
		}
		if strings.HasSuffix(name, ", First>") {
			pendingStart = point
			continue
		}
		start := point
		if strings.HasSuffix(name, ", Last>") {
			start = pendingStart
		}
		pendingStart = -1
		if markCategories[category] {
			marks = append(marks, plainRange{start: start, end: point})
		}
		if ccc == viramaCCC {
			viramas = append(viramas, plainRange{start: start, end: point})
		}
	}
	if scanErr := scanner.Err(); scanErr != nil {
		return nil, nil, fmt.Errorf("idnavalidity: scanning UnicodeData.txt: %w", scanErr)
	}
	return marks, viramas, nil
}

// loadJoining parses the (start, end, type) ranges for Joining_Type in
// {L, D, R, T} in file order, mirroring the Python load_joining: drop everything
// after the first '#', strip, skip blanks, then split the remainder on exactly
// one ';' into a code-range token and a type letter.
func loadJoining(data []byte) ([]typedRange, error) {
	scanner := bufio.NewScanner(bytes.NewReader(data))
	scanner.Buffer(make([]byte, 0, scannerBufferSize), scannerBufferSize)
	var ranges []typedRange
	for scanner.Scan() {
		body := strings.TrimSpace(strings.SplitN(scanner.Text(), "#", 2)[0])
		if body == "" {
			continue
		}
		parts := strings.Split(body, ";")
		if len(parts) != 2 {
			return nil, fmt.Errorf("idnavalidity: expected one ';' in joining record %q", body)
		}
		token := strings.TrimSpace(parts[0])
		jtype := strings.TrimSpace(parts[1])
		if !joiningTypes[jtype] {
			continue
		}
		start, end, err := ucd.ParseCodeRange(token)
		if err != nil {
			return nil, err
		}
		ranges = append(ranges, typedRange{start: start, end: end, jtype: jtype})
	}
	if err := scanner.Err(); err != nil {
		return nil, fmt.Errorf("idnavalidity: scanning DerivedJoiningType.txt: %w", err)
	}
	return ranges, nil
}

// mergeSetRanges sorts plain ranges by (start, end) and adjacency-merges any
// pair that touches, overlaps, or is separated by a single code point.
func mergeSetRanges(ranges []plainRange) []plainRange {
	sort.Slice(ranges, func(i, j int) bool {
		if ranges[i].start != ranges[j].start {
			return ranges[i].start < ranges[j].start
		}
		return ranges[i].end < ranges[j].end
	})
	merged := make([]plainRange, 0, len(ranges))
	for _, current := range ranges {
		if last := len(merged) - 1; last >= 0 && current.start <= merged[last].end+1 {
			if current.end > merged[last].end {
				merged[last].end = current.end
			}
			continue
		}
		merged = append(merged, current)
	}
	return merged
}

// mergeTypedRanges sorts typed ranges by (start, end, type) and adjacency-merges
// only neighbours of the same type; differently-typed adjacent ranges stay
// separate records even when contiguous.
func mergeTypedRanges(ranges []typedRange) []typedRange {
	sort.Slice(ranges, func(i, j int) bool {
		if ranges[i].start != ranges[j].start {
			return ranges[i].start < ranges[j].start
		}
		if ranges[i].end != ranges[j].end {
			return ranges[i].end < ranges[j].end
		}
		return ranges[i].jtype < ranges[j].jtype
	})
	merged := make([]typedRange, 0, len(ranges))
	for _, current := range ranges {
		if last := len(merged) - 1; last >= 0 &&
			current.jtype == merged[last].jtype &&
			current.start <= merged[last].end+1 {
			if current.end > merged[last].end {
				merged[last].end = current.end
			}
			continue
		}
		merged = append(merged, current)
	}
	return merged
}

// encodeSet renders plain ranges to the newline-joined `<START>-<END>` blob,
// uppercase no-pad hex throughout.
func encodeSet(ranges []plainRange) string {
	records := make([]string, len(ranges))
	for index, current := range ranges {
		records[index] = fmt.Sprintf("%X-%X", current.start, current.end)
	}
	return strings.Join(records, "\n")
}

// encodeTyped renders typed ranges to the newline-joined `<START>-<END>;<TYPE>`
// blob, uppercase no-pad hex with the literal L/D/R/T type letter.
func encodeTyped(ranges []typedRange) string {
	records := make([]string, len(ranges))
	for index, current := range ranges {
		records[index] = fmt.Sprintf("%X-%X;%s", current.start, current.end, current.jtype)
	}
	return strings.Join(records, "\n")
}

// chunkBlob slices the blob at whole-escape-unit boundaries so each chunk's
// escaped form fits escapedLineBudget columns. It iterates one code point at a
// time and greedily packs each escaped unit: break before appending when the
// running width would exceed the budget and the current chunk is non-empty,
// mirroring the Python chunk_blob exactly. The blob is ASCII, so the only
// multi-byte escape is the newline.
func chunkBlob(blob string) []string {
	var chunks []string
	var current strings.Builder
	width := 0
	for _, point := range blob {
		escaped := escapeChar(point)
		if width+len(escaped) > escapedLineBudget && current.Len() > 0 {
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

// escapeChar renders one blob code point for a Kotlin string literal: the
// record-separating newline becomes newlineEscape; every other (ASCII hex
// digit, '-', ';', or type letter) byte is emitted verbatim.
func escapeChar(point rune) string {
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

// render assembles the complete Kotlin source: license header, package, the
// plain block comment, then the three chunked-literal properties (Mark, Virama,
// Joining_Type) separated by single blank lines, terminated by one newline.
func render(markChunks, viramaChunks, joiningChunks []string) string {
	lines := []string{
		kotlinlit.LicenseHeader,
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
	return kotlinlit.JoinLines(lines)
}
