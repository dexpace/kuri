/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.kuri.idna

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Validates [Normalizer.nfc] against Unicode's own NormalizationTest.txt (Unicode 17.0), modelled as
 * [NFC_CASES] (all five columns c1..c5). The full UAX #15 NFC conformance clause is asserted across
 * every case: `nfc == nfc(source) == nfc(nfc) == nfc(nfd)` and `nfkc == nfc(nfkc) == nfc(nfkd)` — i.e.
 * all five NFC legs — plus targeted algorithmic-Hangul cases.
 */
class NormalizerTest {
    @Test
    fun `nfc matches the NormalizationTest NFC column for every case`() {
        val mismatches = NFC_CASES.filter { Normalizer.nfc(it.source) != it.nfc }
        assertTrue(
            mismatches.isEmpty(),
            "NFC mismatches: ${mismatches.size}/${NFC_CASES.size}; first = ${describe(mismatches.firstOrNull())}",
        )
    }

    @Test
    fun `nfc is idempotent on every NormalizationTest case`() {
        val unstable = NFC_CASES.filter { Normalizer.nfc(it.nfc) != it.nfc }
        assertTrue(unstable.isEmpty(), "nfc(nfc(x)) != nfc(x) for ${unstable.size} cases")
    }

    @Test
    fun `nfc recomposes the NFD column to the NFC form for every case`() {
        // The high-value leg: recomposing from the full canonical decomposition (c3) must reach NFC (c2).
        val mismatches = NFC_CASES.filter { Normalizer.nfc(it.nfd) != it.nfc }
        assertTrue(
            mismatches.isEmpty(),
            "nfc(NFD) != NFC for ${mismatches.size}/${NFC_CASES.size}; " +
                "first = ${describeLeg(mismatches.firstOrNull()?.nfd, mismatches.firstOrNull()?.nfc)}",
        )
    }

    @Test
    fun `nfc leaves the NFKC column unchanged for every case`() {
        val mismatches = NFC_CASES.filter { Normalizer.nfc(it.nfkc) != it.nfkc }
        assertTrue(
            mismatches.isEmpty(),
            "nfc(NFKC) != NFKC for ${mismatches.size}/${NFC_CASES.size}; " +
                "first = ${describeLeg(mismatches.firstOrNull()?.nfkc, mismatches.firstOrNull()?.nfkc)}",
        )
    }

    @Test
    fun `nfc recomposes the NFKD column to the NFKC form for every case`() {
        val mismatches = NFC_CASES.filter { Normalizer.nfc(it.nfkd) != it.nfkc }
        assertTrue(
            mismatches.isEmpty(),
            "nfc(NFKD) != NFKC for ${mismatches.size}/${NFC_CASES.size}; " +
                "first = ${describeLeg(mismatches.firstOrNull()?.nfkd, mismatches.firstOrNull()?.nfkc)}",
        )
    }

    @Test
    fun `nfc returns empty string unchanged`() {
        assertEquals("", Normalizer.nfc(""))
    }

    @Test
    fun `nfc composes a precomposed latin letter from base and combining mark`() {
        // U+0041 A + U+0301 COMBINING ACUTE -> U+00C1 LATIN CAPITAL LETTER A WITH ACUTE.
        assertEquals("Á", Normalizer.nfc("Á"))
        assertEquals("Á", Normalizer.nfc("Á"))
    }

    @Test
    fun `nfc reorders combining marks into canonical order before composing`() {
        // Two combining marks of classes 230 (above) and 220 (below) must sort to (220, 230);
        // here only the lower mark composes onto the base, leaving the above mark trailing.
        val reordered = Normalizer.nfc("q̣̇")
        assertEquals("q̣̇", reordered)
    }

    @Test
    fun `nfc composes a Hangul LV plus T syllable algorithmically`() {
        // U+1100 (L) U+1161 (V) U+11A8 (T) -> U+AC01 (GAG); also via U+AC00 (GA) + T.
        assertEquals("각", Normalizer.nfc("각"))
        assertEquals("각", Normalizer.nfc("각"))
    }

    @Test
    fun `nfc composes a Hangul LV syllable from L and V jamo`() {
        // U+1100 (L) U+1161 (V) -> U+AC00 (GA).
        assertEquals("가", Normalizer.nfc("가"))
    }

    @Test
    fun `nfc fully decomposes and recomposes a Hangul syllable to itself`() {
        // U+D4DB decomposes to L+V+T then recomposes; idempotence on a packed syllable.
        assertEquals("퓛", Normalizer.nfc("퓛"))
    }

    private fun describe(case: NfcCase?): String {
        if (case == null) {
            return "none"
        }
        val actual = Normalizer.nfc(case.source)
        return "source=${hex(case.source)} expected=${hex(case.nfc)} actual=${hex(actual)}"
    }

    private fun describeLeg(
        input: String?,
        expected: String?,
    ): String {
        if (input == null || expected == null) {
            return "none"
        }
        val actual = Normalizer.nfc(input)
        return "input=${hex(input)} expected=${hex(expected)} actual=${hex(actual)}"
    }

    private fun hex(value: String): String = value.map { it.code.toString(HEX_RADIX) }.joinToString(" ")

    private companion object {
        private const val HEX_RADIX: Int = 16
    }
}
