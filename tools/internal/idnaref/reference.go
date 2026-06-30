// Copyright (c) 2026 dexpace and Omar Aljarrah
// SPDX-License-Identifier: MIT

// Package idnaref is a faithful Go port of kuri's Idna.domainToAscii (UTS-46
// ToASCII under the Url profile), built on the very Unicode 16.0 data the runtime
// tables decode. The codegen conformance generator runs it over the WPT corpora
// to derive the tracked known-failures baseline. It depends only on internal/ucd
// for its table data.
package idnaref

import (
	"unicode/utf16"

	"github.com/dexpace/kuri/tools/internal/ucd"
)

// Reference is a faithful Go port of kuri's Idna.domainToAscii under the Url
// profile, built on the very Unicode 16.0 data the runtime tables decode (mapping
// table, NFC tables, validity ranges). It deliberately reproduces kuri's
// omissions — no CheckBidi, no re-validation of an already-decoded A-label, no
// host-layer forbidden-code-point or empty-domain checks — so the inputs it fails
// equal kuri's live failing set over the corpus.
type Reference struct {
	table     *mappingTable
	marks     *rangeSet
	viramas   *rangeSet
	joining   *joiningTable
	ccc       map[int]int
	decompose map[int][]int
	compose   map[[2]int]int
}

// NewReference loads every backing table from the vendored UCD inputs.
func NewReference() (*Reference, error) {
	ranges, err := ucd.LoadTable()
	if err != nil {
		return nil, err
	}
	marks, viramas, joining, err := ucd.LoadValidity()
	if err != nil {
		return nil, err
	}
	ccc, decomposition, composition, err := ucd.LoadNfc()
	if err != nil {
		return nil, err
	}
	return &Reference{
		table:     newMappingTable(ranges),
		marks:     newRangeSet(marks),
		viramas:   newRangeSet(viramas),
		joining:   newJoiningTable(joining),
		ccc:       ccc,
		decompose: decomposition,
		compose:   composition,
	}, nil
}

// CaseFails reports whether kuri's domainToAscii fails the case. A required
// rejection (expected == nil) fails when ToASCII unexpectedly succeeds; a
// required output fails when ToASCII errors or yields a different string.
func (r *Reference) CaseFails(input, expected []uint16) bool {
	result, ok := r.domainToAscii(input)
	if expected == nil {
		return ok
	}
	return !ok || !equalUnits(result, expected)
}

// domainToAscii ports Idna.domainToAscii: map -> NFC -> split labels -> per-label
// (decode xn--, validate, re-encode). It returns ok=false on any UTS-46 failure.
func (r *Reference) domainToAscii(domain []uint16) ([]uint16, bool) {
	mapped, ok := r.mapAll(domain)
	if !ok {
		return nil, false
	}
	normalized := r.nfc(mapped)
	var out []uint16
	for index, label := range splitLabels(normalized) {
		processed, labelOK := r.processLabel(label)
		if !labelOK {
			return nil, false
		}
		if index > 0 {
			out = append(out, '.')
		}
		out = append(out, processed...)
	}
	return out, true
}

// mapAll applies the UTS-46 mapping step to every code point, dropping ignored
// points, substituting mapped ones, and failing on the first disallowed point.
func (r *Reference) mapAll(domain []uint16) ([]uint16, bool) {
	var out []uint16
	for _, codePoint := range codePointsOf(domain) {
		rng := r.table.lookup(codePoint)
		switch rng.Kind {
		case kindValid, kindDeviation:
			out = appendCodePoint(out, codePoint)
		case kindMapped:
			out = append(out, utf16.Encode([]rune(rng.Replacement))...)
		case kindIgnored:
			// dropped
		case kindDisallowed:
			return nil, false
		}
	}
	return out, true
}

// processLabel ports Idna.processLabel: an xn-- label is Punycode-decoded first,
// then every label is validated and re-encoded to ASCII.
func (r *Reference) processLabel(label []uint16) ([]uint16, bool) {
	decoded := label
	if hasACEPrefix(label) {
		var ok bool
		decoded, ok = r.punycodeDecode(label[acePrefixLength:])
		if !ok {
			return nil, false
		}
	}
	if !r.validateLabel(decoded) {
		return nil, false
	}
	return r.encodeLabel(decoded)
}

// validateLabel ports Idna.validateLabel: reject a leading combining mark, any
// ContextJ violation, or any code point that is not valid/deviation. kuri does
// not re-apply the mapping rejection here beyond the valid-code-point check, and
// it implements no CheckBidi — both reproduced.
func (r *Reference) validateLabel(label []uint16) bool {
	if r.startsWithCombiningMark(label) {
		return false
	}
	if !r.checkJoiners(label) {
		return false
	}
	for _, codePoint := range codePointsOf(label) {
		kind := r.table.lookup(codePoint).Kind
		if kind != kindValid && kind != kindDeviation {
			return false
		}
	}
	return true
}

// encodeLabel ports Idna.encodeLabel: an all-ASCII label passes through; a label
// with any non-ASCII point becomes xn-- + its Punycode form, or a failure on
// Punycode overflow.
func (r *Reference) encodeLabel(label []uint16) ([]uint16, bool) {
	allASCII := true
	for _, unit := range label {
		if unit >= nonASCIIMin {
			allASCII = false
			break
		}
	}
	if allASCII {
		return label, true
	}
	encoded, ok := r.punycodeEncode(label)
	if !ok {
		return nil, false
	}
	out := make([]uint16, 0, acePrefixLength+len(encoded))
	out = append(out, acePrefix...)
	return append(out, encoded...), true
}

// splitLabels splits on U+002E; an empty domain yields one empty label, matching
// Kotlin's String.split(".").
func splitLabels(domain []uint16) [][]uint16 {
	labels := [][]uint16{}
	current := []uint16{}
	for _, unit := range domain {
		if unit == labelSeparator {
			labels = append(labels, current)
			current = []uint16{}
			continue
		}
		current = append(current, unit)
	}
	return append(labels, current)
}

// hasACEPrefix reports whether label begins with the lowercase "xn--" ACE prefix
// (labels reach here already mapped, hence lowercased).
func hasACEPrefix(label []uint16) bool {
	if len(label) < acePrefixLength {
		return false
	}
	for i := 0; i < acePrefixLength; i++ {
		if label[i] != acePrefix[i] {
			return false
		}
	}
	return true
}

// equalUnits reports whether two UTF-16 sequences are identical unit-for-unit.
func equalUnits(a, b []uint16) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}
