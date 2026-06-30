// Copyright (c) 2026 dexpace and Omar Aljarrah
// SPDX-License-Identifier: MIT

package ucd

import (
	"bufio"
	"bytes"
	"fmt"
	"os"
	"sort"
	"strings"
)

// Runtime status letters used by the decoder. Each IdnaMappingTable.txt status
// keyword canonicalizes to one of these under the baked-in UTS-46 parameter set
// (UseSTD3ASCIIRules = false, Transitional_Processing = false).
const (
	kindValid      = "V"
	kindIgnored    = "I"
	kindDisallowed = "D"
	kindMapped     = "M"
	kindDeviation  = "Y"
)

// statusToKind maps each IdnaMappingTable.txt status keyword to a runtime kind
// under UseSTD3ASCIIRules = false, Transitional_Processing = false. The
// disallowed_STD3_* keywords do not occur in the Unicode 17.0 input but are kept
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

// idnaRange is one inclusive code-point range with its canonicalized runtime
// kind and (for MAPPED only) replacement string.
type idnaRange struct {
	start       int
	end         int
	kind        string
	replacement string
}

// Range is the merged form of one inclusive code-point range: its canonicalized
// UTS-46 kind ("V", "I", "D", "M", "Y") and, for "M", the mapped replacement. It
// lets the conformance reference reproduce IdnaMappingTable.map over the same
// Unicode 17.0 data the runtime table encodes (verified byte-for-byte against
// IdnaMappingTableData.kt).
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
	path, err := mappingTablePath()
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
		return nil, fmt.Errorf("ucd: scanning mapping table: %w", err)
	}

	sort.SliceStable(ranges, func(i, j int) bool { return ranges[i].start < ranges[j].start })

	expected := 0
	for _, current := range ranges {
		if current.start != expected {
			return nil, fmt.Errorf(
				"ucd: gap/overlap at U+%04X (expected U+%04X)", current.start, expected,
			)
		}
		expected = current.end + 1
	}
	if expected != maxCodePoint+1 {
		return nil, fmt.Errorf("ucd: coverage ends at U+%04X, not U+10FFFF", expected-1)
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
		return nil, fmt.Errorf("ucd: missing status in %q", body)
	}
	kind, ok := statusToKind[statusTokens[0]]
	if !ok {
		return nil, fmt.Errorf("ucd: unknown status %q", statusTokens[0])
	}

	start, end, err := ParseCodeRange(fields[0])
	if err != nil {
		return nil, err
	}

	replacement := ""
	if kind == kindMapped && len(fields) > 2 {
		replacement, err = ScalarsToString(fields[2])
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
