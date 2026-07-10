/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.kuri.serialize

import org.dexpace.kuri.error.ParseResult
import org.dexpace.kuri.parser.ParsedComponents
import org.dexpace.kuri.parser.URL_TEST_CASES
import org.dexpace.kuri.parser.UrlCase
import org.dexpace.kuri.parser.UrlParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The stable key separator joining a case's [UrlCase.input] and [UrlCase.base] into one baseline
 * key. `U+0000` never survives WPT pre-processing in an input or base, so it disambiguates the two
 * halves without collision (mirrors `UrlConformanceTest`).
 */
private const val KEY_SEPARATOR: String = "\u0000"

/** The whole-percent scale used to compare the pass rate without floating-point arithmetic. */
private const val PERCENT_SCALE: Int = 100

/** The floor, in whole percent, that the href round-trip pass rate must never drop below. */
private const val MIN_PASS_PERCENT: Int = 99

/**
 * End-to-end href round-trip conformance: for every non-failure WPT `urltestdata.json` case,
 * `parse -> serialize` must reproduce the WHATWG-serialized URL exactly (SPEC §11.4 round-trip;
 * §11.2 serialization; WHATWG "URL serializer"). This is the capstone validation of layer 1.
 *
 * Each case resolves [UrlCase.input] against the parsed [UrlCase.base] (the WPT bases always parse,
 * so a base failure is surfaced as a real bug, not skipped), then serializes the resulting
 * [ParsedComponents] via [UrlSerializer] and compares against [UrlCase.href]. Only non-failure
 * cases are in scope -- a required-failure input has no canonical serialization.
 *
 * This target is strictly tighter than the component-getter test in `UrlConformanceTest`: the
 * `search`/`hash` getters collapse an absent component into a present-but-empty one, whereas the
 * href distinguishes them (a trailing `?`/`#`), and the href carries the §11.2 [NORM-18] `/.`
 * guard that the bare `pathname` getter omits. The suite ratchets in both directions: a brand-new
 * mismatch breaks the untracked-regressions test, and a closed gap breaks the baseline-equality
 * test until [KNOWN_FAILURES] is regenerated, so the residual can never drift silently.
 */
@Suppress("TooManyFunctions") // small single-purpose round-trip helpers plus the ratchet tests.
class UrlHrefConformanceTest {
    /** Parses a WPT [base], which must succeed; a failure is a genuine bug surfaced here (§8.3). */
    private fun parseBase(base: String): ParsedComponents =
        when (val result = UrlParser.parse(base)) {
            is ParseResult.Ok -> result.value
            is ParseResult.Err -> error("WPT base must parse but failed: $base (${result.error})")
        }

    /**
     * True when [case] round-trips: `parse(input, base)` succeeds and its [UrlSerializer]
     * serialization equals the WPT [UrlCase.href]. A non-failure input that fails to parse counts
     * as a residual (false), since every in-scope case must yield a canonical serialization.
     */
    private fun caseRoundTrips(case: UrlCase): Boolean {
        require(!case.failure) { "href round-trip is defined only for non-failure cases: ${case.input}" }
        val base = case.base?.let { parseBase(it) }
        return when (val result = UrlParser.parse(case.input, base)) {
            is ParseResult.Err -> false
            is ParseResult.Ok -> UrlSerializer.serialize(result.value) == case.href
        }
    }

    /** The stable baseline key for [case]: its input and base joined by [KEY_SEPARATOR]. */
    private fun key(case: UrlCase): String = case.input + KEY_SEPARATOR + (case.base ?: "")

    /** Every in-scope (non-failure) corpus case. */
    private fun roundTripCases(): List<UrlCase> = URL_TEST_CASES.filterNot { it.failure }

    /** The keys of every non-failure case whose live href round-trip currently diverges from WPT. */
    private fun liveFailingKeys(): Set<String> =
        roundTripCases().filterNot { caseRoundTrips(it) }.map { key(it) }.toSet()

    @Test
    fun `every non-failure case outside the known-failures set round-trips to its WHATWG href`() {
        val untracked = liveFailingKeys() - KNOWN_FAILURES
        assertTrue(untracked.isEmpty(), "new href round-trip regressions (untracked failures): $untracked")
    }

    @Test
    fun `the known-failures set exactly equals the live failing set`() {
        // Ratchet: a fixed gap (tracked failure now round-trips) or a brand-new mismatch breaks this
        // until the baseline is regenerated, so the deferred residual can never drift silently.
        assertEquals(KNOWN_FAILURES, liveFailingKeys())
    }

    @Test
    fun `every tracked known failure is a real non-failure corpus case`() {
        val keys = roundTripCases().map { key(it) }.toSet()
        val orphans = KNOWN_FAILURES - keys
        assertTrue(orphans.isEmpty(), "known failures absent from the non-failure corpus: $orphans")
    }

    @Test
    fun `the href round-trip pass rate stays at or above the floor`() {
        val total = roundTripCases().size
        val passing = total - liveFailingKeys().size
        assertTrue(total > KNOWN_FAILURES.size, "the corpus must carry round-tripping cases")
        assertTrue(
            passing * PERCENT_SCALE >= total * MIN_PASS_PERCENT,
            "href round-trip pass rate must stay >= $MIN_PASS_PERCENT%: $passing/$total",
        )
    }

    private companion object {
        /**
         * The tracked baseline of non-failure case keys (`input + U+0000 + base`) whose live href
         * round-trip diverges from WPT. The analysis-derived residual is empty: `UrlConformanceTest`
         * already matches every component getter for all non-failure cases, and the [UrlSerializer]
         * reproduces the only two stricter-than-getter distinctions (present-but-empty `?`/`#`, the
         * §11.2 [NORM-18] `/.` guard). The decoded-A-label re-validation corners are all
         * required-*failure* inputs and so lie outside this test's non-failure scope.
         *
         * The ratcheting `the known-failures set exactly equals the live failing set` test is the
         * authority: any genuine serializer corner it surfaces is fixed in the [UrlSerializer]
         * (against the WHATWG URL serializer), never masked by appending to this baseline.
         */
        private val KNOWN_FAILURES: Set<String> = emptySet()
    }
}
