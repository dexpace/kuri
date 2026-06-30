// Copyright (c) 2026 dexpace and Omar Aljarrah
// SPDX-License-Identifier: MIT

// Shared JSON-string decoding for the corpus generators. encoding/json replaces
// an unpaired surrogate with U+FFFD when decoding into a Go string; the WPT
// corpora deliberately include lone-surrogate test inputs, so the conformance and
// URL generators decode to UTF-16 code units through decodeJSONString to keep
// those inputs intact.

package codegen

import (
	"encoding/json"
	"fmt"
	"strings"
	"unicode/utf16"
	"unicode/utf8"
)

// isJSONNull reports whether a raw JSON token is the literal null.
func isJSONNull(raw json.RawMessage) bool {
	return strings.TrimSpace(string(raw)) == "null"
}

// decodeJSONString decodes a raw JSON string token into a UTF-16 code-unit
// sequence WITHOUT Go's default lone-surrogate replacement. Each \uHHHH escape
// becomes exactly one code unit (a surrogate, lone or paired, is preserved), the
// short escapes map to their control byte, and literal UTF-8 runs are decoded and
// re-encoded to UTF-16. This is what keeps the lone-surrogate corpus inputs
// intact, where encoding/json would substitute U+FFFD.
func decodeJSONString(raw json.RawMessage) ([]uint16, error) {
	body := strings.TrimSpace(string(raw))
	if len(body) < 2 || body[0] != '"' || body[len(body)-1] != '"' {
		return nil, fmt.Errorf("codegen: not a JSON string token: %q", string(raw))
	}
	body = body[1 : len(body)-1]
	var units []uint16
	for i := 0; i < len(body); {
		c := body[i]
		if c != '\\' {
			r, size := utf8.DecodeRuneInString(body[i:])
			if r == utf8.RuneError && size <= 1 {
				return nil, fmt.Errorf("codegen: invalid UTF-8 in JSON string %q", string(raw))
			}
			units = utf16.AppendRune(units, r)
			i += size
			continue
		}
		if i+1 >= len(body) {
			return nil, fmt.Errorf("codegen: dangling backslash in JSON string %q", string(raw))
		}
		esc := body[i+1]
		if esc == 'u' {
			if i+6 > len(body) {
				return nil, fmt.Errorf("codegen: truncated \\u escape in %q", string(raw))
			}
			value, err := parseHex4(body[i+2 : i+6])
			if err != nil {
				return nil, err
			}
			units = append(units, value)
			i += 6
			continue
		}
		mapped, ok := shortEscape(esc)
		if !ok {
			return nil, fmt.Errorf("codegen: unknown escape \\%c in %q", esc, string(raw))
		}
		units = append(units, uint16(mapped))
		i += 2
	}
	return units, nil
}

// shortEscape maps a JSON two-character escape's second byte to its literal byte.
func shortEscape(esc byte) (byte, bool) {
	switch esc {
	case '"':
		return '"', true
	case '\\':
		return '\\', true
	case '/':
		return '/', true
	case 'b':
		return '\b', true
	case 'f':
		return '\f', true
	case 'n':
		return '\n', true
	case 'r':
		return '\r', true
	case 't':
		return '\t', true
	default:
		return 0, false
	}
}

// parseHex4 parses exactly four hexadecimal digits into a UTF-16 code unit.
func parseHex4(digits string) (uint16, error) {
	var value uint16
	for i := 0; i < len(digits); i++ {
		nibble, ok := hexNibble(digits[i])
		if !ok {
			return 0, fmt.Errorf("codegen: invalid hex digit %q in \\u escape", string(digits[i]))
		}
		value = value<<4 | uint16(nibble)
	}
	return value, nil
}

// hexNibble decodes one hexadecimal digit (either case) into its 0..15 value.
func hexNibble(c byte) (int, bool) {
	switch {
	case c >= '0' && c <= '9':
		return int(c - '0'), true
	case c >= 'a' && c <= 'f':
		return int(c-'a') + 10, true
	case c >= 'A' && c <= 'F':
		return int(c-'A') + 10, true
	default:
		return 0, false
	}
}
