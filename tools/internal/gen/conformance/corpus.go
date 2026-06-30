// Copyright (c) 2026 dexpace and Omar Aljarrah
// SPDX-License-Identifier: MIT

package conformance

import (
	"encoding/json"
	"fmt"
	"strings"
	"unicode/utf16"
	"unicode/utf8"
)

// idnaCase is one WPT conformance case modelled as UTF-16 code-unit sequences.
// expected is nil when the input must be rejected (corpus output null or empty);
// otherwise it is the required ToASCII result. UTF-16 units are kept verbatim so
// the two lone-surrogate inputs (a\ud900z, A\ud900Z) survive into the fixture.
type idnaCase struct {
	input    []uint16
	expected []uint16 // nil => required failure
}

// loadCases reads the two WPT corpora in order (IdnaTestV2 then toascii) and
// returns the cases de-duplicated by the (input, expected) pair in first-seen
// order, mirroring the Python load_cases exactly. The dedup key folds the
// expected==nil sentinel into a distinct component so a rejection never collides
// with a real output.
func loadCases(idnaV2, toascii []byte) ([]idnaCase, error) {
	var cases []idnaCase
	seen := map[string]bool{}
	for _, data := range [][]byte{idnaV2, toascii} {
		var elements []json.RawMessage
		if err := json.Unmarshal(data, &elements); err != nil {
			return nil, fmt.Errorf("conformance: parsing corpus array: %w", err)
		}
		for _, element := range elements {
			parsed, ok, err := parseEntry(element)
			if err != nil {
				return nil, err
			}
			if !ok {
				continue // a header string or an object without "input"
			}
			key := dedupKey(parsed)
			if seen[key] {
				continue
			}
			seen[key] = true
			cases = append(cases, parsed)
		}
	}
	return cases, nil
}

// parseEntry decodes one JSON array element into a case. It returns ok=false for
// any element that is not an object or that lacks an "input" key (this drops the
// descriptive header strings at the top of each corpus). The "comment" field is
// ignored.
func parseEntry(element json.RawMessage) (idnaCase, bool, error) {
	trimmed := strings.TrimLeft(string(element), " \t\r\n")
	if len(trimmed) == 0 || trimmed[0] != '{' {
		return idnaCase{}, false, nil
	}
	var fields map[string]json.RawMessage
	if err := json.Unmarshal(element, &fields); err != nil {
		return idnaCase{}, false, fmt.Errorf("conformance: parsing corpus object: %w", err)
	}
	rawInput, hasInput := fields["input"]
	if !hasInput {
		return idnaCase{}, false, nil
	}
	input, err := decodeJSONString(rawInput)
	if err != nil {
		return idnaCase{}, false, err
	}
	// expected = output if (output present, not null, and not "") else nil.
	var expected []uint16
	if rawOutput, hasOutput := fields["output"]; hasOutput && !isJSONNull(rawOutput) {
		output, decodeErr := decodeJSONString(rawOutput)
		if decodeErr != nil {
			return idnaCase{}, false, decodeErr
		}
		if len(output) > 0 {
			expected = output
		}
	}
	return idnaCase{input: input, expected: expected}, true, nil
}

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
		return nil, fmt.Errorf("conformance: not a JSON string token: %q", string(raw))
	}
	body = body[1 : len(body)-1]
	var units []uint16
	for i := 0; i < len(body); {
		c := body[i]
		if c != '\\' {
			r, size := utf8.DecodeRuneInString(body[i:])
			if r == utf8.RuneError && size <= 1 {
				return nil, fmt.Errorf("conformance: invalid UTF-8 in JSON string %q", string(raw))
			}
			units = utf16.AppendRune(units, r)
			i += size
			continue
		}
		if i+1 >= len(body) {
			return nil, fmt.Errorf("conformance: dangling backslash in JSON string %q", string(raw))
		}
		esc := body[i+1]
		if esc == 'u' {
			if i+6 > len(body) {
				return nil, fmt.Errorf("conformance: truncated \\u escape in %q", string(raw))
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
			return nil, fmt.Errorf("conformance: unknown escape \\%c in %q", esc, string(raw))
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
			return 0, fmt.Errorf("conformance: invalid hex digit %q in \\u escape", string(digits[i]))
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

// dedupKey builds a collision-free key for a case's (input, expected) pair. Each
// unit sequence is length-prefixed and serialized as raw bytes, with a one-byte
// flag distinguishing a present expected from the rejection sentinel.
func dedupKey(c idnaCase) string {
	var builder strings.Builder
	writeUnitsKey(&builder, c.input)
	if c.expected == nil {
		builder.WriteByte(0)
		return builder.String()
	}
	builder.WriteByte(1)
	writeUnitsKey(&builder, c.expected)
	return builder.String()
}

// writeUnitsKey appends a length-prefixed, byte-serialized form of units.
func writeUnitsKey(builder *strings.Builder, units []uint16) {
	length := len(units)
	builder.WriteByte(byte(length >> 24))
	builder.WriteByte(byte(length >> 16))
	builder.WriteByte(byte(length >> 8))
	builder.WriteByte(byte(length))
	for _, unit := range units {
		builder.WriteByte(byte(unit >> 8))
		builder.WriteByte(byte(unit))
	}
}
