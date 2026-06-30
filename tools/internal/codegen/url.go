// Copyright (c) 2026 dexpace and Omar Aljarrah
// SPDX-License-Identifier: MIT

// The url generator ports tools/url/generate_urltestdata_fixture.py: it reads the
// vendored WPT URL corpus and materializes the Kotlin UrlTestData fixture
// byte-for-byte. Only the corpus is materialized here; the known-failures
// baseline lives in the test, not in this generator.

package codegen

import (
	"bytes"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
)

// urlChunkSize is the number of cases per generated builder method, kept well
// under the 64 KB JVM constant-pool limit so no single method overflows.
const urlChunkSize = 60

// fieldIndent is the column at which every named constructor argument starts.
const fieldIndent = 16

// expectedFields are the component getters emitted, in UrlCase declaration order
// after input/base/failure. href is emitted separately, after this group.
var expectedFields = []string{
	"protocol", "username", "password", "hostname",
	"port", "pathname", "search", "hash",
}

// urlCase is one in-scope corpus entry. base is nil when the JSON value is null
// or the key is absent. values holds the component getter strings keyed by name
// (including href); for failure cases the generator emits "" regardless.
type urlCase struct {
	input   string
	base    *string
	failure bool
	values  map[string]string
}

// urlInputPath returns the absolute path of the vendored WPT corpus.
func urlInputPath() (string, error) {
	root, err := Root()
	if err != nil {
		return "", err
	}
	return filepath.Join(root, "tools", "url", "urltestdata.json"), nil
}

// generateURL reads the corpus and returns the complete Kotlin source string.
func generateURL() (string, error) {
	path, err := urlInputPath()
	if err != nil {
		return "", err
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return "", err
	}
	cases, err := urlLoadCases(data)
	if err != nil {
		return "", err
	}
	return urlEmitFixture(cases), nil
}

// urlLoadCases decodes the top-level JSON array and keeps only objects carrying
// an `input` key, preserving document order with no sorting or deduplication. The
// 112 documentation strings (and any object lacking `input`) are dropped.
func urlLoadCases(data []byte) ([]urlCase, error) {
	var elements []json.RawMessage
	if err := json.Unmarshal(data, &elements); err != nil {
		return nil, fmt.Errorf("url: decoding corpus array: %w", err)
	}
	cases := make([]urlCase, 0, len(elements))
	for index, element := range elements {
		if firstNonSpace(element) != '{' {
			continue // documentation string
		}
		var object map[string]json.RawMessage
		if err := json.Unmarshal(element, &object); err != nil {
			return nil, fmt.Errorf("url: decoding object at index %d: %w", index, err)
		}
		if _, ok := object["input"]; !ok {
			continue
		}
		parsed, err := parseCase(object, index)
		if err != nil {
			return nil, err
		}
		cases = append(cases, parsed)
	}
	return cases, nil
}

// parseCase reads the fields the generator emits from one corpus object. base
// is nil for a JSON null or an absent key; failure is true only for an explicit
// JSON true; component getters default to "" when absent.
func parseCase(object map[string]json.RawMessage, index int) (urlCase, error) {
	input, err := decodeString(object["input"], "input", index)
	if err != nil {
		return urlCase{}, err
	}
	var base *string
	if raw, ok := object["base"]; ok && firstNonSpace(raw) != 'n' {
		value, decodeErr := decodeString(raw, "base", index)
		if decodeErr != nil {
			return urlCase{}, decodeErr
		}
		base = &value
	}
	failure := false
	if raw, ok := object["failure"]; ok {
		if err := json.Unmarshal(raw, &failure); err != nil {
			return urlCase{}, fmt.Errorf("url: decoding failure at index %d: %w", index, err)
		}
	}
	values := make(map[string]string, len(expectedFields)+1)
	for _, name := range append(append([]string{}, expectedFields...), "href") {
		if raw, ok := object[name]; ok {
			value, decodeErr := decodeString(raw, name, index)
			if decodeErr != nil {
				return urlCase{}, decodeErr
			}
			values[name] = value
		}
	}
	return urlCase{input: input, base: base, failure: failure, values: values}, nil
}

// decodeString unmarshals a JSON string field, wrapping decode errors with the
// field name and corpus index for context.
func decodeString(raw json.RawMessage, field string, index int) (string, error) {
	var value string
	if err := json.Unmarshal(raw, &value); err != nil {
		return "", fmt.Errorf("url: decoding %s at index %d: %w", field, index, err)
	}
	return value, nil
}

// firstNonSpace returns the first non-whitespace byte of raw, or 0 if raw is all
// whitespace. It distinguishes object elements ('{') from documentation strings
// ('"') and a null base ('n') without a full decode.
func firstNonSpace(raw []byte) byte {
	trimmed := bytes.TrimLeft(raw, " \t\r\n")
	if len(trimmed) == 0 {
		return 0
	}
	return trimmed[0]
}

// urlEmitFixture renders the complete fixture: license header, suppress block,
// package, data class, and the chunked builder object, terminated by exactly one
// newline. The structure mirrors the Python emit_fixture exactly.
func urlEmitFixture(cases []urlCase) string {
	parts := Chunk(cases, urlChunkSize)
	lines := []string{LicenseHeader, ""}
	lines = append(lines,
		"// Generated bulk data, not hand-written logic: the chunked builders intentionally exceed",
		"// detekt's method/class-size heuristics to stay within the 64 KB JVM method limit.",
		FileSuppress("LongMethod", "LargeClass", "MatchingDeclarationName"),
		"",
		"package org.dexpace.kuri.parser",
		"",
	)
	lines = append(lines, dataClassLines()...)
	lines = append(lines,
		"",
		"// Generated by tools/url/generate_urltestdata_fixture.py from the WPT urltestdata corpus.",
		"// Chunked into small builders so no single method exceeds the 64 KB JVM limit.",
		"private object UrlConformanceCaseData {",
	)
	lines = append(lines, "    fun all(): List<UrlCase> =")
	lines = append(lines, "        "+urlSumExpression(len(parts)))
	for index, part := range parts {
		lines = append(lines, "")
		lines = append(lines, fmt.Sprintf("    private fun part%d(): List<UrlCase> =", index))
		lines = append(lines, "        listOf(")
		for _, current := range part {
			lines = append(lines, urlCaseLines(current)...)
		}
		lines = append(lines, "        )")
	}
	lines = append(lines,
		"}",
		"",
		"/** Every in-scope WPT `urltestdata.json` case (objects carrying an `input`). */",
		"internal val URL_TEST_CASES: List<UrlCase> = UrlConformanceCaseData.all()",
	)
	return JoinLines(lines)
}

// urlSumExpression renders the `part0() + part1() + ...` body of all(): the first
// term sits at 8 spaces (supplied by the caller) and each subsequent term wraps
// to its own line at 12 spaces with a trailing " +" on all but the last.
func urlSumExpression(count int) string {
	terms := make([]string, count)
	for i := range terms {
		terms[i] = fmt.Sprintf("part%d()", i)
	}
	return join(terms, " +\n            ")
}

// join concatenates terms with sep. It exists only so urlSumExpression reads
// clearly; strings.Join would do, but the separator embeds a newline and indent
// that are easy to misread inline.
func join(terms []string, sep string) string {
	result := ""
	for i, term := range terms {
		if i > 0 {
			result += sep
		}
		result += term
	}
	return result
}

// urlCaseLines renders one UrlCase constructor call as indented named arguments.
// failure cases force every component getter and href to "".
func urlCaseLines(current urlCase) []string {
	lines := []string{"            UrlCase("}
	lines = append(lines, FieldLines("input", current.input, fieldIndent)...)
	if current.base == nil {
		lines = append(lines, "                base = null,")
	} else {
		lines = append(lines, FieldLines("base", *current.base, fieldIndent)...)
	}
	failureText := "false"
	if current.failure {
		failureText = "true"
	}
	lines = append(lines, "                failure = "+failureText+",")
	for _, name := range expectedFields {
		lines = append(lines, FieldLines(name, current.fieldValue(name), fieldIndent)...)
	}
	lines = append(lines, FieldLines("href", current.fieldValue("href"), fieldIndent)...)
	lines = append(lines, "            ),")
	return lines
}

// fieldValue returns the emitted value for a component getter or href: the empty
// string for a failure case, otherwise the parsed value (defaulting to "").
func (c urlCase) fieldValue(name string) string {
	if c.failure {
		return ""
	}
	return c.values[name]
}

// dataClassLines is the fixed KDoc + UrlCase data-class declaration.
func dataClassLines() []string {
	return []string{
		"/**",
		" * One WPT `urltestdata.json` case for the `Url` profile.",
		" *",
		" * A [failure] case asserts the parse must fail; otherwise the expected getter strings",
		" * (`protocol` = scheme + `:`, `hostname` = serialized host, `port` = string with `\"\"`",
		" * for none, `search`/`hash` including their leading `?`/`#`) are compared against the",
		" * parsed [org.dexpace.kuri.parser.ParsedComponents]. [href] is the full WHATWG-serialized",
		" * URL (the parse -> serialize round-trip target). [base] is the optional base URL the",
		" * input resolves against (`null` for an absolute parse).",
		" */",
		"internal data class UrlCase(",
		"    val input: String,",
		"    val base: String?,",
		"    val failure: Boolean,",
		"    val protocol: String,",
		"    val username: String,",
		"    val password: String,",
		"    val hostname: String,",
		"    val port: String,",
		"    val pathname: String,",
		"    val search: String,",
		"    val hash: String,",
		"    val href: String,",
		")",
	}
}
