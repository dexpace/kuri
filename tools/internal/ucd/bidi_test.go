// Copyright (c) 2026 dexpace and Omar Aljarrah
// SPDX-License-Identifier: MIT

package ucd

import "testing"

func TestLoadBidiUnicodeDataBasic(t *testing.T) {
	data := []byte(
		"0041;LATIN CAPITAL LETTER A;Lu;0;L;;;;;N;;;;;\n" +
			"0627;ARABIC LETTER ALEF;Lo;0;AL;;;;;N;;;;;\n",
	)
	got, err := loadBidiUnicodeData(data)
	if err != nil {
		t.Fatalf("loadBidiUnicodeData: unexpected error: %v", err)
	}
	if len(got) != 2 {
		t.Fatalf("loadBidiUnicodeData: got %d ranges, want 2", len(got))
	}
	if got[0].start != 0x41 || got[0].jtype != "L" {
		t.Errorf("got[0] = %+v, want start=0x41 class=L", got[0])
	}
	if got[1].start != 0x627 || got[1].jtype != "AL" {
		t.Errorf("got[1] = %+v, want start=0x627 class=AL", got[1])
	}
}

func TestLoadBidiUnicodeDataFiltersUntrackedClasses(t *testing.T) {
	// B is Paragraph_Separator, which is not in bidiClasses; such code points
	// must be dropped entirely from the result, not recorded with an empty class.
	data := []byte("2029;PARAGRAPH SEPARATOR;Zp;0;B;;;;;N;;;;;\n")
	got, err := loadBidiUnicodeData(data)
	if err != nil {
		t.Fatalf("loadBidiUnicodeData: unexpected error: %v", err)
	}
	if len(got) != 0 {
		t.Fatalf("loadBidiUnicodeData = %+v, want empty (Paragraph_Separator is untracked)", got)
	}
}

func TestLoadBidiUnicodeDataFirstLastBlock(t *testing.T) {
	// The expanded range takes the Bidi_Class of the Last (closing) row, mirroring
	// the Mark/Virama pass's First/Last handling.
	data := []byte(
		"3400;<CJK Ideograph Extension A, First>;Lo;0;L;;;;;N;;;;;\n" +
			"4DBF;<CJK Ideograph Extension A, Last>;Lo;0;L;;;;;N;;;;;\n",
	)
	got, err := loadBidiUnicodeData(data)
	if err != nil {
		t.Fatalf("loadBidiUnicodeData: unexpected error: %v", err)
	}
	if len(got) != 1 || got[0].start != 0x3400 || got[0].end != 0x4DBF || got[0].jtype != "L" {
		t.Fatalf("got = %+v, want a single range [0x3400, 0x4DBF] class L", got)
	}
}

func TestLoadBidiUnicodeDataSkipsShortLines(t *testing.T) {
	data := []byte("\n0041;A;Lu;0;L;;;;;N;;;;;\n")
	got, err := loadBidiUnicodeData(data)
	if err != nil {
		t.Fatalf("loadBidiUnicodeData: unexpected error: %v", err)
	}
	if len(got) != 1 {
		t.Fatalf("loadBidiUnicodeData: got %d ranges, want 1 (blank line skipped)", len(got))
	}
}

func TestLoadBidiUnicodeDataInvalidCodePoint(t *testing.T) {
	data := []byte("ZZZZ;BOGUS;Lu;0;L;;;;;N;;;;;\n")
	if _, err := loadBidiUnicodeData(data); err == nil {
		t.Fatal("loadBidiUnicodeData with invalid code point: expected error, got nil")
	}
}

func TestLoadBidiUnicodeDataSortsByStart(t *testing.T) {
	// Out-of-order input must come back sorted by start, mirroring the sort step
	// in loadBidiUnicodeData.
	data := []byte(
		"0627;ARABIC LETTER ALEF;Lo;0;AL;;;;;N;;;;;\n" +
			"0041;LATIN CAPITAL LETTER A;Lu;0;L;;;;;N;;;;;\n",
	)
	got, err := loadBidiUnicodeData(data)
	if err != nil {
		t.Fatalf("loadBidiUnicodeData: unexpected error: %v", err)
	}
	if len(got) != 2 || got[0].start != 0x41 || got[1].start != 0x627 {
		t.Fatalf("loadBidiUnicodeData did not sort by start: got %+v", got)
	}
}

func TestBidiClassesTrackedSet(t *testing.T) {
	// Pin the exact set of classes the RFC 5893 rule consults, so an accidental
	// addition or removal in bidi.go is caught here rather than only surfacing as
	// a silent behavior change in the generated table.
	want := []string{"L", "R", "AL", "EN", "ES", "ET", "AN", "CS", "NSM", "BN", "ON"}
	if len(bidiClasses) != len(want) {
		t.Fatalf("bidiClasses has %d entries, want %d: %v", len(bidiClasses), len(want), bidiClasses)
	}
	for _, class := range want {
		if !bidiClasses[class] {
			t.Errorf("bidiClasses[%q] = false, want true", class)
		}
	}
}
