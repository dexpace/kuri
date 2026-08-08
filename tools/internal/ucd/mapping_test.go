// Copyright (c) 2026 dexpace and Omar Aljarrah
// SPDX-License-Identifier: MIT

package ucd

import (
	"strings"
	"testing"
)

func TestParseLineBlankAndComment(t *testing.T) {
	for _, line := range []string{"", "   ", "# just a comment", "  # comment  "} {
		got, err := parseLine(line)
		if err != nil {
			t.Fatalf("parseLine(%q): unexpected error: %v", line, err)
		}
		if got != nil {
			t.Fatalf("parseLine(%q) = %+v, want nil", line, got)
		}
	}
}

func TestParseLineValid(t *testing.T) {
	got, err := parseLine("0000..002C     ; disallowed # <control-0000>..<control-002C>")
	if err != nil {
		t.Fatalf("parseLine: unexpected error: %v", err)
	}
	if got == nil {
		t.Fatal("parseLine: got nil, want a range")
	}
	if got.start != 0 || got.end != 0x2C || got.kind != kindDisallowed || got.replacement != "" {
		t.Fatalf("parseLine = %+v, want {start:0 end:0x2C kind:D replacement:\"\"}", *got)
	}
}

func TestParseLineMappedWithReplacement(t *testing.T) {
	got, err := parseLine("0041          ; mapped   ; 0061 # LATIN CAPITAL LETTER A")
	if err != nil {
		t.Fatalf("parseLine: unexpected error: %v", err)
	}
	if got.kind != kindMapped || got.replacement != "a" {
		t.Fatalf("parseLine = %+v, want kind M replacement %q", *got, "a")
	}
	if got.start != 0x41 || got.end != 0x41 {
		t.Fatalf("parseLine range = [%#x, %#x], want [0x41, 0x41]", got.start, got.end)
	}
}

func TestParseLineDeviationDropsReplacement(t *testing.T) {
	// Deviation records carry a replacement field in the source (e.g. ß -> ss),
	// but the runtime kind never uses it, and parseLine must discard it.
	got, err := parseLine("00DF          ; deviation ; 0073 0073")
	if err != nil {
		t.Fatalf("parseLine: unexpected error: %v", err)
	}
	if got.kind != kindDeviation {
		t.Fatalf("parseLine kind = %q, want %q", got.kind, kindDeviation)
	}
	if got.replacement != "" {
		t.Fatalf("parseLine replacement = %q, want empty (deviation targets are discarded)", got.replacement)
	}
}

func TestParseLineAllStatusKeywords(t *testing.T) {
	tests := []struct {
		status   string
		wantKind string
	}{
		{"valid", kindValid},
		{"disallowed_STD3_valid", kindValid},
		{"ignored", kindIgnored},
		{"disallowed", kindDisallowed},
		{"mapped", kindMapped},
		{"disallowed_STD3_mapped", kindMapped},
		{"deviation", kindDeviation},
	}
	for _, tt := range tests {
		t.Run(tt.status, func(t *testing.T) {
			line := "0041; " + tt.status
			if tt.status == "mapped" || tt.status == "disallowed_STD3_mapped" {
				line += "; 0061"
			}
			got, err := parseLine(line)
			if err != nil {
				t.Fatalf("parseLine(%q): unexpected error: %v", line, err)
			}
			if got.kind != tt.wantKind {
				t.Fatalf("parseLine(%q) kind = %q, want %q", line, got.kind, tt.wantKind)
			}
		})
	}
}

func TestParseLineUnknownStatus(t *testing.T) {
	if _, err := parseLine("0041; bogus_status"); err == nil {
		t.Fatal("parseLine with unknown status: expected error, got nil")
	}
}

func TestParseLineMissingStatus(t *testing.T) {
	if _, err := parseLine("0041; "); err == nil {
		t.Fatal("parseLine with empty status field: expected error, got nil")
	}
}

func TestParseLineInvalidCodeRange(t *testing.T) {
	if _, err := parseLine("ZZZZ; valid"); err == nil {
		t.Fatal("parseLine with invalid code range: expected error, got nil")
	}
}

func TestParseLineMissingSeparator(t *testing.T) {
	// A non-blank, non-comment line with no ';' field separator at all must
	// return an error rather than panic on the fields[1] access below.
	if _, err := parseLine("0041"); err == nil {
		t.Fatal("parseLine with no ';' separator: expected error, got nil")
	}
}

func TestLoadRangesGapFreeCoverage(t *testing.T) {
	// A tiny, gap-free, three-range fixture covering the entire code-point space
	// via a single "10FFFF..10FFFF"-adjacent final record would be unwieldy to
	// hand-write; instead craft the minimal input by directly exercising
	// loadRanges' invariant check with ranges that already cover 0..maxCodePoint.
	data := []byte(
		"0000..10FFFE ; valid\n" +
			"10FFFF       ; disallowed\n",
	)
	ranges, err := loadRanges(data)
	if err != nil {
		t.Fatalf("loadRanges: unexpected error: %v", err)
	}
	if len(ranges) != 2 {
		t.Fatalf("loadRanges: got %d ranges, want 2", len(ranges))
	}
	if ranges[0].start != 0 || ranges[0].end != 0x10FFFE {
		t.Errorf("ranges[0] = %+v, want start=0 end=0x10FFFE", ranges[0])
	}
	if ranges[1].start != 0x10FFFF || ranges[1].end != 0x10FFFF {
		t.Errorf("ranges[1] = %+v, want start=0x10FFFF end=0x10FFFF", ranges[1])
	}
}

func TestLoadRangesDetectsGap(t *testing.T) {
	// A gap at U+0002: the file jumps from ending at 0001 to starting at 0003,
	// leaving 0002 uncovered.
	data := []byte(
		"0000..0001 ; valid\n" +
			"0003..10FFFF ; valid\n",
	)
	_, err := loadRanges(data)
	if err == nil {
		t.Fatal("loadRanges with a gap: expected error, got nil")
	}
	if !strings.Contains(err.Error(), "gap/overlap") {
		t.Fatalf("loadRanges error = %v, want a gap/overlap message", err)
	}
}

func TestLoadRangesDetectsOverlap(t *testing.T) {
	// The second range starts before the first one's end, an overlap.
	data := []byte(
		"0000..0005 ; valid\n" +
			"0003..10FFFF ; valid\n",
	)
	_, err := loadRanges(data)
	if err == nil {
		t.Fatal("loadRanges with an overlap: expected error, got nil")
	}
}

func TestLoadRangesDetectsIncompleteCoverage(t *testing.T) {
	// Coverage stops short of 0x10FFFF entirely.
	data := []byte("0000..00FF ; valid\n")
	_, err := loadRanges(data)
	if err == nil {
		t.Fatal("loadRanges with incomplete coverage: expected error, got nil")
	}
	if !strings.Contains(err.Error(), "coverage ends") {
		t.Fatalf("loadRanges error = %v, want a coverage message", err)
	}
}

func TestLoadRangesSortsUnorderedInput(t *testing.T) {
	// Records appear out of code-point order in the file; loadRanges must sort
	// them by start before validating coverage, mirroring the Python sort step.
	data := []byte(
		"0100..10FFFF ; valid\n" +
			"0000..00FF   ; disallowed\n",
	)
	ranges, err := loadRanges(data)
	if err != nil {
		t.Fatalf("loadRanges: unexpected error: %v", err)
	}
	if len(ranges) != 2 || ranges[0].start != 0 || ranges[1].start != 0x100 {
		t.Fatalf("loadRanges did not sort by start: got %+v", ranges)
	}
}

func TestMergeAdjacentSameKindNoReplacement(t *testing.T) {
	in := []idnaRange{
		{start: 0, end: 9, kind: kindValid},
		{start: 10, end: 19, kind: kindValid},
	}
	got := mergeAdjacent(in)
	if len(got) != 1 {
		t.Fatalf("mergeAdjacent: got %d ranges, want 1 merged range", len(got))
	}
	if got[0].start != 0 || got[0].end != 19 {
		t.Fatalf("mergeAdjacent = %+v, want {start:0 end:19}", got[0])
	}
}

func TestMergeAdjacentDifferentKindNotMerged(t *testing.T) {
	in := []idnaRange{
		{start: 0, end: 9, kind: kindValid},
		{start: 10, end: 19, kind: kindDisallowed},
	}
	got := mergeAdjacent(in)
	if len(got) != 2 {
		t.Fatalf("mergeAdjacent: got %d ranges, want 2 (different kinds must not merge)", len(got))
	}
}

func TestMergeAdjacentSameKindDifferentReplacementNotMerged(t *testing.T) {
	in := []idnaRange{
		{start: 0, end: 0, kind: kindMapped, replacement: "a"},
		{start: 1, end: 1, kind: kindMapped, replacement: "b"},
	}
	got := mergeAdjacent(in)
	if len(got) != 2 {
		t.Fatalf("mergeAdjacent: got %d ranges, want 2 (different replacements must not merge)", len(got))
	}
}

func TestMergeAdjacentNonAdjacentNotMerged(t *testing.T) {
	// A gap between end and the next start (here just a difference of more than
	// one) must prevent merging, even though the kinds match.
	in := []idnaRange{
		{start: 0, end: 9, kind: kindValid},
		{start: 11, end: 19, kind: kindValid},
	}
	got := mergeAdjacent(in)
	if len(got) != 2 {
		t.Fatalf("mergeAdjacent: got %d ranges, want 2 (non-adjacent ranges must not merge)", len(got))
	}
}

func TestMergeAdjacentEmpty(t *testing.T) {
	got := mergeAdjacent(nil)
	if len(got) != 0 {
		t.Fatalf("mergeAdjacent(nil) = %+v, want empty", got)
	}
}
