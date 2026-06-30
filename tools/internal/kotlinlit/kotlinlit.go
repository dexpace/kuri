// Copyright (c) 2026 dexpace and Omar Aljarrah
// SPDX-License-Identifier: MIT

// Package kotlinlit emits ASCII-only Kotlin source fragments shared by the
// codegen generators: string-literal escaping, column-aware field wrapping,
// chunking, and the boilerplate headers. Every helper produces bytes that match
// the Python generators line-for-line so the regenerated fixtures stay
// byte-identical.
package kotlinlit

import (
	"fmt"
	"strings"
	"unicode/utf16"
)

// MaxCols is the hard column limit the generated Kotlin must respect (ktlint's
// 120-column rule). Field wrapping keys its budget off this value.
const MaxCols = 120

// LicenseHeader is the MIT block comment that opens every generated file. It has
// no trailing newline; the line joiner supplies the separators.
const LicenseHeader = "/*\n" +
	" * Copyright (c) 2026 dexpace and Omar Aljarrah\n" +
	" * SPDX-License-Identifier: MIT\n" +
	" */"

// EscapeTokensUTF16 escapes a sequence of UTF-16 code units into ASCII-only
// Kotlin string tokens. Each returned token is indivisible: a surrogate pair
// becomes a single `\uHHHH\uHHHH` token so line wrapping can never split it,
// and a lone surrogate falls through to a single `\uHHHH` escape. The escape
// order mirrors the Python generators exactly: surrogate pair, then `"`, `\`,
// `$`, then printable ASCII verbatim, then `\uHHHH` for everything else
// (including tab/newline/CR). All hex is lowercase and zero-padded to 4 digits.
func EscapeTokensUTF16(units []uint16) []string {
	tokens := make([]string, 0, len(units))
	for i := 0; i < len(units); i++ {
		u := units[i]
		if isHighSurrogate(u) && i+1 < len(units) && isLowSurrogate(units[i+1]) {
			tokens = append(tokens, fmt.Sprintf("\\u%04x\\u%04x", u, units[i+1]))
			i++
			continue
		}
		switch {
		case u == '"':
			tokens = append(tokens, "\\\"")
		case u == '\\':
			tokens = append(tokens, "\\\\")
		case u == '$':
			tokens = append(tokens, "\\$")
		case u >= 0x20 && u <= 0x7E:
			tokens = append(tokens, string(rune(u)))
		default:
			tokens = append(tokens, fmt.Sprintf("\\u%04x", u))
		}
	}
	return tokens
}

// EscapeTokens escapes a Go string into Kotlin string tokens by first encoding
// it to UTF-16. encoding/json has already merged JSON surrogate pairs into
// single runes, so this reproduces Python's per-code-point iteration.
func EscapeTokens(value string) []string {
	return EscapeTokensUTF16(utf16.Encode([]rune(value)))
}

// KotlinString returns the full double-quoted Kotlin literal for value.
func KotlinString(value string) string {
	return `"` + strings.Join(EscapeTokens(value), "") + `"`
}

// KotlinStringUTF16 returns the full double-quoted Kotlin literal for a sequence
// of UTF-16 code units, preserving lone surrogates (escaped as a single \uHHHH).
// Use this over KotlinString when the value may carry an unpaired surrogate that
// a round-trip through Go runes would replace with U+FFFD.
func KotlinStringUTF16(units []uint16) string {
	return `"` + strings.Join(EscapeTokensUTF16(units), "") + `"`
}

// PackSegments greedily groups escape tokens into quoted-literal segments, each
// kept within budget and never splitting a token. It is the wrapping primitive
// shared by the column-aware field emitters and the conformance literal wrapper.
func PackSegments(tokens []string, budget int) []string {
	return packSegments(tokens, budget)
}

// isHighSurrogate reports whether u is a UTF-16 high (leading) surrogate.
func isHighSurrogate(u uint16) bool { return u >= 0xD800 && u <= 0xDBFF }

// isLowSurrogate reports whether u is a UTF-16 low (trailing) surrogate.
func isLowSurrogate(u uint16) bool { return u >= 0xDC00 && u <= 0xDFFF }

// FieldLines emits `<indent>name = "<value>",` on one line when it fits within
// MaxCols, otherwise wraps the literal using the URL generator's two-tier
// indentation: the first operand at indent+4 and continuation operands at
// indent+8. It is shorthand for FieldLinesIndented with those offsets.
func FieldLines(name, value string, indent int) []string {
	return FieldLinesIndented(name, value, indent, indent+4, indent+8)
}

// FieldLinesIndented is the general column-aware field emitter. When the single
// line `<indent>name = "<value>",` (trailing comma included) is at most MaxCols
// it is emitted as-is. Otherwise it emits `<indent>name =`, then the value's
// escape tokens greedily packed into quoted operands: the first operand at
// firstIndent, the rest at contIndent. The packing budget is keyed to
// contIndent (MaxCols - contIndent - len(" +")) and never splits a token; every
// operand carries a " +" tail except the last, which carries ",".
//
// firstIndent and contIndent are parameters because the sibling generators wrap
// differently: the URL generator uses indent+4 then indent+8, whereas the idna
// conformance generator uses a single continuation indent (indent+4 for both).
func FieldLinesIndented(name, value string, indent, firstIndent, contIndent int) []string {
	pad := strings.Repeat(" ", indent)
	single := pad + name + " = " + KotlinString(value) + ","
	if len(single) <= MaxCols {
		return []string{single}
	}
	budget := MaxCols - contIndent - len(" +")
	segments := packSegments(EscapeTokens(value), budget)
	firstPad := strings.Repeat(" ", firstIndent)
	contPad := strings.Repeat(" ", contIndent)
	lines := make([]string, 0, len(segments)+1)
	lines = append(lines, pad+name+" =")
	for index, segment := range segments {
		head := contPad
		if index == 0 {
			head = firstPad
		}
		tail := " +"
		if index == len(segments)-1 {
			tail = ","
		}
		lines = append(lines, head+`"`+segment+`"`+tail)
	}
	return lines
}

// packSegments greedily groups tokens so each quoted segment stays within
// budget, never splitting a token. A segment is flushed only when the current
// run is non-empty and adding the next token would exceed budget, so there is
// always at least one (possibly empty) segment.
func packSegments(tokens []string, budget int) []string {
	var segments []string
	current := ""
	for _, token := range tokens {
		if current != "" && len(`"`+current+token+`"`) > budget {
			segments = append(segments, current)
			current = token
		} else {
			current += token
		}
	}
	return append(segments, current)
}

// Chunk splits items into consecutive, order-preserving slices of at most size
// elements. The final slice holds the remainder. A non-positive size yields a
// single chunk holding everything, which keeps callers from looping forever.
func Chunk[T any](items []T, size int) [][]T {
	if size <= 0 {
		return [][]T{items}
	}
	chunks := make([][]T, 0, (len(items)+size-1)/size)
	for start := 0; start < len(items); start += size {
		end := start + size
		if end > len(items) {
			end = len(items)
		}
		chunks = append(chunks, items[start:end])
	}
	return chunks
}

// FileSuppress renders a `@file:Suppress(...)` annotation with each name quoted
// and comma-separated, matching the layout the generators emit.
func FileSuppress(names ...string) string {
	quoted := make([]string, len(names))
	for i, name := range names {
		quoted[i] = `"` + name + `"`
	}
	return "@file:Suppress(" + strings.Join(quoted, ", ") + ")"
}

// JoinLines joins lines with newlines and appends exactly one trailing newline,
// reproducing Python's `"\n".join(lines) + "\n"`. Elements may themselves span
// multiple lines (e.g. the license header); their internal newlines are kept.
func JoinLines(lines []string) string {
	return strings.Join(lines, "\n") + "\n"
}
