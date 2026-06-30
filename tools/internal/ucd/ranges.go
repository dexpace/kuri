// Copyright (c) 2026 dexpace and Omar Aljarrah
// SPDX-License-Identifier: MIT

package ucd

import (
	"fmt"
	"strconv"
	"strings"
)

// rangeSeparator delimits the inclusive bounds of a UCD code-point range field,
// e.g. the ".." in "0000..002C".
const rangeSeparator = ".."

// ParseCodeRange parses a UCD code-point range field — either a single hex
// scalar ("0041") or an inclusive "LO..HI" range ("0000..002C") — and returns
// the inclusive [lo, hi] bounds. For a single scalar lo == hi. A bound that is
// not valid hexadecimal yields an error, mirroring the Python ports' int(_, 16).
func ParseCodeRange(field string) (lo, hi int, err error) {
	if index := strings.Index(field, rangeSeparator); index >= 0 {
		if lo, err = parseScalar(field[:index]); err != nil {
			return 0, 0, err
		}
		if hi, err = parseScalar(field[index+len(rangeSeparator):]); err != nil {
			return 0, 0, err
		}
		return lo, hi, nil
	}
	scalar, err := parseScalar(field)
	if err != nil {
		return 0, 0, err
	}
	return scalar, scalar, nil
}

// parseScalar parses one bare hexadecimal Unicode scalar into an int, using the
// same radix and bit width as the other UCD field parsers.
func parseScalar(token string) (int, error) {
	code, err := strconv.ParseInt(token, hexRadix, scalarBitSize)
	if err != nil {
		return 0, fmt.Errorf("ucd: parsing hex scalar %q: %w", token, err)
	}
	return int(code), nil
}
