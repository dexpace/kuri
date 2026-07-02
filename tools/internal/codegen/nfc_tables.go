// Copyright (c) 2026 dexpace and Omar Aljarrah
// SPDX-License-Identifier: MIT

// The nfc-tables generator ports the former Python generator: it renders the
// combining-class, canonical-decomposition, and UAX #15 primary-composite maps
// (loaded by ucd from the vendored UnicodeData.txt and CompositionExclusions.txt)
// into the Kotlin NfcData.kt fixture byte-for-byte. Three compact, chunked string
// tables are produced, each sliced into fixed-width ASCII chunks to stay under the
// 64 KB JVM string-constant ceiling.

package codegen

import (
	"sort"
	"strconv"
	"strings"

	"github.com/dexpace/kuri/tools/internal/ucd"
)

// hexRadix is the base for the hex columns emitted by hx.
const hexRadix = 16

// chunkBudget is the fixed character width of each emitted string chunk. The
// blobs are pure ASCII, so a byte slice of this width is also a character slice,
// matching the Python blob[i:i+100] slicing.
const chunkBudget = 100

// generateNfcTables loads the NFC maps and returns the complete Kotlin source
// string.
func generateNfcTables() (string, error) {
	version, err := ucd.BundledUnicodeVersion()
	if err != nil {
		return "", err
	}
	ccc, decomposition, composition, err := ucd.LoadNfc()
	if err != nil {
		return "", err
	}
	return emitKotlin(version.MajorMinor(), encodeCCC(ccc), encodeDecomposition(decomposition), encodeComposition(composition)), nil
}

// encodeCCC renders the combining-class blob: "<cpHex>:<cccHex>" records sorted
// by integer code point and joined by ';'.
func encodeCCC(ccc map[int]int) string {
	keys := sortedKeys(ccc)
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
func emitKotlin(version, cccBlob, decompBlob, compBlob string) string {
	lines := []string{LicenseHeader, ""}
	lines = append(lines, FileSuppressBlock([]string{
		"// Generated bulk data, not hand-written logic: the chunked string tables intentionally",
		"// exceed detekt's size heuristics to stay within the 64 KB JVM string-constant limit.",
	}, "MatchingDeclarationName")...)
	lines = append(lines,
		"package org.dexpace.kuri.idna",
		"",
		"// Compact, generated NFC table data (Unicode "+version+"), derived from UnicodeData.txt and",
		"// CompositionExclusions.txt by ./gradlew generateNfcTables. Do not edit by hand;",
		"// re-run the generator instead. See Normalizer for the decoder and the UAX #15 contract.",
	)
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
	return JoinLines(lines)
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
	for _, chunk := range nfcChunkBlob(blob) {
		lines = append(lines, "        \""+chunk+"\",")
	}
	return append(lines, "    )")
}

// nfcChunkBlob slices a pure-ASCII blob into fixed-width string chunks, reusing the
// shared byte chunker. Because the blob is ASCII, byte-width slices equal
// character-width slices, matching the Python blob[i:i+100] slicing.
func nfcChunkBlob(blob string) []string {
	byteChunks := Chunk([]byte(blob), chunkBudget)
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

// sortedKeys returns the integer code-point keys of m sorted ascending,
// regardless of the map's value type (combining classes or decomposition lists).
func sortedKeys[V any](m map[int]V) []int {
	keys := make([]int, 0, len(m))
	for key := range m {
		keys = append(keys, key)
	}
	sort.Ints(keys)
	return keys
}
