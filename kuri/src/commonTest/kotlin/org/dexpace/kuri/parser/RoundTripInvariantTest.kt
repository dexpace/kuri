/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.parser

import org.dexpace.kuri.Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoundTripInvariantTest {
    private fun parsedCases(): List<Url> =
        URL_TEST_CASES
            .filterNot { it.failure }
            .mapNotNull { case -> Url.parseOrNull(case.input, case.base?.let { Url.parseOrNull(it) }) }

    @Test
    fun `serialization is idempotent for every parsable case`() {
        parsedCases().forEach { url ->
            val reparsed = Url.parseOrThrow(url.href)
            assertEquals(url.href, reparsed.href, "href not idempotent for ${url.href}")
        }
    }

    @Test
    fun `newBuilder round-trips to the same href for every parsable case`() {
        parsedCases().forEach { url ->
            assertEquals(url.href, url.newBuilder().build().href, "builder round-trip drifted for ${url.href}")
        }
    }

    @Test
    fun `the invariant corpus is substantial`() {
        assertTrue(parsedCases().size > 500, "expected a substantial parsable corpus: ${parsedCases().size}")
    }
}
