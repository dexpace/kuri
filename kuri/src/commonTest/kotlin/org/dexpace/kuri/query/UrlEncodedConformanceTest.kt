/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.kuri.query

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs every WPT `urlencoded-parser.json` case (modelled as [URL_ENCODED_TEST_CASES]) through
 * [FormUrlEncoded.parse] and ratchets a tracked known-failures baseline (SPEC §10.4, [QUERY-21]).
 *
 * The corpus is the array literal WPT's `url/urlencoded-parser.any.js` feeds to its
 * "URLSearchParams constructed with" subtest: a flat `{input, output: [[name, value], ...]}`
 * shape with no `failure` field, so [FormUrlEncoded.parse]'s return type
 * (`List<Pair<String, String>>`) already matches [UrlEncodedCase.output] with no adaptation. The
 * file's two sibling per-entry subtests (`request.formData()`/`response.formData()`) exercise
 * non-UTF-8 charsets (`windows-1252`, `shift_jis`) and are out of scope, since kuri's form codec
 * is UTF-8-only; they are not vendored.
 *
 * There is no residual divergence: [KNOWN_FAILURES] is empty and the suite is at full
 * conformance. It still ratchets in both directions -- a brand-new failure breaks the
 * untracked-regressions test, and a regression that repopulates the live set breaks the
 * baseline-equality test until the baseline is updated.
 */
class UrlEncodedConformanceTest {
    /** True when parsing [case]'s input reproduces its expected output pairs, in order. */
    private fun caseMatches(case: UrlEncodedCase): Boolean = FormUrlEncoded.parse(case.input) == case.output

    /** The inputs of every case the live parser currently does not satisfy. */
    private fun liveFailingInputs(): Set<String> =
        URL_ENCODED_TEST_CASES.filterNot { caseMatches(it) }.map { it.input }.toSet()

    @Test
    fun `every urlencoded case outside the known-failures set matches WPT`() {
        val untracked = liveFailingInputs() - KNOWN_FAILURES
        assertTrue(untracked.isEmpty(), "new urlencoded-parser regressions (untracked failures): $untracked")
    }

    @Test
    fun `the known-failures set exactly equals the live failing set`() {
        // Ratchet: a fixed gap (tracked failure now passing) or a brand-new failure breaks this
        // until the baseline is regenerated, so the deferred debt can never drift silently.
        assertEquals(KNOWN_FAILURES, liveFailingInputs())
    }

    @Test
    fun `every tracked known failure is a real corpus case`() {
        val inputs = URL_ENCODED_TEST_CASES.map { it.input }.toSet()
        val orphans = KNOWN_FAILURES - inputs
        assertTrue(orphans.isEmpty(), "known failures absent from the corpus: $orphans")
    }

    @Test
    fun `the corpus is non-empty and fully conformant`() {
        assertTrue(URL_ENCODED_TEST_CASES.isNotEmpty(), "the WPT corpus should not be empty")
        val failing = liveFailingInputs()
        assertTrue(failing.isEmpty(), "every WPT case must parse per spec; failing: $failing")
    }

    private companion object {
        /**
         * The tracked baseline of currently-failing case inputs. It is empty: the live form parser
         * satisfies every WPT `urlencoded-parser.json` case. The ratcheting
         * `the known-failures set exactly equals the live failing set` test pins the suite at full
         * conformance: any regression repopulates the live set and breaks the build until this
         * baseline is updated.
         */
        private val KNOWN_FAILURES: Set<String> = emptySet()
    }
}
