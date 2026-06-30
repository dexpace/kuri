// Copyright (c) 2026 dexpace and Omar Aljarrah
// SPDX-License-Identifier: MIT

package idnaref

import "github.com/dexpace/kuri/tools/internal/ucd"

// Bidi_Class values consulted by the RFC 5893 Bidi rule. A code point with no
// tabled class resolves to bidiNone, which satisfies no condition.
const (
	bidiNone = ""
	bidiL    = "L"
	bidiR    = "R"
	bidiAL   = "AL"
	bidiAN   = "AN"
	bidiEN   = "EN"
	bidiES   = "ES"
	bidiCS   = "CS"
	bidiET   = "ET"
	bidiON   = "ON"
	bidiBN   = "BN"
	bidiNSM  = "NSM"
)

// bidiTable resolves a code point's Bidi_Class via binary search over merged,
// start-sorted ranges, defaulting to bidiNone when untabled.
type bidiTable struct {
	starts  []int
	ends    []int
	classes []string
}

// newBidiTable builds the lookup from merged, start-sorted typed ranges.
func newBidiTable(ranges []ucd.TypedRange) *bidiTable {
	starts := make([]int, len(ranges))
	ends := make([]int, len(ranges))
	classes := make([]string, len(ranges))
	for index, current := range ranges {
		starts[index] = current.Start
		ends[index] = current.End
		classes[index] = current.Type
	}
	return &bidiTable{starts: starts, ends: ends, classes: classes}
}

// classOf returns the Bidi_Class of codePoint, or bidiNone when untabled.
func (b *bidiTable) classOf(codePoint int) string {
	index := floorIndex(b.starts, codePoint)
	if index >= 0 && codePoint <= b.ends[index] {
		return b.classes[index]
	}
	return bidiNone
}

// isRTLLabel reports whether a label contains at least one R, AL, or AN code
// point, making it subject to the Bidi rule (RFC 5893 §2).
func (r *Reference) isRTLLabel(codePoints []int) bool {
	for _, cp := range codePoints {
		switch r.bidi.classOf(cp) {
		case bidiR, bidiAL, bidiAN:
			return true
		}
	}
	return false
}

// lastNonNSM returns the index of the last code point whose Bidi_Class is not
// NSM, or -1 when every code point is NSM (an all-NSM label fails the rule).
func (r *Reference) lastNonNSM(codePoints []int) int {
	for i := len(codePoints) - 1; i >= 0; i-- {
		if r.bidi.classOf(codePoints[i]) != bidiNSM {
			return i
		}
	}
	return -1
}

// checkBidi ports ada's is_label_valid Bidi block (RFC 5893 §2). A label that
// contains no RTL code point is exempt; otherwise it must satisfy the six
// conditions, evaluated as LTR or RTL by the direction of its first code point.
func (r *Reference) checkBidi(codePoints []int) bool {
	if !r.isRTLLabel(codePoints) {
		return true
	}
	last := r.lastNonNSM(codePoints)
	if last < 0 {
		return false
	}
	if r.bidi.classOf(codePoints[0]) == bidiL {
		return r.bidiLTRValid(codePoints, last)
	}
	return r.bidiRTLValid(codePoints, last)
}

// bidiLTRValid enforces RFC 5893 conditions 1, 5, and 6 for an LTR label.
func (r *Reference) bidiLTRValid(codePoints []int, last int) bool {
	for i := 0; i <= last; i++ {
		switch r.bidi.classOf(codePoints[i]) {
		case bidiL, bidiEN, bidiES, bidiCS, bidiET, bidiON, bidiBN, bidiNSM:
		default:
			return false
		}
	}
	switch r.bidi.classOf(codePoints[last]) {
	case bidiL, bidiEN:
		return true
	default:
		return false
	}
}

// bidiRTLValid enforces RFC 5893 conditions 1, 2, 3, and 4 for an RTL label.
func (r *Reference) bidiRTLValid(codePoints []int, last int) bool {
	switch r.bidi.classOf(codePoints[0]) {
	case bidiR, bidiAL:
	default:
		return false
	}
	hasAN, hasEN := false, false
	for i := 0; i <= last; i++ {
		class := r.bidi.classOf(codePoints[i])
		if class == bidiEN {
			hasEN = true
		}
		if class == bidiAN {
			hasAN = true
		}
		if hasAN && hasEN {
			return false
		}
		switch class {
		case bidiR, bidiAL, bidiAN, bidiEN, bidiES, bidiCS, bidiET, bidiON, bidiBN, bidiNSM:
		default:
			return false
		}
		if i == last {
			switch class {
			case bidiR, bidiAL, bidiEN, bidiAN:
			default:
				return false
			}
		}
	}
	return true
}
