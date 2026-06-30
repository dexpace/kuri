// Copyright (c) 2026 dexpace and Omar Aljarrah
// SPDX-License-Identifier: MIT

package conformance

import (
	"github.com/dexpace/kuri/tools/internal/gen/idnamapping"
	"github.com/dexpace/kuri/tools/internal/gen/idnavalidity"
)

// UTS-46 mapping kind letters, matching IdnaMappingTable's runtime encoding.
const (
	kindValid      = "V"
	kindIgnored    = "I"
	kindDisallowed = "D"
	kindMapped     = "M"
	kindDeviation  = "Y"
)

// Idna/Punycode/validity boundaries shared across the reference.
const (
	nonASCIIMin       = 0x80 // first non-ASCII code point; a label with any >= this needs ACE
	labelSeparator    = uint16('.')
	acePrefixLength   = 4
	highSurrogateMin  = 0xD800
	highSurrogateLast = 0xDBFF
	lowSurrogateMin   = 0xDC00
	lowSurrogateLast  = 0xDFFF
	supplementaryBase = 0x10000
	surrogateShift    = 10
	lowSurrogateMask  = 0x3FF
)

// acePrefix is the lowercase "xn--" ACE prefix as UTF-16 units.
var acePrefix = []uint16{'x', 'n', '-', '-'}

// mappingTable resolves a code point's UTS-46 mapping via binary search over a
// gap-free, start-sorted range list, mirroring IdnaMappingTable.findRangeIndex.
type mappingTable struct {
	starts []int
	ranges []idnamapping.Range
}

// newMappingTable indexes the merged ranges by their start code points.
func newMappingTable(ranges []idnamapping.Range) *mappingTable {
	starts := make([]int, len(ranges))
	for index, current := range ranges {
		starts[index] = current.Start
	}
	return &mappingTable{starts: starts, ranges: ranges}
}

// lookup returns the mapping range covering codePoint.
func (m *mappingTable) lookup(codePoint int) idnamapping.Range {
	return m.ranges[floorIndex(m.starts, codePoint)]
}

// rangeSet is a sorted inclusive-range membership set (Mark / Virama tables).
type rangeSet struct {
	starts []int
	ends   []int
}

// newRangeSet builds a membership set from merged, start-sorted ranges.
func newRangeSet(ranges []idnavalidity.PlainRange) *rangeSet {
	starts := make([]int, len(ranges))
	ends := make([]int, len(ranges))
	for index, current := range ranges {
		starts[index] = current.Start
		ends[index] = current.End
	}
	return &rangeSet{starts: starts, ends: ends}
}

// contains reports whether codePoint falls inside one of the ranges.
func (s *rangeSet) contains(codePoint int) bool {
	index := floorIndex(s.starts, codePoint)
	return index >= 0 && codePoint <= s.ends[index]
}

// joiningTable maps a code point to its Joining_Type, defaulting to 'U'.
type joiningTable struct {
	starts []int
	ends   []int
	types  []byte
}

// newJoiningTable builds the lookup from merged, start-sorted typed ranges.
func newJoiningTable(ranges []idnavalidity.TypedRange) *joiningTable {
	starts := make([]int, len(ranges))
	ends := make([]int, len(ranges))
	types := make([]byte, len(ranges))
	for index, current := range ranges {
		starts[index] = current.Start
		ends[index] = current.End
		types[index] = current.Type[0]
	}
	return &joiningTable{starts: starts, ends: ends, types: types}
}

// typeOf returns the Joining_Type letter for codePoint, or 'U' when untabled.
func (j *joiningTable) typeOf(codePoint int) byte {
	index := floorIndex(j.starts, codePoint)
	if index >= 0 && codePoint <= j.ends[index] {
		return j.types[index]
	}
	return joinOther
}

// floorIndex returns the greatest index whose starts value is <= codePoint, or
// -1 when none is, matching the runtime binary search.
func floorIndex(starts []int, codePoint int) int {
	low, high, result := 0, len(starts)-1, -1
	for low <= high {
		mid := (low + high) >> 1
		if starts[mid] <= codePoint {
			result = mid
			low = mid + 1
		} else {
			high = mid - 1
		}
	}
	return result
}

// codePointsOf splits a UTF-16 sequence into code points, folding well-formed
// surrogate pairs and passing lone surrogates through as their unit value.
func codePointsOf(units []uint16) []int {
	out := make([]int, 0, len(units))
	for i := 0; i < len(units); i++ {
		high := units[i]
		if high >= highSurrogateMin && high <= highSurrogateLast &&
			i+1 < len(units) && units[i+1] >= lowSurrogateMin && units[i+1] <= lowSurrogateLast {
			out = append(out, toCodePoint(high, units[i+1]))
			i++
			continue
		}
		out = append(out, int(high))
	}
	return out
}

// firstCodePoint returns the first code point of a non-empty sequence, folding a
// leading surrogate pair.
func firstCodePoint(units []uint16) int {
	high := units[0]
	if high >= highSurrogateMin && high <= highSurrogateLast &&
		len(units) > 1 && units[1] >= lowSurrogateMin && units[1] <= lowSurrogateLast {
		return toCodePoint(high, units[1])
	}
	return int(high)
}

// toCodePoint folds a surrogate pair into a supplementary-plane code point.
func toCodePoint(high, low uint16) int {
	return supplementaryBase + ((int(high) - highSurrogateMin) << surrogateShift) + (int(low) - lowSurrogateMin)
}

// appendCodePoint appends codePoint to units, emitting a surrogate pair for a
// supplementary value and a single unit for a BMP value or lone surrogate.
func appendCodePoint(units []uint16, codePoint int) []uint16 {
	if codePoint <= 0xFFFF {
		return append(units, uint16(codePoint))
	}
	offset := codePoint - supplementaryBase
	return append(units,
		uint16(highSurrogateMin+(offset>>surrogateShift)),
		uint16(lowSurrogateMin+(offset&lowSurrogateMask)),
	)
}
