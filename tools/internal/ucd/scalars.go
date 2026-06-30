// Copyright (c) 2026 dexpace and Omar Aljarrah
// SPDX-License-Identifier: MIT

package ucd

import (
	"fmt"
	"strconv"
	"strings"
)

// hexRadix is the base UCD scalar fields are written in.
const hexRadix = 16

// scalarBitSize bounds a parsed scalar; Unicode scalars never exceed 0x10FFFF,
// which fits comfortably in 32 bits and mirrors the Python ports' int(tok, 16).
const scalarBitSize = 32

// ScalarsToString parses a whitespace-separated list of hexadecimal Unicode
// scalar values — the encoding UCD files such as NormalizationTest.txt use for a
// code-point sequence (e.g. "0044 0307") — and returns the concatenated string.
//
// Whitespace handling matches Python's no-argument str.split: runs of whitespace
// are collapsed and empty tokens dropped, so an empty or all-whitespace field
// yields the empty string. Tokens are bare hex (no "0x" prefix); a token that is
// not valid hex is returned as an error rather than silently skipped.
func ScalarsToString(field string) (string, error) {
	var builder strings.Builder
	for _, token := range strings.Fields(field) {
		code, err := strconv.ParseInt(token, hexRadix, scalarBitSize)
		if err != nil {
			return "", fmt.Errorf("ucd: parsing hex scalar %q: %w", token, err)
		}
		builder.WriteRune(rune(code))
	}
	return builder.String(), nil
}
