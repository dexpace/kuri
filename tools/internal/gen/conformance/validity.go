// Copyright (c) 2026 dexpace and Omar Aljarrah
// SPDX-License-Identifier: MIT

package conformance

// RFC 5892 ContextJ join controls and Joining_Type letters, mirroring
// IdnaValidity's constants.
const (
	zwnj            = 0x200C // ZERO WIDTH NON-JOINER (RFC 5892 A.1)
	zwj             = 0x200D // ZERO WIDTH JOINER (RFC 5892 A.2)
	joinLeft        = 'L'
	joinDual        = 'D'
	joinRight       = 'R'
	joinTransparent = 'T'
	joinOther       = 'U'
)

// startsWithCombiningMark ports IdnaValidity.startsWithCombiningMark: a label is
// invalid when its first code point is a General_Category Mark. An empty label
// has no leading mark.
func (r *reference) startsWithCombiningMark(label []uint16) bool {
	if len(label) == 0 {
		return false
	}
	return r.marks.contains(firstCodePoint(label))
}

// checkJoiners ports IdnaValidity.checkJoiners: every ZWNJ/ZWJ must satisfy its
// RFC 5892 context; all other code points pass.
func (r *reference) checkJoiners(label []uint16) bool {
	codePoints := codePointsOf(label)
	for index := range codePoints {
		if !r.joinerContextValid(codePoints, index) {
			return false
		}
	}
	return true
}

// joinerContextValid dispatches the per-position ContextJ check.
func (r *reference) joinerContextValid(codePoints []int, index int) bool {
	switch codePoints[index] {
	case zwnj:
		return r.zeroWidthNonJoinerValid(codePoints, index)
	case zwj:
		return r.hasViramaBefore(codePoints, index)
	default:
		return true
	}
}

// zeroWidthNonJoinerValid implements RFC 5892 A.1: valid after a Virama, or
// within an L/D ... R/D joining context.
func (r *reference) zeroWidthNonJoinerValid(codePoints []int, index int) bool {
	if r.hasViramaBefore(codePoints, index) {
		return true
	}
	return r.precedingJoinerMatches(codePoints, index) && r.followingJoinerMatches(codePoints, index)
}

// hasViramaBefore reports whether the code point immediately before index is a
// Virama (Canonical_Combining_Class 9).
func (r *reference) hasViramaBefore(codePoints []int, index int) bool {
	return index > 0 && r.viramas.contains(codePoints[index-1])
}

// precedingJoinerMatches scans left over Transparent code points; the first
// non-Transparent must be Left- or Dual-joining.
func (r *reference) precedingJoinerMatches(codePoints []int, index int) bool {
	cursor := index - 1
	for cursor >= 0 && r.joining.typeOf(codePoints[cursor]) == joinTransparent {
		cursor--
	}
	if cursor < 0 {
		return false
	}
	t := r.joining.typeOf(codePoints[cursor])
	return t == joinLeft || t == joinDual
}

// followingJoinerMatches scans right over Transparent code points; the first
// non-Transparent must be Right- or Dual-joining.
func (r *reference) followingJoinerMatches(codePoints []int, index int) bool {
	cursor := index + 1
	for cursor < len(codePoints) && r.joining.typeOf(codePoints[cursor]) == joinTransparent {
		cursor++
	}
	if cursor >= len(codePoints) {
		return false
	}
	t := r.joining.typeOf(codePoints[cursor])
	return t == joinRight || t == joinDual
}
