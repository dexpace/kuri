// Copyright (c) 2026 dexpace and Omar Aljarrah
// SPDX-License-Identifier: MIT

package ucd

import (
	"bufio"
	"bytes"
	"fmt"
	"os"
	"sort"
	"strconv"
	"strings"
)

// viramaCCC is the Canonical_Combining_Class value that marks a Virama; the
// ContextJ Virama check keys off exactly this class.
const viramaCCC = 9

// UnicodeData.txt field indices consumed by the Mark/Virama pass (fieldCodePoint
// is shared with the NFC pass).
const (
	fieldName     = 1
	fieldCategory = 2
	fieldCCC      = 3
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

// PlainRange is the merged form of one inclusive Mark/Virama range.
type PlainRange struct {
	Start int
	End   int
}

// TypedRange is the merged form of one Joining_Type range, carrying its L/D/R/T
// type letter.
type TypedRange struct {
	Start int
	End   int
	Type  string
}

// LoadValidity parses the vendored UCD inputs and returns the merged Mark,
// Virama, and Joining_Type ranges (Unicode 17.0) in start order — the same data
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
	rawMarks, rawViramas, err := loadValidityUnicodeData(unicodeData)
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

// toPlain converts internal plain ranges to their merged form.
func toPlain(ranges []plainRange) []PlainRange {
	out := make([]PlainRange, len(ranges))
	for index, current := range ranges {
		out[index] = PlainRange{Start: current.start, End: current.end}
	}
	return out
}

// toTyped converts internal typed ranges to their merged form.
func toTyped(ranges []typedRange) []TypedRange {
	out := make([]TypedRange, len(ranges))
	for index, current := range ranges {
		out[index] = TypedRange{Start: current.start, End: current.end, Type: current.jtype}
	}
	return out
}

// loadValidityUnicodeData parses the Mark and Virama (start, end) ranges from
// UnicodeData.txt, expanding <..., First>/<..., Last> blocks so the whole
// enclosed run is covered. It mirrors the Python load_unicode_data exactly,
// including the detail that the Last/current row's category and combining class
// (not the First row's, despite the Python docstring) decide membership; the two
// rows always agree, so this is observationally identical.
func loadValidityUnicodeData(data []byte) (marks, viramas []plainRange, err error) {
	scanner := bufio.NewScanner(bytes.NewReader(data))
	scanner.Buffer(make([]byte, 0, scannerBufferSize), scannerBufferSize)
	pendingStart := -1
	for scanner.Scan() {
		fields := strings.Split(scanner.Text(), ";")
		codePoint, parseErr := strconv.ParseInt(fields[fieldCodePoint], 16, 32)
		if parseErr != nil {
			return nil, nil, fmt.Errorf("ucd: parsing code point %q: %w", fields[fieldCodePoint], parseErr)
		}
		point := int(codePoint)
		name := fields[fieldName]
		category := fields[fieldCategory]
		ccc, parseErr := strconv.Atoi(fields[fieldCCC])
		if parseErr != nil {
			return nil, nil, fmt.Errorf("ucd: parsing combining class %q: %w", fields[fieldCCC], parseErr)
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
		return nil, nil, fmt.Errorf("ucd: scanning UnicodeData.txt: %w", scanErr)
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
			return nil, fmt.Errorf("ucd: expected one ';' in joining record %q", body)
		}
		token := strings.TrimSpace(parts[0])
		jtype := strings.TrimSpace(parts[1])
		if !joiningTypes[jtype] {
			continue
		}
		start, end, err := ParseCodeRange(token)
		if err != nil {
			return nil, err
		}
		ranges = append(ranges, typedRange{start: start, end: end, jtype: jtype})
	}
	if err := scanner.Err(); err != nil {
		return nil, fmt.Errorf("ucd: scanning DerivedJoiningType.txt: %w", err)
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
