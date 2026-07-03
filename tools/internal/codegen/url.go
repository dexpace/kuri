// Copyright (c) 2026 dexpace and Omar Aljarrah
// SPDX-License-Identifier: MIT

// The url generator ports the former Python generator: it reads the
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
	"port", "pathname", "search", "hash", "origin",
}

// urlCase is one in-scope corpus entry, modelled as UTF-16 code-unit sequences so
// an unpaired surrogate in the corpus survives into the fixture (encoding/json
// would fold it to U+FFFD). base is absent (hasBase == false) when the JSON value
// is null or the key is missing. values holds the component getter sequences keyed
// by name (including href); for failure cases the generator emits "" regardless.
type urlCase struct {
	input   []uint16
	base    []uint16
	hasBase bool
	failure bool
	values  map[string][]uint16
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
	var base []uint16
	hasBase := false
	if raw, ok := object["base"]; ok && firstNonSpace(raw) != 'n' {
		value, decodeErr := decodeString(raw, "base", index)
		if decodeErr != nil {
			return urlCase{}, decodeErr
		}
		base, hasBase = value, true
	}
	failure := false
	if raw, ok := object["failure"]; ok {
		if err := json.Unmarshal(raw, &failure); err != nil {
			return urlCase{}, fmt.Errorf("url: decoding failure at index %d: %w", index, err)
		}
	}
	values := make(map[string][]uint16, len(expectedFields)+1)
	for _, name := range append(append([]string{}, expectedFields...), "href") {
		if raw, ok := object[name]; ok {
			value, decodeErr := decodeString(raw, name, index)
			if decodeErr != nil {
				return urlCase{}, decodeErr
			}
			values[name] = value
		}
	}
	return urlCase{input: input, base: base, hasBase: hasBase, failure: failure, values: values}, nil
}

// decodeString decodes a JSON string field into UTF-16 code units via the shared
// lossless decoder (which preserves unpaired surrogates that encoding/json would
// fold to U+FFFD), wrapping decode errors with the field name and corpus index.
func decodeString(raw json.RawMessage, field string, index int) ([]uint16, error) {
	units, err := decodeJSONString(raw)
	if err != nil {
		return nil, fmt.Errorf("url: decoding %s at index %d: %w", field, index, err)
	}
	return units, nil
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
	lines = append(lines, FileSuppressBlock(bulkDataComments, "LongMethod", "LargeClass", "MatchingDeclarationName")...)
	lines = append(lines,
		"package org.dexpace.kuri.parser",
		"",
	)
	lines = append(lines, dataClassLines()...)
	lines = append(lines,
		"",
		"// Generated by ./gradlew generateUrlTestData from the WPT urltestdata corpus.",
		"// Chunked into small builders so no single method exceeds the 64 KB JVM limit.",
		"private object UrlConformanceCaseData {",
	)
	lines = append(lines, "    fun all(): List<UrlCase> =")
	lines = append(lines, "        "+PartSumExpression(len(parts)))
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

// urlCaseLines renders one UrlCase constructor call as indented named arguments.
// failure cases force every component getter and href to "".
func urlCaseLines(current urlCase) []string {
	lines := []string{"            UrlCase("}
	lines = append(lines, FieldLines("input", current.input, fieldIndent)...)
	if !current.hasBase {
		lines = append(lines, "                base = null,")
	} else {
		lines = append(lines, FieldLines("base", current.base, fieldIndent)...)
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
// sequence for a failure case, otherwise the parsed value (nil, emitted as "",
// when absent).
func (c urlCase) fieldValue(name string) []uint16 {
	if c.failure {
		return nil
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
		" * parsed [org.dexpace.kuri.parser.ParsedComponents]. [origin] is the WHATWG ASCII origin",
		" * serialization (a tuple `scheme://host[:port]`, or `\"null\"` for an opaque origin), or `\"\"`",
		" * when the corpus omits it or the case is a [failure]. [href] is the full WHATWG-serialized",
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
		"    val origin: String,",
		"    val href: String,",
		")",
	}
}
