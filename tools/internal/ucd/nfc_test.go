// Copyright (c) 2026 dexpace and Omar Aljarrah
// SPDX-License-Identifier: MIT

package ucd

import "testing"

func TestLoadNfcUnicodeDataCombiningClassAndDecomposition(t *testing.T) {
	// 00C0 (LATIN CAPITAL LETTER A WITH GRAVE) canonically decomposes to 0041 0300.
	// 0300 (COMBINING GRAVE ACCENT) has CCC 230 and no decomposition.
	// 0041 has CCC 0 (omitted from the map, since only non-zero CCC is kept) and
	// no decomposition (blank field).
	data := []byte(
		"0041;LATIN CAPITAL LETTER A;Lu;0;L;;;;;N;;;;;\n" +
			"0300;COMBINING GRAVE ACCENT;Mn;230;NSM;;;;;N;;;;;\n" +
			"00C0;LATIN CAPITAL LETTER A WITH GRAVE;Lu;0;L;0041 0300;;;;N;;;;;\n",
	)
	ccc, decomposition, err := loadNfcUnicodeData(data)
	if err != nil {
		t.Fatalf("loadNfcUnicodeData: unexpected error: %v", err)
	}
	if _, ok := ccc[0x41]; ok {
		t.Error("ccc[0x41] present, want absent (CCC 0 is not recorded)")
	}
	if got, want := ccc[0x300], 230; got != want {
		t.Errorf("ccc[0x300] = %d, want %d", got, want)
	}
	want := []int{0x41, 0x300}
	got := decomposition[0xC0]
	if len(got) != len(want) || got[0] != want[0] || got[1] != want[1] {
		t.Errorf("decomposition[0xC0] = %v, want %v", got, want)
	}
	if _, ok := decomposition[0x300]; ok {
		t.Error("decomposition[0x300] present, want absent (no decomposition field)")
	}
}

func TestLoadNfcUnicodeDataSkipsCompatibilityDecomposition(t *testing.T) {
	// A decomposition field starting with '<' is a compatibility mapping (e.g.
	// "<font> 0041") and must be excluded from the canonical decomposition map.
	data := []byte("00BC;VULGAR FRACTION ONE QUARTER;No;0;ON;<fraction> 0031 2044 0034;;;;N;;;;;\n")
	_, decomposition, err := loadNfcUnicodeData(data)
	if err != nil {
		t.Fatalf("loadNfcUnicodeData: unexpected error: %v", err)
	}
	if _, ok := decomposition[0xBC]; ok {
		t.Error("decomposition[0xBC] present, want absent (compatibility decomposition must be skipped)")
	}
}

func TestLoadNfcUnicodeDataSkipsShortLines(t *testing.T) {
	// A line with fewer than six fields (e.g. blank) must be skipped without error,
	// and the real record after it must still be processed. Using a fixture whose
	// real record contributes both a non-zero CCC and a canonical decomposition
	// means the assertion actually confirms the record was parsed, rather than
	// merely being consistent with it having been dropped too.
	data := []byte("\n00C0;LATIN CAPITAL LETTER A WITH GRAVE;Lu;230;L;0041 0300;;;;N;;;;;\n")
	ccc, decomposition, err := loadNfcUnicodeData(data)
	if err != nil {
		t.Fatalf("loadNfcUnicodeData: unexpected error: %v", err)
	}
	if ccc[0xC0] != 230 {
		t.Errorf("ccc[0xC0] = %d, want 230 (blank line skipped, real record processed)", ccc[0xC0])
	}
	if len(decomposition[0xC0]) != 2 || decomposition[0xC0][0] != 0x41 || decomposition[0xC0][1] != 0x300 {
		t.Errorf("decomposition[0xC0] = %v, want [0x41, 0x300]", decomposition[0xC0])
	}
}

func TestLoadNfcUnicodeDataInvalidCodePoint(t *testing.T) {
	data := []byte("ZZZZ;BOGUS;Lu;0;L;;;;;N;;;;;\n")
	if _, _, err := loadNfcUnicodeData(data); err == nil {
		t.Fatal("loadNfcUnicodeData with invalid code point: expected error, got nil")
	}
}

func TestLoadNfcUnicodeDataInvalidCombiningClass(t *testing.T) {
	data := []byte("0041;A;Lu;NOTANUMBER;L;;;;;N;;;;;\n")
	if _, _, err := loadNfcUnicodeData(data); err == nil {
		t.Fatal("loadNfcUnicodeData with invalid combining class: expected error, got nil")
	}
}

func TestLoadNfcUnicodeDataInvalidDecompositionTarget(t *testing.T) {
	data := []byte("00C0;A WITH GRAVE;Lu;0;L;ZZZZ 0300;;;;N;;;;;\n")
	if _, _, err := loadNfcUnicodeData(data); err == nil {
		t.Fatal("loadNfcUnicodeData with an invalid decomposition target: expected error, got nil")
	}
}

func TestParseHexTokensAcceptsOutOfRangeAndSurrogate(t *testing.T) {
	// Documents a deliberate discrepancy with ScalarsToString: parseHexTokens
	// validates hex syntax only, so an out-of-range or surrogate decomposition
	// target is accepted rather than rejected. See the doc comment on
	// parseHexTokens for why this is intentional (the real corpus never
	// contains such values here) rather than an oversight.
	data := []byte("00C0;A WITH GRAVE;Lu;0;L;110000 D800;;;;N;;;;;\n")
	_, decomposition, err := loadNfcUnicodeData(data)
	if err != nil {
		t.Fatalf("loadNfcUnicodeData: unexpected error: %v", err)
	}
	want := []int{0x110000, 0xD800}
	got := decomposition[0xC0]
	if len(got) != len(want) || got[0] != want[0] || got[1] != want[1] {
		t.Fatalf("decomposition[0xC0] = %v, want %v (out-of-range/surrogate targets accepted)", got, want)
	}
}

func TestLoadExclusions(t *testing.T) {
	data := []byte(
		"# 0344 COMBINING GREEK DIALYTIKA TONOS (Non-Starter Decompositions, commented out)\n" +
			"0F73          # TIBETAN VOWEL SIGN II\n" +
			"\n" +
			"# a pure comment line\n",
	)
	got, err := loadExclusions(data)
	if err != nil {
		t.Fatalf("loadExclusions: unexpected error: %v", err)
	}
	// 0344 appears only inside a comment (the whole line starts with '#'), so its
	// body before '#' is empty and it must NOT be in the exclusion set.
	if got[0x344] {
		t.Error("exclusions[0x344] = true, want false (fully commented line)")
	}
	if !got[0xF73] {
		t.Error("exclusions[0xF73] = false, want true")
	}
}

func TestLoadExclusionsInvalidHex(t *testing.T) {
	data := []byte("ZZZZ\n")
	if _, err := loadExclusions(data); err == nil {
		t.Fatal("loadExclusions with invalid hex: expected error, got nil")
	}
}

func TestBuildCompositionBasicPair(t *testing.T) {
	ccc := map[int]int{0x300: 230}
	decomposition := map[int][]int{0xC0: {0x41, 0x300}}
	exclusions := map[int]bool{}
	got := buildComposition(ccc, decomposition, exclusions)
	if got[[2]int{0x41, 0x300}] != 0xC0 {
		t.Fatalf("composition[{0x41,0x300}] = %#x, want 0xC0", got[[2]int{0x41, 0x300}])
	}
}

func TestBuildCompositionExcludedNotComposed(t *testing.T) {
	ccc := map[int]int{0x300: 230}
	decomposition := map[int][]int{0xC0: {0x41, 0x300}}
	exclusions := map[int]bool{0xC0: true}
	got := buildComposition(ccc, decomposition, exclusions)
	if _, ok := got[[2]int{0x41, 0x300}]; ok {
		t.Fatal("composition contains an explicitly excluded composite, want absent")
	}
}

func TestBuildCompositionNonPairNotComposed(t *testing.T) {
	// A three-element (or singleton) decomposition is not a canonical pair and
	// must never contribute a primary composite.
	ccc := map[int]int{}
	decomposition := map[int][]int{
		0x1E0C: {0x44, 0x323, 0x300}, // hypothetical 3-part mapping
		0x1234: {0x41},               // singleton
	}
	got := buildComposition(ccc, decomposition, map[int]bool{})
	if len(got) != 0 {
		t.Fatalf("composition = %v, want empty (no 2-element decompositions present)", got)
	}
}

func TestBuildCompositionNonStarterFirstElementNotComposed(t *testing.T) {
	// If the first element of the pair is itself a non-starter (CCC != 0), it
	// cannot serve as a composition base and the pair must be skipped.
	ccc := map[int]int{0x300: 230, 0x301: 230}
	decomposition := map[int][]int{0x1000: {0x300, 0x301}}
	got := buildComposition(ccc, decomposition, map[int]bool{})
	if len(got) != 0 {
		t.Fatalf("composition = %v, want empty (starter of the pair is a non-starter)", got)
	}
}

func TestBuildCompositionLastWriteWinsInCodePointOrder(t *testing.T) {
	// Two different composites decompose to the same (starter, combining) pair;
	// buildComposition must resolve the collision using ascending code-point
	// (file) order, so the higher code point (processed last) wins.
	ccc := map[int]int{0x300: 230}
	decomposition := map[int][]int{
		0x1000: {0x41, 0x300},
		0x2000: {0x41, 0x300},
	}
	got := buildComposition(ccc, decomposition, map[int]bool{})
	if got[[2]int{0x41, 0x300}] != 0x2000 {
		t.Fatalf("composition[{0x41,0x300}] = %#x, want 0x2000 (higher code point wins)", got[[2]int{0x41, 0x300}])
	}
}
