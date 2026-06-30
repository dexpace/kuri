// Copyright (c) 2026 dexpace and Omar Aljarrah
// SPDX-License-Identifier: MIT

// Package ucd holds Unicode Character Database parsing helpers shared by the
// IDNA mapping/validity and NFC generators (semicolon-delimited records,
// code-point ranges, and field comments). It currently exposes hex-scalar field
// parsing; more record/range helpers land as those generators are ported.
package ucd
