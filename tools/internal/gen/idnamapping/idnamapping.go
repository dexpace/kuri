// Copyright (c) 2026 dexpace and Omar Aljarrah
// SPDX-License-Identifier: MIT

// Package idnamapping ports tools/idna/generate_idna_mapping_table.py: it reads
// the vendored Unicode IdnaMappingTable.txt and materializes the compact UTS-46
// IDNA mapping-table data file (IdnaMappingTableData.kt) byte-for-byte.
//
// The UTS-46 parameter set baked in matches the Python generator:
// UseSTD3ASCIIRules = false (disallowed_STD3_valid -> VALID, disallowed_STD3_mapped
// -> MAPPED) and Transitional_Processing = false (deviation kept DISTINCT). After
// canonicalizing each line's status and merging adjacent ranges that share the
// same status (and, for MAPPED, the same replacement), each merged range is
// rendered to one `<startHexUpper> <kindLetter><replacement?>` record. The
// records are newline-joined into one blob and sliced into chunks whose escaped
// form stays within escapedLineBudget columns.
package idnamapping

import (
	"bufio"
	"bytes"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"

	"github.com/dexpace/kuri/tools/internal/kotlinlit"
	"github.com/dexpace/kuri/tools/internal/repo"
	"github.com/dexpace/kuri/tools/internal/ucd"
)

// Runtime status letters used by the decoder. Each IdnaMappingTable.txt status
// keyword canonicalizes to one of these under the baked-in UTS-46 parameter set.
const (
	kindValid      = "V"
	kindIgnored    = "I"
	kindDisallowed = "D"
	kindMapped     = "M"
	kindDeviation  = "Y"
)

// statusToKind maps each IdnaMappingTable.txt status keyword to a runtime kind
// under UseSTD3ASCIIRules = false, Transitional_Processing = false. The
// disallowed_STD3_* keywords do not occur in the Unicode 16.0 input but are kept
// for correctness against other releases.
var statusToKind = map[string]string{
	"valid":                  kindValid,
	"disallowed_STD3_valid":  kindValid,
	"ignored":                kindIgnored,
	"disallowed":             kindDisallowed,
	"mapped":                 kindMapped,
	"disallowed_STD3_mapped": kindMapped,
	"deviation":              kindDeviation,
}

// maxCodePoint is the last Unicode code point; the merged table must cover
// 0..maxCodePoint with no gaps or overlaps.
const maxCodePoint = 0x10FFFF

// escapedLineBudget caps each chunk's escaped width so the emitted string-literal
// line stays within the 120-column limit: 120 - 8 (indent) - 2 (quotes) - 1
// (comma) = 109; 100 leaves slack and never splits a 6-char \uXXXX escape.
const escapedLineBudget = 100

// Bounds and UTF-16 surrogate-encoding constants for escapeChar. Printable ASCII
// in [safeLiteralMin, safeLiteralMax] (minus the Kotlin-special chars) is emitted
// verbatim; BMP code points become a single \uXXXX; values above the BMP become a
// surrogate pair derived from these bounds.
const (
	safeLiteralMin     = 0x20
	safeLiteralMax     = 0x7E
	bmpMax             = 0xFFFF
	surrogateBase      = 0x10000
	highSurrogateMin   = 0xD800
	lowSurrogateMin    = 0xDC00
	surrogateHighShift = 10
	surrogateLowMask   = 0x3FF
)

// unicodeVersion is interpolated into the generated KDoc; bump it together with
// the source file when retargeting a different Unicode release.
const unicodeVersion = "16.0.0"

// scannerBufferSize caps a single scanned line. UCD lines are short, but the
// buffer is raised above bufio's 64 KB default defensively.
const scannerBufferSize = 1 << 20

// idnaRange is one inclusive code-point range with its canonicalized runtime
// kind and (for MAPPED only) replacement string.
type idnaRange struct {
	start       int
	end         int
	kind        string
	replacement string
}

// OutputPath returns the absolute path of the generated IdnaMappingTableData.kt.
func OutputPath() (string, error) {
	root, err := repo.Root()
	if err != nil {
		return "", err
	}
	return filepath.Join(
		root, "kuri", "src", "commonMain", "kotlin", "org", "dexpace",
		"kuri", "idna", "IdnaMappingTableData.kt",
	), nil
}

// inputPath returns the absolute path of the vendored IdnaMappingTable.txt.
func inputPath() (string, error) {
	root, err := repo.Root()
	if err != nil {
		return "", err
	}
	return filepath.Join(
		root, ".claude", "references", "unicode-16.0", "IdnaMappingTable.txt",
	), nil
}

// Generate reads the source table and returns the complete Kotlin source string.
func Generate() (string, error) {
	path, err := inputPath()
	if err != nil {
		return "", err
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return "", err
	}
	ranges, err := loadRanges(data)
	if err != nil {
		return "", err
	}
	merged := mergeAdjacent(ranges)
	blob := encodeRecords(merged)
	chunks := chunkBlob(blob)
	return render(chunks), nil
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

// Range is the exported form of one merged inclusive code-point range: its
// canonicalized UTS-46 kind ("V", "I", "D", "M", "Y") and, for "M", the mapped
// replacement. It lets the conformance reference reproduce IdnaMappingTable.map
// over the same Unicode 16.0 data this package encodes into the runtime table
// (verified byte-for-byte against IdnaMappingTableData.kt).
type Range struct {
	Start       int
	End         int
	Kind        string
	Replacement string
}

// LoadTable parses the vendored IdnaMappingTable.txt and returns the merged,
// gap-free ranges covering 0..0x10FFFF in start order — the same data the
// generated runtime table decodes, so a binary search over the starts yields the
// identical mapping outcome the kuri runtime computes.
func LoadTable() ([]Range, error) {
	path, err := inputPath()
	if err != nil {
		return nil, err
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	ranges, err := loadRanges(data)
	if err != nil {
		return nil, err
	}
	merged := mergeAdjacent(ranges)
	out := make([]Range, len(merged))
	for index, current := range merged {
		out[index] = Range{
			Start:       current.start,
			End:         current.end,
			Kind:        current.kind,
			Replacement: current.replacement,
		}
	}
	return out, nil
}

// loadRanges parses every data line, sorts the ranges by start, and validates
// that they cover 0..maxCodePoint with no gap or overlap, mirroring the Python
// load_ranges exactly.
func loadRanges(data []byte) ([]idnaRange, error) {
	scanner := bufio.NewScanner(bytes.NewReader(data))
	scanner.Buffer(make([]byte, 0, scannerBufferSize), scannerBufferSize)
	var ranges []idnaRange
	for scanner.Scan() {
		parsed, err := parseLine(scanner.Text())
		if err != nil {
			return nil, err
		}
		if parsed != nil {
			ranges = append(ranges, *parsed)
		}
	}
	if err := scanner.Err(); err != nil {
		return nil, fmt.Errorf("idnamapping: scanning table: %w", err)
	}

	sort.SliceStable(ranges, func(i, j int) bool { return ranges[i].start < ranges[j].start })

	expected := 0
	for _, current := range ranges {
		if current.start != expected {
			return nil, fmt.Errorf(
				"idnamapping: gap/overlap at U+%04X (expected U+%04X)", current.start, expected,
			)
		}
		expected = current.end + 1
	}
	if expected != maxCodePoint+1 {
		return nil, fmt.Errorf("idnamapping: coverage ends at U+%04X, not U+10FFFF", expected-1)
	}
	return ranges, nil
}

// parseLine parses one data line into a range, or nil for blank/comment lines.
// The replacement is only read for MAPPED; for every other kind (including
// DEVIATION) it stays empty, so deviation targets are discarded.
func parseLine(line string) (*idnaRange, error) {
	body := strings.TrimSpace(strings.SplitN(line, "#", 2)[0])
	if body == "" {
		return nil, nil
	}
	fields := strings.Split(body, ";")
	for index := range fields {
		fields[index] = strings.TrimSpace(fields[index])
	}

	statusTokens := strings.Fields(fields[1])
	if len(statusTokens) == 0 {
		return nil, fmt.Errorf("idnamapping: missing status in %q", body)
	}
	kind, ok := statusToKind[statusTokens[0]]
	if !ok {
		return nil, fmt.Errorf("idnamapping: unknown status %q", statusTokens[0])
	}

	start, end, err := ucd.ParseCodeRange(fields[0])
	if err != nil {
		return nil, err
	}

	replacement := ""
	if kind == kindMapped && len(fields) > 2 {
		replacement, err = ucd.ScalarsToString(fields[2])
		if err != nil {
			return nil, err
		}
	}
	return &idnaRange{start: start, end: end, kind: kind, replacement: replacement}, nil
}

// mergeAdjacent merges neighbouring ranges that share the same kind and
// replacement, extending the previous record's end rather than appending.
func mergeAdjacent(ranges []idnaRange) []idnaRange {
	merged := make([]idnaRange, 0, len(ranges))
	for _, current := range ranges {
		if last := len(merged) - 1; last >= 0 {
			prev := merged[last]
			if prev.kind == current.kind &&
				prev.replacement == current.replacement &&
				prev.end+1 == current.start {
				merged[last].end = current.end
				continue
			}
		}
		merged = append(merged, current)
	}
	return merged
}

// encodeRecords renders each merged range to a `<startHexUpper> <kind><rep>`
// record and joins them with a single newline into one blob. The start hex uses
// uppercase no-pad %X; this casing is distinct from the lowercase \uXXXX escapes
// applied later and must not be unified.
func encodeRecords(ranges []idnaRange) string {
	records := make([]string, len(ranges))
	for index, current := range ranges {
		records[index] = fmt.Sprintf("%X %s%s", current.start, current.kind, current.replacement)
	}
	return strings.Join(records, "\n")
}

// chunkBlob slices the blob at whole-escape-unit boundaries so each chunk's
// escaped form fits escapedLineBudget columns. It iterates one code point at a
// time and greedily packs each escaped unit: break before appending when the
// running width would exceed the budget and the current chunk is non-empty,
// mirroring the Python chunk_blob exactly.
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

// escapeChar escapes a single code point for a Kotlin string literal as pure
// ASCII, reproducing the Python generator's escape_char. Note this differs from
// the shared kotlinlit escaper for the three Kotlin-special chars '"', '\\' and
// '$': this generator routes them through the \uXXXX branch (e.g. '"' -> ")
// rather than emitting backslash escapes. Printable ASCII outside that set is
// emitted verbatim; BMP code points become a lowercase zero-padded \uXXXX; code
// points above the BMP become a UTF-16 surrogate pair of two escapes.
func escapeChar(point rune) string {
	code := int(point)
	if code >= safeLiteralMin && code <= safeLiteralMax &&
		point != '"' && point != '\\' && point != '$' {
		return string(point)
	}
	if code <= bmpMax {
		return fmt.Sprintf("\\u%04x", code)
	}
	offset := code - surrogateBase
	high := highSurrogateMin + (offset >> surrogateHighShift)
	low := lowSurrogateMin + (offset & surrogateLowMask)
	return fmt.Sprintf("\\u%04x\\u%04x", high, low)
}

// render builds the complete Kotlin source: license header, package, KDoc, and
// the single flat listOf of chunk string literals, terminated by exactly one
// newline. The KDoc carries the only non-ASCII byte in the file (the '§' in
// 'SPEC §7.4'); it is written verbatim, never run through the chunk escaper.
func render(chunks []string) string {
	lines := []string{
		kotlinlit.LicenseHeader,
		"",
		"package org.dexpace.kuri.idna",
		"",
		"/**",
		" * Compact, generated UTS-46 IDNA mapping table data (Unicode " + unicodeVersion + ").",
		" *",
		" * GENERATED by tools/idna/generate_idna_mapping_table.py from Unicode's",
		" * IdnaMappingTable.txt. Do not edit by hand; re-run the generator instead.",
		" *",
		" * The chunks concatenate into a newline-joined list of range records, each",
		" * `<startHex> <kindLetter><replacement?>`. See [IdnaMappingTable] for the",
		" * decoder and encoding contract (SPEC §7.4, [HOST-26]/[HOST-28]).",
		" */",
		"internal val IDNA_MAPPING_TABLE_CHUNKS: List<String> =",
		"    listOf(",
	}
	for _, chunk := range chunks {
		lines = append(lines, "        \""+chunk+"\",")
	}
	lines = append(lines, "    )")
	return kotlinlit.JoinLines(lines)
}
