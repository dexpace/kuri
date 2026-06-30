// Copyright (c) 2026 dexpace and Omar Aljarrah
// SPDX-License-Identifier: MIT

// Package nfctest ports tools/idna/generate_nfc_test_fixture.py: it reads the
// vendored Unicode NormalizationTest.txt corpus and materializes the Kotlin
// NfcTestData fixture byte-for-byte. Only the source and NFC columns are kept;
// the cases are emitted in raw file order with no sort, dedup, or range
// expansion.
package nfctest

import (
	"bufio"
	"bytes"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/dexpace/kuri/tools/internal/kotlinlit"
	"github.com/dexpace/kuri/tools/internal/repo"
	"github.com/dexpace/kuri/tools/internal/ucd"
)

// chunkSize is the number of cases per generated builder method, kept well under
// the 64 KB JVM method limit so no single method overflows.
const chunkSize = 400

// maxLine is the column budget for the single-line NfcCase form; a record whose
// single-line rendering exceeds it is wrapped across four lines instead.
const maxLine = 120

// sourceColumn and nfcColumn are the only NormalizationTest.txt columns consumed;
// the remaining NFD/NFKC/NFKD columns and the trailing empty field are ignored.
const (
	sourceColumn = 0
	nfcColumn    = 1
)

// scannerBufferSize caps a single scanned line. UCD lines are short, but the
// buffer is raised above bufio's 64 KB default defensively so an unusually long
// comment line can never silently truncate the input.
const scannerBufferSize = 1 << 20

// nfcCase is one NormalizationTest.txt entry: source and its required NFC form,
// each already decoded from its hex-scalar field into a Go string.
type nfcCase struct {
	source string
	nfc    string
}

// OutputPath returns the absolute path of the generated NfcTestData.kt fixture.
func OutputPath() (string, error) {
	root, err := repo.Root()
	if err != nil {
		return "", err
	}
	return filepath.Join(
		root, "kuri", "src", "commonTest", "kotlin", "org", "dexpace",
		"kuri", "idna", "NfcTestData.kt",
	), nil
}

// inputPath returns the absolute path of the vendored NormalizationTest.txt.
func inputPath() (string, error) {
	root, err := repo.Root()
	if err != nil {
		return "", err
	}
	return filepath.Join(
		root, ".claude", "references", "unicode-16.0", "NormalizationTest.txt",
	), nil
}

// Generate reads the corpus and returns the complete Kotlin source string.
func Generate() (string, error) {
	path, err := inputPath()
	if err != nil {
		return "", err
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return "", err
	}
	cases, err := loadCases(data)
	if err != nil {
		return "", err
	}
	return emitFixture(cases), nil
}

// Run generates the fixture and either prints it to stdout or writes it to the
// fixture path, creating the parent directory if needed.
func Run(stdout bool) error {
	source, err := Generate()
	if err != nil {
		return err
	}
	if stdout {
		_, err := os.Stdout.WriteString(source)
		return err
	}
	out, err := OutputPath()
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(out), 0o755); err != nil {
		return err
	}
	return os.WriteFile(out, []byte(source), 0o644)
}

// loadCases parses (source, nfc) pairs in file order, mirroring the Python
// load_cases exactly: drop everything after the first '#', strip surrounding
// whitespace, skip blank and '@'-prefixed section-header lines, then split the
// remainder on ';' and decode the source and NFC hex-scalar columns. No sort, no
// dedup, no validation — the input order is preserved verbatim.
func loadCases(data []byte) ([]nfcCase, error) {
	scanner := bufio.NewScanner(bytes.NewReader(data))
	scanner.Buffer(make([]byte, 0, scannerBufferSize), scannerBufferSize)
	var cases []nfcCase
	for scanner.Scan() {
		body := strings.TrimSpace(strings.SplitN(scanner.Text(), "#", 2)[0])
		if body == "" || strings.HasPrefix(body, "@") {
			continue
		}
		columns := strings.Split(body, ";")
		source, err := ucd.ScalarsToString(columns[sourceColumn])
		if err != nil {
			return nil, err
		}
		nfc, err := ucd.ScalarsToString(columns[nfcColumn])
		if err != nil {
			return nil, err
		}
		cases = append(cases, nfcCase{source: source, nfc: nfc})
	}
	if err := scanner.Err(); err != nil {
		return nil, fmt.Errorf("nfctest: scanning corpus: %w", err)
	}
	return cases, nil
}

// emitFixture renders the complete fixture: license header, suppress block,
// package, NfcCase data class, and the chunked builder object, terminated by
// exactly one newline. The structure mirrors the Python render_kotlin exactly.
func emitFixture(cases []nfcCase) string {
	parts := kotlinlit.Chunk(cases, chunkSize)
	lines := []string{
		kotlinlit.LicenseHeader,
		"",
		"// Generated bulk data, not hand-written logic: the chunked builders intentionally exceed",
		"// detekt's method/class-size heuristics to stay within the 64 KB JVM method limit.",
		kotlinlit.FileSuppress("LongMethod", "LargeClass", "MatchingDeclarationName"),
		"",
		"package org.dexpace.kuri.idna",
		"",
		"/** One Unicode NormalizationTest.txt case: [nfc] is the required NFC form of [source]. */",
		"internal data class NfcCase(",
		"    val source: String,",
		"    val nfc: String,",
		")",
		"",
		"// Generated by tools/idna/generate_nfc_test_fixture.py from NormalizationTest.txt.",
		"private object NfcCaseData {",
	}
	lines = append(lines, "    fun all(): List<NfcCase> =")
	lines = append(lines, "        "+sumExpression(len(parts)))
	for index, part := range parts {
		lines = append(lines, "")
		lines = append(lines, fmt.Sprintf("    private fun part%d(): List<NfcCase> =", index))
		lines = append(lines, "        listOf(")
		for _, current := range part {
			lines = append(lines, caseLines(current)...)
		}
		lines = append(lines, "        )")
	}
	lines = append(lines,
		"}",
		"",
		"/** Every NormalizationTest.txt case as `(source, NFC)` (see [NfcCaseData]). */",
		"internal val NFC_CASES: List<NfcCase> = NfcCaseData.all()",
	)
	return kotlinlit.JoinLines(lines)
}

// sumExpression renders the `part0() + part1() + ...` body of all(): the first
// term sits at 8 spaces (supplied by the caller) and each subsequent term wraps
// to its own line at 12 spaces with a trailing " +" on all but the last. A
// single part collapses to just "part0()".
func sumExpression(count int) string {
	terms := make([]string, count)
	for i := range terms {
		terms[i] = fmt.Sprintf("part%d()", i)
	}
	return strings.Join(terms, " +\n            ")
}

// caseLines renders one NfcCase constructor call. The single-line form is
// preferred; when its length would exceed maxLine the record wraps to four lines
// with the two literals indented at 16 spaces. The trailing comma is always
// present (Kotlin trailing comma), even on the last case in a listOf.
func caseLines(current nfcCase) []string {
	sourceLit := escapeLiteral(current.source)
	nfcLit := escapeLiteral(current.nfc)
	singleLine := "            NfcCase(\"" + sourceLit + "\", \"" + nfcLit + "\"),"
	if len(singleLine) <= maxLine {
		return []string{singleLine}
	}
	return []string{
		"            NfcCase(",
		"                \"" + sourceLit + "\",",
		"                \"" + nfcLit + "\",",
		"            ),",
	}
}

// escapeLiteral returns the pure-ASCII Kotlin string-literal body (without the
// surrounding quotes) for value, reusing the shared escaping so astral scalars
// become surrogate-pair escapes and every non-printable scalar a lowercase
// \uXXXX, identical to the Python escape_string.
func escapeLiteral(value string) string {
	return strings.Join(kotlinlit.EscapeTokens(value), "")
}
