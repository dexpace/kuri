// Copyright (c) 2026 dexpace and Omar Aljarrah
// SPDX-License-Identifier: MIT

// The nfc-test generator ports the former Python generator: it reads
// the vendored Unicode NormalizationTest.txt corpus and materializes the Kotlin
// NfcTestData fixture byte-for-byte. All five columns are kept — source, NFC,
// NFD, NFKC, and NFKD — so the full UAX #15 NFC conformance clause can be
// asserted; the cases are emitted in raw file order with no sort, dedup, or
// range expansion.

package codegen

import (
	"bufio"
	"bytes"
	"fmt"
	"os"
	"strings"

	"github.com/dexpace/kuri/tools/internal/ucd"
)

// nfcTestChunkSize is the number of cases per generated builder method, kept well
// under the 64 KB JVM method limit so no single method overflows.
const nfcTestChunkSize = 400

// maxLine is the column budget for the single-line NfcCase form; a record whose
// single-line rendering exceeds it is wrapped across four lines instead.
const maxLine = 120

// sourceColumn..nfkdColumn are the five NormalizationTest.txt data columns
// consumed (c1..c5); the trailing empty field after c5 is ignored.
const (
	sourceColumn = 0
	nfcColumn    = 1
	nfdColumn    = 2
	nfkcColumn   = 3
	nfkdColumn   = 4
)

// scannerBufferSize caps a single scanned line. UCD lines are short, but the
// buffer is raised above bufio's 64 KB default defensively so an unusually long
// comment line can never silently truncate the input.
const scannerBufferSize = 1 << 20

// nfcCase is one NormalizationTest.txt entry: the source and its NFC, NFD, NFKC,
// and NFKD forms (columns c1..c5), each already decoded from its hex-scalar field
// into a Go string.
type nfcCase struct {
	source string
	nfc    string
	nfd    string
	nfkc   string
	nfkd   string
}

// nfcTestInputPath returns the absolute path of the vendored NormalizationTest.txt,
// resolved through ucd so it tracks the same bundled Unicode version as every
// other UCD input.
func nfcTestInputPath() (string, error) {
	return ucd.NormalizationTestPath()
}

// generateNfcTest reads the corpus and returns the complete Kotlin source string.
func generateNfcTest() (string, error) {
	path, err := nfcTestInputPath()
	if err != nil {
		return "", err
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return "", err
	}
	cases, err := nfcTestLoadCases(data)
	if err != nil {
		return "", err
	}
	return nfcTestEmitFixture(cases), nil
}

// nfcTestLoadCases parses (source, nfc, nfd, nfkc, nfkd) tuples in file order,
// mirroring the Python load_cases exactly: drop everything after the first '#',
// strip surrounding whitespace, skip blank and '@'-prefixed section-header lines,
// then split the remainder on ';' and decode the five hex-scalar columns c1..c5.
// No sort, no dedup, no validation — the input order is preserved verbatim.
func nfcTestLoadCases(data []byte) ([]nfcCase, error) {
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
		nfd, err := ucd.ScalarsToString(columns[nfdColumn])
		if err != nil {
			return nil, err
		}
		nfkc, err := ucd.ScalarsToString(columns[nfkcColumn])
		if err != nil {
			return nil, err
		}
		nfkd, err := ucd.ScalarsToString(columns[nfkdColumn])
		if err != nil {
			return nil, err
		}
		cases = append(cases, nfcCase{source: source, nfc: nfc, nfd: nfd, nfkc: nfkc, nfkd: nfkd})
	}
	if err := scanner.Err(); err != nil {
		return nil, fmt.Errorf("nfctest: scanning corpus: %w", err)
	}
	return cases, nil
}

// nfcTestEmitFixture renders the complete fixture: license header, suppress block,
// package, NfcCase data class, the NfcCaseData aggregator object, one NfcCasePartN
// object per chunk, and the NFC_CASES accessor, terminated by exactly one newline.
//
// Each chunk is emitted as its own object (rather than one method per chunk inside a
// single object) because five columns per case push the combined string-constant pool
// of a single class past the 65535-entry JVM class-file limit; splitting per chunk
// keeps every part object's constant pool comfortably bounded.
func nfcTestEmitFixture(cases []nfcCase) string {
	parts := Chunk(cases, nfcTestChunkSize)
	lines := []string{LicenseHeader, ""}
	lines = append(lines, FileSuppressBlock(bulkDataComments, "LongMethod", "LargeClass", "MatchingDeclarationName")...)
	lines = append(lines,
		"package org.dexpace.kuri.idna",
		"",
		"/**",
		" * One Unicode NormalizationTest.txt case, holding all five columns (c1..c5): [source] and its",
		" * [nfc], [nfd], [nfkc], and [nfkd] forms. These pin the full UAX #15 NFC conformance clause:",
		" * `nfc == nfc(source) == nfc(nfc) == nfc(nfd)` and `nfkc == nfc(nfkc) == nfc(nfkd)`.",
		" */",
		"internal data class NfcCase(",
		"    val source: String,",
		"    val nfc: String,",
		"    val nfd: String,",
		"    val nfkc: String,",
		"    val nfkd: String,",
		")",
		"",
		"// Generated by ./gradlew generateNfcTestFixture from NormalizationTest.txt. Each chunk is its own",
		"// object so no single class's string-constant pool exceeds the 65535-entry JVM class-file limit.",
		"private object NfcCaseData {",
		"    fun all(): List<NfcCase> =",
		"        "+nfcCasePartSumExpression(len(parts)),
		"}",
	)
	for index, part := range parts {
		lines = append(lines, "")
		lines = append(lines, fmt.Sprintf("private object NfcCasePart%d {", index))
		lines = append(lines, "    fun build(): List<NfcCase> =")
		lines = append(lines, "        listOf(")
		for _, current := range part {
			lines = append(lines, nfcTestCaseLines(current)...)
		}
		lines = append(lines, "        )")
		lines = append(lines, "}")
	}
	lines = append(lines,
		"",
		"/** Every NormalizationTest.txt case as `(source, NFC, NFD, NFKC, NFKD)` (see [NfcCaseData]). */",
		"internal val NFC_CASES: List<NfcCase> = NfcCaseData.all()",
	)
	return JoinLines(lines)
}

// nfcCasePartSumExpression renders the `NfcCasePart0.build() +\n            NfcCasePart1.build() + ...`
// aggregator body: the first term sits at the caller's 8-space indent and each later term wraps to its
// own line at 12 spaces with a trailing " +". A single part collapses to "NfcCasePart0.build()".
func nfcCasePartSumExpression(count int) string {
	terms := make([]string, count)
	for i := range terms {
		terms[i] = fmt.Sprintf("NfcCasePart%d.build()", i)
	}
	return strings.Join(terms, " +\n            ")
}

// nfcTestCaseLines renders one NfcCase constructor call with all five literals in
// c1..c5 order (source, nfc, nfd, nfkc, nfkd). The single-line form is preferred;
// when its length would exceed maxLine the record wraps to one literal per line
// indented at 16 spaces so the ktlint 120-column gate is never tripped. The
// trailing comma is always present (Kotlin trailing comma), even on the last case
// in a listOf.
func nfcTestCaseLines(current nfcCase) []string {
	sourceLit := escapeLiteral(current.source)
	nfcLit := escapeLiteral(current.nfc)
	nfdLit := escapeLiteral(current.nfd)
	nfkcLit := escapeLiteral(current.nfkc)
	nfkdLit := escapeLiteral(current.nfkd)
	singleLine := "            NfcCase(\"" + sourceLit + "\", \"" + nfcLit + "\", \"" + nfdLit +
		"\", \"" + nfkcLit + "\", \"" + nfkdLit + "\"),"
	if len(singleLine) <= maxLine {
		return []string{singleLine}
	}
	return []string{
		"            NfcCase(",
		"                \"" + sourceLit + "\",",
		"                \"" + nfcLit + "\",",
		"                \"" + nfdLit + "\",",
		"                \"" + nfkcLit + "\",",
		"                \"" + nfkdLit + "\",",
		"            ),",
	}
}

// escapeLiteral returns the pure-ASCII Kotlin string-literal body (without the
// surrounding quotes) for value, reusing the shared escaping so astral scalars
// become surrogate-pair escapes and every non-printable scalar a lowercase
// \uXXXX, identical to the Python escape_string.
//
// value is a Go string rather than UTF-16 units because NormalizationTest.txt
// encodes its columns as valid Unicode scalar values only: ucd.ScalarsToString
// rejects surrogate code points, so no unpaired surrogate can reach here and the
// round-trip through a Go string is lossless.
func escapeLiteral(value string) string {
	return strings.Join(EscapeTokens(value), "")
}
