// Copyright (c) 2026 dexpace and Omar Aljarrah
// SPDX-License-Identifier: MIT

package conformance

// Algorithmic Hangul composition/decomposition parameters (UAX #15), matching
// Normalizer's constants.
const (
	hangulSBase  = 0xAC00
	hangulLBase  = 0x1100
	hangulVBase  = 0x1161
	hangulTBase  = 0x11A7
	hangulLCount = 19
	hangulVCount = 21
	hangulTCount = 28
	hangulNCount = hangulVCount * hangulTCount
	hangulSCount = hangulLCount * hangulNCount
	cccStarter   = 0
)

// nfc ports Normalizer.nfc: full canonical decomposition, canonical ordering,
// then canonical composition. Empty input passes through; lone surrogates are
// treated as non-decomposing starters, as in the runtime.
func (r *reference) nfc(input []uint16) []uint16 {
	if len(input) == 0 {
		return input
	}
	decomposed := r.decomposeAll(input)
	r.canonicalOrder(decomposed)
	return r.composeAll(decomposed)
}

// decomposeAll fully canonically decomposes input into a flat code-point list.
func (r *reference) decomposeAll(input []uint16) []int {
	out := []int{}
	for _, codePoint := range codePointsOf(input) {
		out = r.decomposeCodePoint(codePoint, out)
	}
	return out
}

// decomposeCodePoint appends the full canonical decomposition of codePoint
// (recursive, plus algorithmic Hangul) to out.
func (r *reference) decomposeCodePoint(codePoint int, out []int) []int {
	if decomposed, ok := hangulDecompose(codePoint); ok {
		return append(out, decomposed...)
	}
	mapping, found := r.decompose[codePoint]
	if !found {
		return append(out, codePoint)
	}
	for _, part := range mapping {
		out = r.decomposeCodePoint(part, out)
	}
	return out
}

// canonicalOrder stably sorts each maximal combining run by combining class in
// place, mirroring Normalizer.canonicalOrder exactly (including its back-step).
func (r *reference) canonicalOrder(codePoints []int) {
	index := 1
	for index < len(codePoints) {
		current := r.combiningClass(codePoints[index])
		previous := r.combiningClass(codePoints[index-1])
		if current != cccStarter && previous != cccStarter && previous > current {
			codePoints[index], codePoints[index-1] = codePoints[index-1], codePoints[index]
			if index > 1 {
				index--
			} else {
				index++
			}
			continue
		}
		index++
	}
}

// composeAll canonically composes the ordered code points back into UTF-16,
// mirroring Normalizer.compose (table + Hangul, blocked-starter rule).
func (r *reference) composeAll(codePoints []int) []uint16 {
	starterIndex := 0
	starter := codePoints[0]
	lastClass := cccStarter
	index := 1
	for index < len(codePoints) {
		current := codePoints[index]
		currentClass := r.combiningClass(current)
		composed := -1
		if notBlocked(lastClass, currentClass) {
			composed = r.composeStarter(starter, current)
		}
		if composed >= 0 {
			starter = composed
			codePoints[starterIndex] = composed
			codePoints = append(codePoints[:index], codePoints[index+1:]...)
			continue
		}
		lastClass = currentClass
		if currentClass == cccStarter {
			starterIndex = index
			starter = current
		}
		index++
	}
	out := []uint16{}
	for _, codePoint := range codePoints {
		out = appendCodePoint(out, codePoint)
	}
	return out
}

// composeStarter composes starter with combining via Hangul or the primary
// composite table, returning -1 when not composable.
func (r *reference) composeStarter(starter, combining int) int {
	if hangul := hangulCompose(starter, combining); hangul >= 0 {
		return hangul
	}
	if composed, ok := r.compose[[2]int{starter, combining}]; ok {
		return composed
	}
	return -1
}

// combiningClass returns codePoint's combining class, zero for any starter.
func (r *reference) combiningClass(codePoint int) int {
	if class, ok := r.ccc[codePoint]; ok {
		return class
	}
	return cccStarter
}

// hangulDecompose decomposes a Hangul syllable algorithmically; ok=false for a
// non-syllable.
func hangulDecompose(codePoint int) ([]int, bool) {
	syllable := codePoint - hangulSBase
	if syllable < 0 || syllable >= hangulSCount {
		return nil, false
	}
	out := []int{
		hangulLBase + syllable/hangulNCount,
		hangulVBase + syllable%hangulNCount/hangulTCount,
	}
	if trailing := syllable % hangulTCount; trailing != 0 {
		out = append(out, hangulTBase+trailing)
	}
	return out, true
}

// hangulCompose composes an L+V or LV+T Hangul pair algorithmically; returns -1
// when not composable.
func hangulCompose(starter, combining int) int {
	leading := starter - hangulLBase
	if leading >= 0 && leading < hangulLCount {
		vowel := combining - hangulVBase
		if vowel >= 0 && vowel < hangulVCount {
			return hangulSBase + (leading*hangulVCount+vowel)*hangulTCount
		}
		return -1
	}
	syllable := starter - hangulSBase
	isLeadingVowel := syllable >= 0 && syllable < hangulSCount && syllable%hangulTCount == 0
	trailing := combining - hangulTBase
	if isLeadingVowel && trailing >= 1 && trailing < hangulTCount {
		return starter + trailing
	}
	return -1
}

// notBlocked reports whether a combining character of currentClass is reachable
// by the active starter (UAX #15 blocked-starter rule).
func notBlocked(lastClass, currentClass int) bool {
	return lastClass == cccStarter || lastClass < currentClass
}
