/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.kuri.percent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs every WPT `percent-encoding.json` case carrying a `utf-8` expectation (modelled as
 * [PERCENT_ENCODING_TEST_CASES]) through [PercentCodec.encode] under [PercentEncodeSets.C0_CONTROL]
 * and ratchets a tracked known-failures baseline (SPEC §5.2, [PCT-1]).
 *
 * The corpus's `utf-8` expectations correspond to the C0-control percent-encode behavior over
 * UTF-8 bytes: only C0 controls, DEL, and non-ASCII code points are ever encoded, and the
 * trailing-`A` cases exist precisely to keep an adjacent C0 control from swallowing the following
 * ASCII text. There is no residual divergence: [KNOWN_FAILURES] is empty and the suite is at full
 * conformance. It still ratchets in both directions -- a brand-new failure breaks the
 * untracked-regressions test, and a regression that repopulates the live set breaks the
 * baseline-equality test until the baseline is updated.
 */
class PercentEncodingConformanceTest {
    /** True when encoding [case]'s input under the C0-control set reproduces its `utf-8` expectation. */
    private fun caseMatches(case: PercentEncodingCase): Boolean =
        PercentCodec.encode(case.input, PercentEncodeSets.C0_CONTROL) == case.utf8

    /** The inputs of every case the live codec currently does not satisfy. */
    private fun liveFailingInputs(): Set<String> =
        PERCENT_ENCODING_TEST_CASES.filterNot { caseMatches(it) }.map { it.input }.toSet()

    @Test
    fun `every percent-encoding case outside the known-failures set matches WPT`() {
        val untracked = liveFailingInputs() - KNOWN_FAILURES
        assertTrue(untracked.isEmpty(), "new percent-encoding regressions (untracked failures): $untracked")
    }

    @Test
    fun `the known-failures set exactly equals the live failing set`() {
        // Ratchet: a fixed gap (tracked failure now passing) or a brand-new failure breaks this
        // until the baseline is regenerated, so the deferred debt can never drift silently.
        assertEquals(KNOWN_FAILURES, liveFailingInputs())
    }

    @Test
    fun `every tracked known failure is a real corpus case`() {
        val inputs = PERCENT_ENCODING_TEST_CASES.map { it.input }.toSet()
        val orphans = KNOWN_FAILURES - inputs
        assertTrue(orphans.isEmpty(), "known failures absent from the corpus: $orphans")
    }

    @Test
    fun `the corpus is non-empty and fully conformant`() {
        assertTrue(PERCENT_ENCODING_TEST_CASES.isNotEmpty(), "the WPT corpus should not be empty")
        val failing = liveFailingInputs()
        assertTrue(failing.isEmpty(), "every WPT case must encode per spec; failing: $failing")
    }

    private companion object {
        /**
         * The tracked baseline of currently-failing case inputs. It is empty: the live UTF-8 percent
         * codec satisfies every in-scope (utf-8-carrying) WPT `percent-encoding.json` case under the
         * C0-control set. The ratcheting `the known-failures set exactly equals the live failing set`
         * test pins the suite at full conformance: any regression repopulates the live set and breaks
         * the build until this baseline is updated.
         */
        private val KNOWN_FAILURES: Set<String> = emptySet()
    }
}
