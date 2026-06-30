// Copyright (c) 2026 dexpace and Omar Aljarrah
// SPDX-License-Identifier: MIT

// Package nfctables ports tools/idna/generate_nfc_tables.py: it parses the
// vendored Unicode UnicodeData.txt and CompositionExclusions.txt and emits the
// Kotlin NfcData.kt fixture byte-for-byte. Three compact, chunked string tables
// are produced — Canonical Combining Class, canonical decomposition, and the
// UAX #15 primary-composite map — each sliced into fixed-width ASCII chunks to
// stay under the 64 KB JVM string-constant ceiling.
package nfctables

import (
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"

	"github.com/dexpace/kuri/tools/internal/kotlinlit"
	"github.com/dexpace/kuri/tools/internal/repo"
)

// Field indices in UnicodeData.txt (semicolon-separated). Only the code point,
// combining class, and decomposition columns are consumed.
const (
	fieldCodePoint      = 0
	fieldCombiningClass = 3
	fieldDecomposition  = 5
)

// hexRadix is the base for the hex columns (code point, decomposition targets,
// exclusion entries); the combining-class column is decimal and parsed apart.
const hexRadix = 16

// canonicalPairLength is the decomposition length a primary composite must have:
// exactly a (starter, combining) pair. Singletons and longer mappings are
// excluded from the composition table.
const canonicalPairLength = 2

// chunkBudget is the fixed character width of each emitted string chunk. The
// blobs are pure ASCII, so a byte slice of this width is also a character slice,
// matching the Python blob[i:i+100] slicing.
const chunkBudget = 100

// OutputPath returns the absolute path of the generated NfcData.kt fixture.
func OutputPath() (string, error) {
	root, err := repo.Root()
	if err != nil {
		return "", err
	}
	return filepath.Join(
		root, "kuri", "src", "commonMain", "kotlin", "org", "dexpace",
		"kuri", "idna", "NfcData.kt",
	), nil
}

// unicodeDataPath returns the absolute path of the vendored UnicodeData.txt.
func unicodeDataPath() (string, error) {
	root, err := repo.Root()
	if err != nil {
		return "", err
	}
	return filepath.Join(root, ".claude", "references", "unicode-16.0", "UnicodeData.txt"), nil
}

// exclusionsPath returns the absolute path of the vendored CompositionExclusions.txt.
func exclusionsPath() (string, error) {
	root, err := repo.Root()
	if err != nil {
		return "", err
	}
	return filepath.Join(root, ".claude", "references", "unicode-16.0", "CompositionExclusions.txt"), nil
}

// Generate reads both UCD inputs and returns the complete Kotlin source string.
func Generate() (string, error) {
	dataPath, err := unicodeDataPath()
	if err != nil {
		return "", err
	}
	dataBytes, err := os.ReadFile(dataPath)
	if err != nil {
		return "", err
	}
	ccc, decomposition, err := loadUnicodeData(dataBytes)
	if err != nil {
		return "", err
	}
	exclPath, err := exclusionsPath()
	if err != nil {
		return "", err
	}
	exclBytes, err := os.ReadFile(exclPath)
	if err != nil {
		return "", err
	}
	exclusions, err := loadExclusions(exclBytes)
	if err != nil {
		return "", err
	}
	composition := buildComposition(ccc, decomposition, exclusions)
	return emitKotlin(encodeCCC(ccc), encodeDecomposition(decomposition), encodeComposition(composition)), nil
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

// LoadNfc parses the vendored UCD inputs and returns the combining-class,
// canonical-decomposition, and UAX #15 primary-composite maps (Unicode 16.0) —
// the same data Normalizer decodes at runtime, so the conformance reference
// reproduces NFC exactly (verified byte-for-byte against NfcData.kt).
func LoadNfc() (ccc map[int]int, decomposition map[int][]int, composition map[[2]int]int, err error) {
	dataPath, err := unicodeDataPath()
	if err != nil {
		return nil, nil, nil, err
	}
	dataBytes, err := os.ReadFile(dataPath)
	if err != nil {
		return nil, nil, nil, err
	}
	ccc, decomposition, err = loadUnicodeData(dataBytes)
	if err != nil {
		return nil, nil, nil, err
	}
	exclPath, err := exclusionsPath()
	if err != nil {
		return nil, nil, nil, err
	}
	exclBytes, err := os.ReadFile(exclPath)
	if err != nil {
		return nil, nil, nil, err
	}
	exclusions, err := loadExclusions(exclBytes)
	if err != nil {
		return nil, nil, nil, err
	}
	return ccc, decomposition, buildComposition(ccc, decomposition, exclusions), nil
}

// loadUnicodeData parses UnicodeData.txt into the non-zero combining-class map
// and the canonical-only decomposition map, mirroring the Python load_unicode_data
// exactly. Lines are split only on '\n' (Python line.rstrip("\n")), then on ';'.
// Rows with fewer than six fields (blank lines) are skipped before indexing.
// CCC is decimal and kept only when non-zero; decomposition is kept only when
// non-empty and not a compatibility tag (does not start with '<').
func loadUnicodeData(data []byte) (map[int]int, map[int][]int, error) {
	ccc := map[int]int{}
	decomposition := map[int][]int{}
	for _, line := range strings.Split(string(data), "\n") {
		fields := strings.Split(line, ";")
		if len(fields) <= fieldDecomposition {
			continue
		}
		codePoint, err := strconv.ParseInt(fields[fieldCodePoint], hexRadix, 32)
		if err != nil {
			return nil, nil, fmt.Errorf("nfctables: parsing code point %q: %w", fields[fieldCodePoint], err)
		}
		combining, err := strconv.Atoi(fields[fieldCombiningClass])
		if err != nil {
			return nil, nil, fmt.Errorf("nfctables: parsing combining class %q: %w", fields[fieldCombiningClass], err)
		}
		if combining != 0 {
			ccc[int(codePoint)] = combining
		}
		mapping := strings.TrimSpace(fields[fieldDecomposition])
		if mapping == "" || strings.HasPrefix(mapping, "<") {
			continue
		}
		targets, err := parseHexTokens(mapping)
		if err != nil {
			return nil, nil, err
		}
		decomposition[int(codePoint)] = targets
	}
	return ccc, decomposition, nil
}

// parseHexTokens splits a whitespace-separated hex mapping into a list of code
// points. strings.Fields collapses whitespace runs and trims, matching Python's
// no-argument str.split.
func parseHexTokens(mapping string) ([]int, error) {
	tokens := strings.Fields(mapping)
	targets := make([]int, 0, len(tokens))
	for _, token := range tokens {
		value, err := strconv.ParseInt(token, hexRadix, 32)
		if err != nil {
			return nil, fmt.Errorf("nfctables: parsing decomposition target %q: %w", token, err)
		}
		targets = append(targets, int(value))
	}
	return targets, nil
}

// loadExclusions parses the explicit composition-exclusion set, mirroring the
// Python load_exclusions: the body before the first '#' is stripped, and a
// non-empty body is parsed as a base-16 code point. Section-4 "Non-Starter
// Decompositions" codes are commented out ("# 0344"), so their body is empty and
// they are correctly excluded.
func loadExclusions(data []byte) (map[int]bool, error) {
	exclusions := map[int]bool{}
	for _, line := range strings.Split(string(data), "\n") {
		body := strings.TrimSpace(strings.SplitN(line, "#", 2)[0])
		if body == "" {
			continue
		}
		value, err := strconv.ParseInt(body, hexRadix, 32)
		if err != nil {
			return nil, fmt.Errorf("nfctables: parsing exclusion %q: %w", body, err)
		}
		exclusions[int(value)] = true
	}
	return exclusions, nil
}

// buildComposition derives the UAX #15 primary composites: pair(starter,
// combining) -> composed. A canonical pair cp -> [a, b] yields a composition
// unless cp is an explicit exclusion, the mapping is not a pair, or a is a
// non-starter (CCC != 0). Decomposition entries are iterated in ascending
// code-point order so last-write-wins on any duplicate key matches Python dict
// insertion order (file order); no duplicates exist in 16.0.
func buildComposition(ccc map[int]int, decomposition map[int][]int, exclusions map[int]bool) map[[2]int]int {
	composition := map[[2]int]int{}
	for _, codePoint := range sortedKeys(decomposition) {
		mapping := decomposition[codePoint]
		if len(mapping) != canonicalPairLength {
			continue
		}
		starter, combining := mapping[0], mapping[1]
		if ccc[starter] != 0 || exclusions[codePoint] {
			continue
		}
		composition[[2]int{starter, combining}] = codePoint
	}
	return composition
}

// encodeCCC renders the combining-class blob: "<cpHex>:<cccHex>" records sorted
// by integer code point and joined by ';'.
func encodeCCC(ccc map[int]int) string {
	keys := sortedIntMapKeys(ccc)
	records := make([]string, len(keys))
	for i, cp := range keys {
		records[i] = hx(cp) + ":" + hx(ccc[cp])
	}
	return strings.Join(records, ";")
}

// encodeDecomposition renders the decomposition blob: "<cpHex>=<t1Hex> <t2Hex>..."
// records sorted by integer code point and joined by ';'. Targets are joined by a
// single space.
func encodeDecomposition(decomposition map[int][]int) string {
	keys := sortedKeys(decomposition)
	records := make([]string, len(keys))
	for i, cp := range keys {
		mapping := decomposition[cp]
		targets := make([]string, len(mapping))
		for j, target := range mapping {
			targets[j] = hx(target)
		}
		records[i] = hx(cp) + "=" + strings.Join(targets, " ")
	}
	return strings.Join(records, ";")
}

// encodeComposition renders the composition blob: "<aHex>,<bHex>=<cpHex>" records
// sorted by the (starter, combining) integer pair ascending and joined by ';'.
func encodeComposition(composition map[[2]int]int) string {
	keys := make([][2]int, 0, len(composition))
	for key := range composition {
		keys = append(keys, key)
	}
	sort.Slice(keys, func(i, j int) bool {
		if keys[i][0] != keys[j][0] {
			return keys[i][0] < keys[j][0]
		}
		return keys[i][1] < keys[j][1]
	})
	records := make([]string, len(keys))
	for i, key := range keys {
		records[i] = hx(key[0]) + "," + hx(key[1]) + "=" + hx(composition[key])
	}
	return strings.Join(records, ";")
}

// emitKotlin assembles the complete fixture: license header, suppress block,
// package, the three guidance comment lines, and the three chunked tables in the
// fixed order (CCC, decomposition, composition), terminated by exactly one
// newline. The structure mirrors the Python render_kotlin exactly.
func emitKotlin(cccBlob, decompBlob, compBlob string) string {
	lines := []string{
		kotlinlit.LicenseHeader,
		"",
		"// Generated bulk data, not hand-written logic: the chunked string tables intentionally",
		"// exceed detekt's size heuristics to stay within the 64 KB JVM string-constant limit.",
		kotlinlit.FileSuppress("MatchingDeclarationName"),
		"",
		"package org.dexpace.kuri.idna",
		"",
		"// Compact, generated NFC table data (Unicode 16.0), derived from UnicodeData.txt and",
		"// CompositionExclusions.txt by tools/idna/generate_nfc_tables.py. Do not edit by hand;",
		"// re-run the generator instead. See Normalizer for the decoder and the UAX #15 contract.",
	}
	lines = append(lines, renderVal(
		"NFC_CCC_CHUNKS",
		"Canonical Combining Class records `cpHex:cccHex` (non-zero only), joined by ';'.",
		cccBlob,
	)...)
	lines = append(lines, renderVal(
		"NFC_DECOMPOSITION_CHUNKS",
		"Canonical decomposition records `cpHex=d1 d2...`, joined by ';'.",
		decompBlob,
	)...)
	lines = append(lines, renderVal(
		"NFC_COMPOSITION_CHUNKS",
		"Canonical composition records `aHex,bHex=cpHex`, joined by ';'.",
		compBlob,
	)...)
	return kotlinlit.JoinLines(lines)
}

// renderVal renders one table block, prefixed by a blank line: the KDoc, the
// `internal val ... = listOf(` opener, one quoted chunk per line (each with a
// trailing comma, including the last), and the closing `)`.
func renderVal(name, kdoc, blob string) []string {
	lines := []string{
		"",
		"/** " + kdoc + " */",
		"internal val " + name + ": List<String> =",
		"    listOf(",
	}
	for _, chunk := range chunkBlob(blob) {
		lines = append(lines, "        \""+chunk+"\",")
	}
	return append(lines, "    )")
}

// chunkBlob slices a pure-ASCII blob into fixed-width string chunks, reusing the
// shared byte chunker. Because the blob is ASCII, byte-width slices equal
// character-width slices, matching the Python blob[i:i+100] slicing.
func chunkBlob(blob string) []string {
	byteChunks := kotlinlit.Chunk([]byte(blob), chunkBudget)
	chunks := make([]string, len(byteChunks))
	for i, chunk := range byteChunks {
		chunks[i] = string(chunk)
	}
	return chunks
}

// hx formats a non-negative code point as lowercase hexadecimal with no prefix,
// matching Python's "%x".
func hx(value int) string {
	return strconv.FormatInt(int64(value), hexRadix)
}

// sortedKeys returns the keys of a decomposition map sorted ascending by integer
// code point.
func sortedKeys(m map[int][]int) []int {
	keys := make([]int, 0, len(m))
	for key := range m {
		keys = append(keys, key)
	}
	sort.Ints(keys)
	return keys
}

// sortedIntMapKeys returns the keys of an int-valued map sorted ascending by
// integer code point.
func sortedIntMapKeys(m map[int]int) []int {
	keys := make([]int, 0, len(m))
	for key := range m {
		keys = append(keys, key)
	}
	sort.Ints(keys)
	return keys
}
