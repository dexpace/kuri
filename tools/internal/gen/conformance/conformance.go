// Copyright (c) 2026 dexpace and Omar Aljarrah
// SPDX-License-Identifier: MIT

// Package conformance regenerates the IDNA conformance test fixture
// (IdnaConformanceData.kt) and the tracked known-failures baseline
// (IdnaConformanceKnownFailures.kt) from the WPT IdnaTestV2 and toascii corpora.
package conformance

import "errors"

// Run generates the IDNA conformance fixtures; stub pending the Go port.
func Run(stdout bool) error {
	return errors.New("codegen conformance: not implemented")
}
