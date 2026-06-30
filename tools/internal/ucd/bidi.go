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

// fieldBidi is the Bidi_Class column (5th field) of a UnicodeData.txt record.
const fieldBidi = 4

// bidiClasses is the Bidi_Class set the RFC 5893 Bidi rule consults. Every other
// class (paragraph/segment separators, whitespace, and the explicit-formatting
// controls) is omitted: such code points are never valid in a label, so they
// cannot reach the Bidi check, and recording them would only bloat the table.
var bidiClasses = map[string]bool{
	"L": true, "R": true, "AL": true, "EN": true, "ES": true, "ET": true,
	"AN": true, "CS": true, "NSM": true, "BN": true, "ON": true,
}

// LoadBidi parses the Bidi_Class of every assigned code point from the vendored
// UnicodeData.txt (Unicode 17.0), expanding <..., First>/<..., Last> blocks, and
// returns the merged class ranges in start order. Only the classes the RFC 5893
// Bidi rule consults are retained (see [bidiClasses]); a code point absent from
// the result has no Bidi class the rule cares about. Every valid label code point
// is assigned, hence present here, so the conformance reference resolves the same
// directions kuri does at runtime.
func LoadBidi() ([]TypedRange, error) {
	path, err := unicodeDataPath()
	if err != nil {
		return nil, err
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	raw, err := loadBidiUnicodeData(data)
	if err != nil {
		return nil, err
	}
	return toTyped(mergeTypedRanges(raw)), nil
}

// loadBidiUnicodeData parses the (start, end, class) Bidi ranges from
// UnicodeData.txt, expanding First/Last blocks so an entire enclosed run carries
// the class of its bounding rows (which always agree), mirroring the Mark/Virama
// pass's range handling.
func loadBidiUnicodeData(data []byte) ([]typedRange, error) {
	scanner := bufio.NewScanner(bytes.NewReader(data))
	scanner.Buffer(make([]byte, 0, scannerBufferSize), scannerBufferSize)
	pendingStart := -1
	var ranges []typedRange
	for scanner.Scan() {
		fields := strings.Split(scanner.Text(), ";")
		if len(fields) <= fieldBidi {
			continue
		}
		codePoint, parseErr := strconv.ParseInt(fields[fieldCodePoint], hexRadix, scalarBitSize)
		if parseErr != nil {
			return nil, fmt.Errorf("ucd: parsing code point %q: %w", fields[fieldCodePoint], parseErr)
		}
		point := int(codePoint)
		name := fields[fieldName]
		class := fields[fieldBidi]
		if strings.HasSuffix(name, ", First>") {
			pendingStart = point
			continue
		}
		start := point
		if strings.HasSuffix(name, ", Last>") {
			start = pendingStart
		}
		pendingStart = -1
		if bidiClasses[class] {
			ranges = append(ranges, typedRange{start: start, end: point, jtype: class})
		}
	}
	if err := scanner.Err(); err != nil {
		return nil, fmt.Errorf("ucd: scanning UnicodeData.txt for Bidi_Class: %w", err)
	}
	sort.SliceStable(ranges, func(i, j int) bool { return ranges[i].start < ranges[j].start })
	return ranges, nil
}
