// Copyright (c) 2026 dexpace and Omar Aljarrah
// SPDX-License-Identifier: MIT

package ucd

import (
	"os"
	"path/filepath"
	"testing"
)

func TestParseCodeRangeSingle(t *testing.T) {
	lo, hi, err := ParseCodeRange("0041")
	if err != nil {
		t.Fatalf("ParseCodeRange: unexpected error: %v", err)
	}
	if lo != 0x41 || hi != 0x41 {
		t.Fatalf("ParseCodeRange(%q) = (%#x, %#x), want (0x41, 0x41)", "0041", lo, hi)
	}
}

func TestParseCodeRangeRange(t *testing.T) {
	lo, hi, err := ParseCodeRange("0000..002C")
	if err != nil {
		t.Fatalf("ParseCodeRange: unexpected error: %v", err)
	}
	if lo != 0 || hi != 0x2C {
		t.Fatalf("ParseCodeRange(%q) = (%#x, %#x), want (0x0, 0x2C)", "0000..002C", lo, hi)
	}
}

func TestParseCodeRangeUppercaseHex(t *testing.T) {
	// UCD files sometimes use uppercase hex letters (e.g. "10FFFF"); ParseInt with
	// radix 16 accepts either case, so this should parse identically to lowercase.
	lo, hi, err := ParseCodeRange("10FFFF")
	if err != nil {
		t.Fatalf("ParseCodeRange: unexpected error: %v", err)
	}
	if lo != 0x10FFFF || hi != 0x10FFFF {
		t.Fatalf("ParseCodeRange(%q) = (%#x, %#x), want (0x10FFFF, 0x10FFFF)", "10FFFF", lo, hi)
	}
}

func TestParseCodeRangeInvalidHex(t *testing.T) {
	if _, _, err := ParseCodeRange("ZZZZ"); err == nil {
		t.Fatal("ParseCodeRange(\"ZZZZ\"): expected error, got nil")
	}
}

func TestParseCodeRangeInvalidHexInRange(t *testing.T) {
	// The high bound is malformed; both branches of the ".." split must be checked.
	if _, _, err := ParseCodeRange("0041..ZZZZ"); err == nil {
		t.Fatal("ParseCodeRange(\"0041..ZZZZ\"): expected error, got nil")
	}
	if _, _, err := ParseCodeRange("ZZZZ..0041"); err == nil {
		t.Fatal("ParseCodeRange(\"ZZZZ..0041\"): expected error, got nil")
	}
}

func TestScalarsToStringSingle(t *testing.T) {
	got, err := ScalarsToString("0044")
	if err != nil {
		t.Fatalf("ScalarsToString: unexpected error: %v", err)
	}
	if want := "D"; got != want {
		t.Fatalf("ScalarsToString(%q) = %q, want %q", "0044", got, want)
	}
}

func TestScalarsToStringMultiple(t *testing.T) {
	// "0044 0307" is D + COMBINING DOT ABOVE, the classic NormalizationTest.txt
	// field encoding this function exists to decode.
	got, err := ScalarsToString("0044 0307")
	if err != nil {
		t.Fatalf("ScalarsToString: unexpected error: %v", err)
	}
	want := string(rune(0x44)) + string(rune(0x307))
	if got != want {
		t.Fatalf("ScalarsToString(%q) = %q, want %q", "0044 0307", got, want)
	}
}

func TestScalarsToStringCollapsesWhitespace(t *testing.T) {
	// Multiple spaces/tabs between tokens, and leading/trailing whitespace, should
	// behave exactly like Python's no-argument str.split.
	got, err := ScalarsToString("  0044   0307\t")
	if err != nil {
		t.Fatalf("ScalarsToString: unexpected error: %v", err)
	}
	want := string(rune(0x44)) + string(rune(0x307))
	if got != want {
		t.Fatalf("ScalarsToString with irregular whitespace = %q, want %q", got, want)
	}
}

func TestScalarsToStringEmpty(t *testing.T) {
	got, err := ScalarsToString("")
	if err != nil {
		t.Fatalf("ScalarsToString: unexpected error: %v", err)
	}
	if got != "" {
		t.Fatalf("ScalarsToString(\"\") = %q, want empty string", got)
	}
}

func TestScalarsToStringAllWhitespace(t *testing.T) {
	got, err := ScalarsToString("   \t  ")
	if err != nil {
		t.Fatalf("ScalarsToString: unexpected error: %v", err)
	}
	if got != "" {
		t.Fatalf("ScalarsToString(all-whitespace) = %q, want empty string", got)
	}
}

func TestScalarsToStringRejectsSurrogateBoundaries(t *testing.T) {
	// D800 is the first UTF-16 high surrogate; it (and the rest of the surrogate
	// block) is not a valid scalar value and must not silently become U+FFFD via
	// WriteRune.
	for _, tok := range []string{"D800", "DFFF", "DC00"} {
		if _, err := ScalarsToString(tok); err == nil {
			t.Fatalf("ScalarsToString(%q): expected error for surrogate code point, got nil", tok)
		}
	}
	// One below the surrogate block and one above must both be accepted.
	if _, err := ScalarsToString("D7FF"); err != nil {
		t.Fatalf("ScalarsToString(\"D7FF\"): unexpected error: %v", err)
	}
	if _, err := ScalarsToString("E000"); err != nil {
		t.Fatalf("ScalarsToString(\"E000\"): unexpected error: %v", err)
	}
}

func TestScalarsToStringRejectsOutOfRange(t *testing.T) {
	// 0x110000 is one past the maximum valid Unicode scalar value.
	if _, err := ScalarsToString("110000"); err == nil {
		t.Fatal("ScalarsToString(\"110000\"): expected error for out-of-range scalar, got nil")
	}
	// The maximum valid scalar itself must be accepted.
	if _, err := ScalarsToString("10FFFF"); err != nil {
		t.Fatalf("ScalarsToString(\"10FFFF\"): unexpected error: %v", err)
	}
}

func TestScalarsToStringRejectsNegative(t *testing.T) {
	// strconv.ParseInt accepts a leading '-', so without an explicit lower-bound
	// check a negative token would sail past the (code > maxScalar) guard and
	// WriteRune would silently fold it to U+FFFD — exactly what the doc comment
	// promises can't happen.
	for _, tok := range []string{"-1", "-0041"} {
		if _, err := ScalarsToString(tok); err == nil {
			t.Fatalf("ScalarsToString(%q): expected error for negative scalar, got nil", tok)
		}
	}
}

func TestScalarsToStringRejectsInvalidHex(t *testing.T) {
	if _, err := ScalarsToString("ZZZZ"); err == nil {
		t.Fatal("ScalarsToString(\"ZZZZ\"): expected error, got nil")
	}
}

func TestScalarsToStringSupplementaryPlane(t *testing.T) {
	// A code point above the BMP must round-trip through WriteRune as a single
	// Go rune (rune is int32, so no truncation), not a UTF-16 surrogate pair.
	got, err := ScalarsToString("1F600")
	if err != nil {
		t.Fatalf("ScalarsToString: unexpected error: %v", err)
	}
	want := string(rune(0x1F600))
	if got != want {
		t.Fatalf("ScalarsToString(%q) = %q, want %q", "1F600", got, want)
	}
}

func TestBundledUnicodeVersionRenderings(t *testing.T) {
	version, err := BundledUnicodeVersion()
	if err != nil {
		t.Fatalf("BundledUnicodeVersion: unexpected error: %v", err)
	}
	if got, want := version.MajorMinor(), "17.0"; got != want {
		t.Errorf("MajorMinor() = %q, want %q", got, want)
	}
	if got, want := version.String(), "17.0.0"; got != want {
		t.Errorf("String() = %q, want %q", got, want)
	}
}

// TestBundledUnicodeVersionFormat exercises the version-pin parser directly
// against a handful of well- and ill-formed pins, independent of whatever
// unicodeVersionDir currently is, so a future version bump can't accidentally
// leave the parser itself untested.
func TestBundledUnicodeVersionFormat(t *testing.T) {
	tests := []struct {
		pin        string
		wantMajor  int
		wantMinor  int
		wantPatch  int
		wantString string
		wantErr    bool
	}{
		{pin: "unicode-17.0", wantMajor: 17, wantMinor: 0, wantPatch: 0, wantString: "17.0.0"},
		{pin: "unicode-15.1.0", wantMajor: 15, wantMinor: 1, wantPatch: 0, wantString: "15.1.0"},
		{pin: "unicode-9.0.1", wantMajor: 9, wantMinor: 0, wantPatch: 1, wantString: "9.0.1"},
		{pin: "17.0", wantErr: true},             // missing "unicode-" prefix
		{pin: "unicode-17", wantErr: true},       // needs at least major.minor
		{pin: "unicode-17.0.0.0", wantErr: true}, // too many components
		{pin: "unicode-17.x", wantErr: true},     // non-numeric component
		{pin: "unicode-+17.0", wantErr: true},    // leading '+' must be rejected
		{pin: "unicode-07.0", wantErr: true},     // non-canonical leading zero
		{pin: "unicode--1.0", wantErr: true},     // negative component
	}
	for _, tt := range tests {
		t.Run(tt.pin, func(t *testing.T) {
			version, err := parseVersionPin(tt.pin)
			if tt.wantErr {
				if err == nil {
					t.Fatalf("parseVersionPin(%q): expected error, got version %v", tt.pin, version)
				}
				return
			}
			if err != nil {
				t.Fatalf("parseVersionPin(%q): unexpected error: %v", tt.pin, err)
			}
			if version.major != tt.wantMajor || version.minor != tt.wantMinor || version.patch != tt.wantPatch {
				t.Fatalf("parseVersionPin(%q) = %+v, want major=%d minor=%d patch=%d",
					tt.pin, version, tt.wantMajor, tt.wantMinor, tt.wantPatch)
			}
			if got := version.String(); got != tt.wantString {
				t.Fatalf("parseVersionPin(%q).String() = %q, want %q", tt.pin, got, tt.wantString)
			}
		})
	}
}

func TestRepoRootFindsMarker(t *testing.T) {
	dir := t.TempDir()
	marker := filepath.Join(dir, rootMarker)
	if err := os.WriteFile(marker, []byte(""), 0o644); err != nil {
		t.Fatalf("writing marker file: %v", err)
	}
	nested := filepath.Join(dir, "a", "b", "c")
	if err := os.MkdirAll(nested, 0o755); err != nil {
		t.Fatalf("creating nested dir: %v", err)
	}
	t.Chdir(nested)

	got, err := repoRoot()
	if err != nil {
		t.Fatalf("repoRoot: unexpected error: %v", err)
	}
	// Resolve symlinks (e.g. /tmp -> /private/tmp on macOS) before comparing so
	// the test isn't platform-dependent.
	wantResolved, err := filepath.EvalSymlinks(dir)
	if err != nil {
		t.Fatalf("EvalSymlinks(%q): %v", dir, err)
	}
	gotResolved, err := filepath.EvalSymlinks(got)
	if err != nil {
		t.Fatalf("EvalSymlinks(%q): %v", got, err)
	}
	if gotResolved != wantResolved {
		t.Fatalf("repoRoot() = %q, want %q", gotResolved, wantResolved)
	}
}

func TestJoiningPathPrefersExtracted(t *testing.T) {
	root := t.TempDir()
	if err := os.WriteFile(filepath.Join(root, rootMarker), nil, 0o644); err != nil {
		t.Fatal(err)
	}
	dir := filepath.Join(root, ".claude", "references", unicodeVersionDir)
	extractedDir := filepath.Join(dir, "extracted")
	if err := os.MkdirAll(extractedDir, 0o755); err != nil {
		t.Fatal(err)
	}
	extracted := filepath.Join(extractedDir, "DerivedJoiningType.txt")
	topLevel := filepath.Join(dir, "DerivedJoiningType.txt")
	for _, p := range []string{extracted, topLevel} {
		if err := os.WriteFile(p, nil, 0o644); err != nil {
			t.Fatal(err)
		}
	}
	t.Chdir(root)
	got, err := joiningPath()
	if err != nil {
		t.Fatalf("joiningPath: unexpected error: %v", err)
	}
	if got != extracted {
		t.Fatalf("joiningPath() = %q, want the extracted/ copy %q", got, extracted)
	}
}

func TestJoiningPathFallsBackToTopLevel(t *testing.T) {
	root := t.TempDir()
	if err := os.WriteFile(filepath.Join(root, rootMarker), nil, 0o644); err != nil {
		t.Fatal(err)
	}
	dir := filepath.Join(root, ".claude", "references", unicodeVersionDir)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	// Only the top-level file exists — no extracted/ subdirectory at all.
	topLevel := filepath.Join(dir, "DerivedJoiningType.txt")
	if err := os.WriteFile(topLevel, nil, 0o644); err != nil {
		t.Fatal(err)
	}
	t.Chdir(root)
	got, err := joiningPath()
	if err != nil {
		t.Fatalf("joiningPath: unexpected error: %v", err)
	}
	if got != topLevel {
		t.Fatalf("joiningPath() = %q, want the top-level fallback %q", got, topLevel)
	}
}

func TestJoiningPathNeitherExists(t *testing.T) {
	root := t.TempDir()
	if err := os.WriteFile(filepath.Join(root, rootMarker), nil, 0o644); err != nil {
		t.Fatal(err)
	}
	t.Chdir(root)
	if _, err := joiningPath(); err == nil {
		t.Fatal("joiningPath: expected error when neither candidate exists, got nil")
	}
}

func TestRepoRootMissingMarker(t *testing.T) {
	dir := t.TempDir()
	t.Chdir(dir)
	if _, err := repoRoot(); err == nil {
		t.Fatal("repoRoot(): expected error when no ancestor has settings.gradle.kts, got nil")
	}
}
