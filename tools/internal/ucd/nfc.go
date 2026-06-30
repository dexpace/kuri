// Copyright (c) 2026 dexpace and Omar Aljarrah
// SPDX-License-Identifier: MIT

package ucd

import (
	"fmt"
	"os"
	"sort"
	"strconv"
	"strings"
)

// UnicodeData.txt field indices consumed by the NFC pass (fieldCodePoint is
// shared with the Mark/Virama pass).
const (
	fieldCombiningClass = 3
	fieldDecomposition  = 5
)

// canonicalPairLength is the decomposition length a primary composite must have:
// exactly a (starter, combining) pair. Singletons and longer mappings are
// excluded from the composition table.
const canonicalPairLength = 2

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
	ccc, decomposition, err = loadNfcUnicodeData(dataBytes)
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

// loadNfcUnicodeData parses UnicodeData.txt into the non-zero combining-class map
// and the canonical-only decomposition map, mirroring the Python load_unicode_data
// exactly. Lines are split only on '\n' (Python line.rstrip("\n")), then on ';'.
// Rows with fewer than six fields (blank lines) are skipped before indexing.
// CCC is decimal and kept only when non-zero; decomposition is kept only when
// non-empty and not a compatibility tag (does not start with '<').
func loadNfcUnicodeData(data []byte) (map[int]int, map[int][]int, error) {
	ccc := map[int]int{}
	decomposition := map[int][]int{}
	for _, line := range strings.Split(string(data), "\n") {
		fields := strings.Split(line, ";")
		if len(fields) <= fieldDecomposition {
			continue
		}
		codePoint, err := strconv.ParseInt(fields[fieldCodePoint], hexRadix, 32)
		if err != nil {
			return nil, nil, fmt.Errorf("ucd: parsing code point %q: %w", fields[fieldCodePoint], err)
		}
		combining, err := strconv.Atoi(fields[fieldCombiningClass])
		if err != nil {
			return nil, nil, fmt.Errorf("ucd: parsing combining class %q: %w", fields[fieldCombiningClass], err)
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
			return nil, fmt.Errorf("ucd: parsing decomposition target %q: %w", token, err)
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
			return nil, fmt.Errorf("ucd: parsing exclusion %q: %w", body, err)
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
