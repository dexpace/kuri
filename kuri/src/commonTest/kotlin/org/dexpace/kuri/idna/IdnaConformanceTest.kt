/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.kuri.idna

import org.dexpace.kuri.error.ParseResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs [Idna.domainToAscii] against the official WPT IDNA corpora (IdnaTestV2 + toascii, generated
 * with `--exclude-std3 --exclude-bidi`) modelled as [IDNA_CONFORMANCE_CASES]: a non-null
 * [IdnaCase.expected] is the required ToASCII output, a null one means the input must be rejected
 * (SPEC §7.4, [HOST-26]/[HOST-28]).
 *
 * kuri implements the UTS-46 map / Punycode / re-assemble pipeline but defers three post-mapping
 * steps -- NFC normalization, ContextJ `CheckJoiners`, the leading-combining-mark validity rule --
 * and bundles the Unicode 15.1.0 mapping table while this corpus follows Unicode 16.0. The inputs
 * that fail purely for those reasons are tracked in [IDNA_KNOWN_FAILURES]. None reflect a
 * Punycode/mapping defect (verified offline: zero wrong-output mismatches). The suite ratchets: it
 * fails if any *other* case regresses and if a known failure starts passing without the baseline
 * being regenerated (`tools/idna/generate_conformance_fixture.py`).
 */
class IdnaConformanceTest {
    private val knownFailures: Set<String> = IDNA_KNOWN_FAILURES

    private fun caseMatches(case: IdnaCase): Boolean {
        val result = Idna.domainToAscii(case.input)
        return when (case.expected) {
            null -> result is ParseResult.Err
            else -> result is ParseResult.Ok && result.value == case.expected
        }
    }

    private fun liveFailingInputs(): Set<String> =
        IDNA_CONFORMANCE_CASES.filterNot { caseMatches(it) }.map { it.input }.toSet()

    @Test
    fun `every conformance case outside the known-failures set passes UTS-46 ToASCII`() {
        val unexpected = liveFailingInputs() - knownFailures
        assertTrue(unexpected.isEmpty(), "new IDNA conformance regressions (untracked failures): $unexpected")
    }

    @Test
    fun `the known-failures set exactly equals the live failing set`() {
        // Ratchet: a fixed gap (known failure now passing) or a brand-new failure breaks this until
        // the baseline is regenerated, so the deferred-step debt can never drift silently.
        assertEquals(knownFailures, liveFailingInputs())
    }

    @Test
    fun `every tracked known failure is a real corpus input`() {
        val inputs = IDNA_CONFORMANCE_CASES.map { it.input }.toSet()
        val orphans = knownFailures - inputs
        assertTrue(orphans.isEmpty(), "known failures absent from the corpus: $orphans")
    }

    @Test
    fun `the corpus and known-failures set are non-trivially populated`() {
        assertTrue(IDNA_CONFORMANCE_CASES.size > knownFailures.size, "passing cases should dwarf known failures")
        assertTrue(knownFailures.isNotEmpty(), "deferred steps should yield a tracked baseline")
    }
}
