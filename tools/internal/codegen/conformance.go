// Copyright (c) 2026 dexpace and Omar Aljarrah
// SPDX-License-Identifier: MIT

// The conformance generator regenerates the IDNA conformance test fixture
// (IdnaConformanceData.kt) and the tracked known-failures baseline
// (IdnaConformanceKnownFailures.kt) from the WPT IdnaTestV2 and toascii corpora.
//
// The data fixture is a byte-for-byte port of the Python generator: parse both
// corpora, de-duplicate by (input, expected) in first-seen order, and emit the
// chunked IdnaCase builders. Lone surrogates in the corpus inputs are preserved
// through a UTF-16-unit-aware JSON decoder, since encoding/json would replace
// them with U+FFFD.
//
// The known-failures baseline is DERIVED, not copied: the internal/idnaref port
// of kuri's Idna.domainToAscii is run over the corpus and the inputs it fails
// become the tracked set. Because the reference matches kuri exactly, that set
// equals kuri's live failing set, which IdnaConformanceTest asserts against.

package codegen

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"unicode/utf16"
	"unicode/utf8"

	"github.com/dexpace/kuri/tools/internal/idnaref"
)

// idnaCase is one WPT conformance case modelled as UTF-16 code-unit sequences.
// expected is nil when the input must be rejected (corpus output null or empty);
// otherwise it is the required ToASCII result. UTF-16 units are kept verbatim so
// the two lone-surrogate inputs (a\ud900z, A\ud900Z) survive into the fixture.
type idnaCase struct {
	input    []uint16
	expected []uint16 // nil => required failure
}

// generateConformance reads the corpora, emits the data fixture, derives the
// known-failures set via the idnaref reference, and returns both Kotlin sources.
func generateConformance() (dataSource, knownSource string, err error) {
	idnaV2, toascii, err := readCorpora()
	if err != nil {
		return "", "", err
	}
	cases, err := conformanceLoadCases(idnaV2, toascii)
	if err != nil {
		return "", "", err
	}
	ref, err := idnaref.NewReference()
	if err != nil {
		return "", "", err
	}
	failing := deriveFailures(ref, cases)
	return conformanceEmitFixture(cases), emitKnownFailures(failing), nil
}

// deriveFailures returns, in sorted order, the de-duplicated inputs the idnaref
// reference fails the corpus on.
func deriveFailures(ref *idnaref.Reference, cases []idnaCase) [][]uint16 {
	seen := map[string]bool{}
	var failing [][]uint16
	for _, c := range cases {
		if !ref.CaseFails(c.input, c.expected) {
			continue
		}
		key := string(unitsToKey(c.input))
		if seen[key] {
			continue
		}
		seen[key] = true
		failing = append(failing, c.input)
	}
	sort.Slice(failing, func(i, j int) bool { return lessUnits(failing[i], failing[j]) })
	return failing
}

// lessUnits orders UTF-16 sequences lexicographically by code unit, matching
// Kotlin's natural String ordering (shorter sequence first on a shared prefix).
func lessUnits(a, b []uint16) bool {
	for index := 0; index < len(a) && index < len(b); index++ {
		if a[index] != b[index] {
			return a[index] < b[index]
		}
	}
	return len(a) < len(b)
}

// unitsToKey serializes a UTF-16 sequence to a collision-free byte key.
func unitsToKey(units []uint16) []byte {
	out := make([]byte, 0, len(units)*2)
	for _, unit := range units {
		out = append(out, byte(unit>>8), byte(unit))
	}
	return out
}

// readCorpora reads both WPT corpus files from the vendored ada reference.
func readCorpora() (idnaV2, toascii []byte, err error) {
	root, err := Root()
	if err != nil {
		return nil, nil, err
	}
	dir := filepath.Join(root, ".claude", "references", "ada", "tests", "wpt")
	idnaV2, err = os.ReadFile(filepath.Join(dir, "IdnaTestV2.json"))
	if err != nil {
		return nil, nil, err
	}
	toascii, err = os.ReadFile(filepath.Join(dir, "toascii.json"))
	if err != nil {
		return nil, nil, err
	}
	return idnaV2, toascii, nil
}

// writeConformanceFixtures writes both generated sources to their commonTest
// fixture paths.
func writeConformanceFixtures(dataSource, knownSource string) error {
	root, err := Root()
	if err != nil {
		return err
	}
	dir := filepath.Join(root, "kuri", "src", "commonTest", "kotlin", "org", "dexpace", "kuri", "idna")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	if err := os.WriteFile(filepath.Join(dir, "IdnaConformanceData.kt"), []byte(dataSource), 0o644); err != nil {
		return err
	}
	return os.WriteFile(filepath.Join(dir, "IdnaConformanceKnownFailures.kt"), []byte(knownSource), 0o644)
}

// conformanceLoadCases reads the two WPT corpora in order (IdnaTestV2 then
// toascii) and returns the cases de-duplicated by the (input, expected) pair in
// first-seen order, mirroring the Python load_cases exactly. The dedup key folds
// the expected==nil sentinel into a distinct component so a rejection never
// collides with a real output.
func conformanceLoadCases(idnaV2, toascii []byte) ([]idnaCase, error) {
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

// conformanceChunkSize is the number of cases/entries per generated builder, kept
// well under the 64 KB JVM method-constant limit. It matches the Python CHUNK.
const conformanceChunkSize = 320

// packageLine is the Kotlin package shared by both generated files.
const packageLine = "package org.dexpace.kuri.idna"

// caseBaseIndent is the column the IdnaCase(...) entries start at (inside listOf).
const caseBaseIndent = 12

// caseWrapIndent is the indent for an entry's wrapped literal operands.
const caseWrapIndent = 16

// knownEntryIndent is the column the known-failure string entries start at.
const knownEntryIndent = 12

// literalLines emits a string literal as one indented line, or, when it would
// exceed the column limit, as a `"a" + "b"` continuation wrapped exactly the way
// ktlint expects: the first operand at indent, the rest at indent+4, the budget
// keyed to the continuation indent, never splitting a token. It ports the Python
// literal_lines.
func literalLines(units []uint16, indent int, suffix string) []string {
	pad := strings.Repeat(" ", indent)
	single := pad + KotlinStringUTF16(units) + suffix
	if len(single) <= MaxCols {
		return []string{single}
	}
	contIndent := indent + 4
	budget := MaxCols - contIndent - len(" +")
	segments := PackSegments(EscapeTokensUTF16(units), budget)
	contPad := strings.Repeat(" ", contIndent)
	lines := make([]string, 0, len(segments))
	for index, segment := range segments {
		prefix := pad
		if index != 0 {
			prefix = contPad
		}
		tail := " +"
		if index == len(segments)-1 {
			tail = suffix
		}
		lines = append(lines, prefix+`"`+segment+`"`+tail)
	}
	return lines
}

// conformanceCaseLines emits one IdnaCase entry, single-line when it fits and
// multi-line (each literal wrapped) otherwise, porting the Python case_lines.
func conformanceCaseLines(c idnaCase) []string {
	expected := "null"
	if c.expected != nil {
		expected = KotlinStringUTF16(c.expected)
	}
	single := fmt.Sprintf("%sIdnaCase(%s, %s),",
		strings.Repeat(" ", caseBaseIndent), KotlinExprUTF16(c.input), expected)
	// A lone-surrogate input is emitted as a runtime concatenation (it cannot survive
	// as a string literal on Kotlin/JS); such inputs are short, so keep them one-line.
	if len(single) <= MaxCols || HasLoneSurrogate(c.input) {
		return []string{single}
	}
	lines := []string{strings.Repeat(" ", caseBaseIndent) + "IdnaCase("}
	lines = append(lines, literalLines(c.input, caseWrapIndent, ",")...)
	if c.expected == nil {
		lines = append(lines, strings.Repeat(" ", caseWrapIndent)+"null,")
	} else {
		lines = append(lines, literalLines(c.expected, caseWrapIndent, ",")...)
	}
	return append(lines, strings.Repeat(" ", caseBaseIndent)+"),")
}

// conformanceEmitFixture renders IdnaConformanceData.kt, byte-for-byte with the
// Python generator: header, suppress block, the IdnaCase data class, the chunked
// part-builders, and the IDNA_CONFORMANCE_CASES accessor.
func conformanceEmitFixture(cases []idnaCase) string {
	parts := Chunk(cases, conformanceChunkSize)
	lines := []string{LicenseHeader, ""}
	lines = append(lines, fileSuppress("LongMethod", "LargeClass", "MatchingDeclarationName")...)
	lines = append(lines, packageLine, "")
	lines = append(lines,
		"/** One WPT IDNA conformance case: [expected] is `null` when [input] must be rejected. */",
		"internal data class IdnaCase(",
		"    val input: String,",
		"    val expected: String?,",
		")",
		"",
		"// Generated by tools/idna/generate_conformance_fixture.py from the WPT corpora.",
		"// Chunked into small builders so no single method exceeds the 64 KB JVM limit.",
		"private object IdnaConformanceCaseData {",
		"    fun all(): List<IdnaCase> =",
		"        "+conformanceSumExpression(len(parts)),
	)
	for index, part := range parts {
		lines = append(lines, "", fmt.Sprintf("    private fun part%d(): List<IdnaCase> =", index), "        listOf(")
		for _, c := range part {
			lines = append(lines, conformanceCaseLines(c)...)
		}
		lines = append(lines, "        )")
	}
	lines = append(lines,
		"}",
		"",
		"/** Every WPT IDNA ToASCII case (IdnaTestV2 + toascii), de-duplicated by (input, expected). */",
		"internal val IDNA_CONFORMANCE_CASES: List<IdnaCase> = IdnaConformanceCaseData.all()",
	)
	return JoinLines(lines)
}

// emitKnownFailures renders IdnaConformanceKnownFailures.kt: the tracked baseline
// of inputs kuri's domainToAscii currently fails the corpus on, as a sorted
// Set<String>. The set is the byte-significant content; the prose is documentary.
func emitKnownFailures(inputs [][]uint16) string {
	lines := []string{LicenseHeader, "", packageLine, ""}
	lines = append(lines,
		"/**",
		" * Inputs that [Idna.domainToAscii] still fails the WPT IDNA corpora (IdnaTestV2 + toascii) on,",
		" * tracked so [IdnaConformanceTest] ratchets: the live failing set must equal this set exactly,",
		" * so closing a gap (or a regression) breaks the build until this baseline is regenerated.",
		" *",
		" * Every entry is a UTS-46 criterion this `Url`-profile run does not apply (CheckBidi,",
		" * decoded-A-label re-validation, host-layer forbidden code points, empty-domain length) or a",
		" * corpus/Unicode-version corner -- none reflect a Punycode/mapping/NFC output defect.",
		" *",
		" * GENERATED by `tools/cmd/codegen conformance` from the WPT corpora; do not edit by hand.",
		" */",
		"private object IdnaKnownFailureData {",
		"    fun all(): Set<String> =",
		"        setOf(",
	)
	for _, input := range inputs {
		lines = append(lines, literalLines(input, knownEntryIndent, ",")...)
	}
	lines = append(lines,
		"        )",
		"}",
		"",
		"/** Tracked baseline of currently-failing conformance inputs (see [IdnaKnownFailureData]). */",
		"internal val IDNA_KNOWN_FAILURES: Set<String> = IdnaKnownFailureData.all()",
	)
	return JoinLines(lines)
}

// conformanceSumExpression builds the `part0() +\n            part1() + ...
// partN()` summed accessor body, with 12-space continuation indentation.
func conformanceSumExpression(count int) string {
	parts := make([]string, count)
	for index := range parts {
		parts[index] = fmt.Sprintf("part%d()", index)
	}
	return strings.Join(parts, " +\n            ")
}

// fileSuppress renders the two-comment + @file:Suppress(...) + blank-line block.
func fileSuppress(rules ...string) []string {
	return []string{
		"// Generated bulk data, not hand-written logic: the chunked builders intentionally exceed",
		"// detekt's method/class-size heuristics to stay within the 64 KB JVM method limit.",
		FileSuppress(rules...),
		"",
	}
}
