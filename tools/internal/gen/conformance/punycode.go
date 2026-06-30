// Copyright (c) 2026 dexpace and Omar Aljarrah
// SPDX-License-Identifier: MIT

package conformance

// RFC 3492 Bootstring parameters, matching Punycode's constants. The codecs are a
// faithful port of kuri's Punycode (IgnoreInvalidPunycode = false): any malformed
// digit, non-ASCII basic code point, lone-surrogate scalar, or 32-bit overflow
// yields failure rather than a best-effort result.
const (
	punyBase     = 36
	punyTMin     = 1
	punyTMax     = 26
	punySkew     = 38
	punyDamp     = 700
	punyInitBias = 72
	punyInitN    = 0x80
	punyLetters  = 26
	punyDelim    = '-'
	punyMaxK     = punyBase * 64
	punyAdaptThr = ((punyBase - punyTMin) * punyTMax) / 2
	maxInt32     = 0x7FFFFFFF
	maxCodePoint = 0x10FFFF
)

// punycodeDecode decodes a label (without the xn-- prefix) into its Unicode code
// units, or ok=false on any malformed input, porting Punycode.decode.
func (r *reference) punycodeDecode(input []uint16) ([]uint16, bool) {
	basics, pos, ok := decodeBasics(input)
	if !ok {
		return nil, false
	}
	codePoints := append([]int{}, basics...)
	n, i, bias := punyInitN, 0, punyInitBias
	for pos < len(input) {
		oldI := i
		value, nextPos, stepOK := decodeInteger(input, pos, bias, i)
		if !stepOK {
			return nil, false
		}
		i, pos = value, nextPos
		outLen := len(codePoints) + 1
		bias = adapt(i-oldI, outLen, oldI == 0)
		next := int64(n) + int64(i/outLen)
		if next > maxCodePoint || (next >= highSurrogateMin && next <= lowSurrogateLast) {
			return nil, false
		}
		at := i % outLen
		codePoints = insertAt(codePoints, at, int(next))
		n = int(next)
		i = at + 1
	}
	out := []uint16{}
	for _, codePoint := range codePoints {
		out = appendCodePoint(out, codePoint)
	}
	return out, true
}

// decodeBasics consumes the basic-code-point prefix up to the last delimiter,
// failing if a non-ASCII code point appears before it.
func decodeBasics(input []uint16) (basics []int, nextPos int, ok bool) {
	last := -1
	for index, unit := range input {
		if unit == punyDelim {
			last = index
		}
	}
	if last < 0 {
		return nil, 0, true
	}
	for index := 0; index < last; index++ {
		if input[index] >= punyInitN {
			return nil, 0, false
		}
		basics = append(basics, int(input[index]))
	}
	return basics, last + 1, true
}

// decodeInteger folds one generalized variable-length integer from input at start
// into acc, returning the new accumulator and cursor, or ok=false on a bad digit,
// truncation, or overflow.
func decodeInteger(input []uint16, start, bias, acc int) (value, nextPos int, ok bool) {
	i, w, pos, k := acc, 1, start, punyBase
	terminated := false
	for k <= punyMaxK && !terminated {
		digit := -1
		if pos < len(input) {
			digit = codePointToDigit(input[pos])
		}
		t := threshold(k, bias)
		var next int64 = maxInt32 + 1
		if digit >= 0 {
			next = int64(i) + int64(digit)*int64(w)
		}
		switch {
		case digit < 0 || next > maxInt32:
			return 0, 0, false
		case digit < t:
			i, pos, terminated = int(next), pos+1, true
		case int64(w)*int64(punyBase-t) > maxInt32:
			return 0, 0, false
		default:
			i, pos, w, k = int(next), pos+1, w*(punyBase-t), k+punyBase
		}
	}
	if !terminated {
		return 0, 0, false
	}
	return i, pos, true
}

// punycodeEncode encodes a label's content to its Punycode form without the xn--
// prefix, porting Punycode.encode. An all-basic input is returned unchanged.
func (r *reference) punycodeEncode(input []uint16) ([]uint16, bool) {
	codePoints := codePointsOf(input)
	if !hasNonBasic(codePoints) {
		return input, true
	}
	out := []uint16{}
	basicCount := 0
	for _, codePoint := range codePoints {
		if codePoint < punyInitN {
			out = append(out, uint16(codePoint))
			basicCount++
		}
	}
	if basicCount > 0 {
		out = append(out, punyDelim)
	}
	return encodeSuffix(codePoints, out, basicCount)
}

// encodeSuffix runs the RFC 3492 main loop, appending the encoded suffix to out
// for each non-basic code point in ascending order, or failing on 32-bit delta
// overflow.
func encodeSuffix(codePoints []int, out []uint16, basicCount int) ([]uint16, bool) {
	n := punyInitN
	var delta int64
	bias := punyInitBias
	handled := basicCount
	for handled < len(codePoints) {
		m := minCodePointAtLeast(codePoints, n)
		delta += int64(m-n) * int64(handled+1)
		if delta > maxInt32 {
			return nil, false
		}
		n = m
		for _, codePoint := range codePoints {
			switch {
			case codePoint < n:
				if delta == maxInt32 {
					return nil, false
				}
				delta++
			case codePoint == n:
				out = encodeInteger(out, int(delta), bias)
				bias = adapt(int(delta), handled+1, handled == basicCount)
				delta = 0
				handled++
			}
		}
		delta++
		n++
	}
	return out, true
}

// encodeInteger emits delta as a Bootstring generalized variable-length integer.
func encodeInteger(out []uint16, delta, bias int) []uint16 {
	q := delta
	for k := punyBase; k <= punyMaxK; k += punyBase {
		t := threshold(k, bias)
		if q < t {
			return append(out, digitToCodePoint(q))
		}
		out = append(out, digitToCodePoint(t+(q-t)%(punyBase-t)))
		q = (q - t) / (punyBase - t)
	}
	return append(out, digitToCodePoint(q))
}

// adapt recomputes the bias from the most recent delta (RFC 3492 6.1).
func adapt(delta, numPoints int, firstTime bool) int {
	scaled := delta / 2
	if firstTime {
		scaled = delta / punyDamp
	}
	scaled += scaled / numPoints
	k := 0
	for scaled > punyAdaptThr && k < punyMaxK {
		scaled /= punyBase - punyTMin
		k += punyBase
	}
	return k + ((punyBase-punyTMin+1)*scaled)/(scaled+punySkew)
}

// threshold clamps the digit threshold t for weight k under bias to TMin..TMax.
func threshold(k, bias int) int {
	switch {
	case k <= bias:
		return punyTMin
	case k >= bias+punyTMax:
		return punyTMax
	default:
		return k - bias
	}
}

// digitToCodePoint maps a Bootstring digit 0..35 to its ASCII unit (a..z, 0..9).
func digitToCodePoint(digit int) uint16 {
	if digit < punyLetters {
		return uint16('a' + digit)
	}
	return uint16('0' + (digit - punyLetters))
}

// codePointToDigit maps an ASCII unit to its Bootstring digit, or -1.
func codePointToDigit(unit uint16) int {
	switch {
	case unit >= 'a' && unit <= 'z':
		return int(unit - 'a')
	case unit >= 'A' && unit <= 'Z':
		return int(unit - 'A')
	case unit >= '0' && unit <= '9':
		return int(unit-'0') + punyLetters
	default:
		return -1
	}
}

// hasNonBasic reports whether any code point is non-basic (>= 0x80).
func hasNonBasic(codePoints []int) bool {
	for _, codePoint := range codePoints {
		if codePoint >= punyInitN {
			return true
		}
	}
	return false
}

// minCodePointAtLeast returns the smallest code point that is >= n.
func minCodePointAtLeast(codePoints []int, n int) int {
	best := maxInt32
	for _, codePoint := range codePoints {
		if codePoint >= n && codePoint < best {
			best = codePoint
		}
	}
	return best
}

// insertAt inserts value at index, shifting the tail right.
func insertAt(slice []int, index, value int) []int {
	slice = append(slice, 0)
	copy(slice[index+1:], slice[index:])
	slice[index] = value
	return slice
}
