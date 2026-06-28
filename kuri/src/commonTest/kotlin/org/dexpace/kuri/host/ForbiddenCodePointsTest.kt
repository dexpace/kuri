/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.host

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the forbidden code-point predicates against SPEC §7.6 [HOST-36] / [HOST-37].
 */
class ForbiddenCodePointsTest {
    /** The 17 forbidden-host code points of the [HOST-36] table, verbatim. */
    private val forbiddenHost: List<Char> =
        listOf(
            Char(0x00),
            '\t',
            '\n',
            '\r',
            ' ',
            '#',
            '/',
            ':',
            '<',
            '>',
            '?',
            '@',
            '[',
            '\\',
            ']',
            '^',
            '|',
        )

    @Test
    fun `isForbiddenHostCodePoint accepts every HOST-36 table entry`() {
        forbiddenHost.forEach { cp ->
            assertTrue(isForbiddenHostCodePoint(cp), "expected forbidden host: U+${cp.code}")
        }
    }

    @Test
    fun `isForbiddenHostCodePoint rejects ordinary host characters`() {
        listOf('a', 'Z', '0', '9', '.', '-', '_', '~', '%', Char(0x80)).forEach { cp ->
            assertFalse(isForbiddenHostCodePoint(cp), "unexpected forbidden host: U+${cp.code}")
        }
    }

    @Test
    fun `isForbiddenHostCodePoint does not forbid non-NUL non-TAB-LF-CR C0 controls`() {
        // Only NUL, TAB, LF, CR of the C0 range are forbidden hosts; the rest are not ([HOST-36]).
        listOf(Char(0x01), Char(0x08), Char(0x1B), Char(0x1F)).forEach { cp ->
            assertFalse(isForbiddenHostCodePoint(cp), "C0 control wrongly forbidden as host: U+${cp.code}")
        }
    }

    @Test
    fun `isForbiddenDomainCodePoint is a superset of the forbidden-host set`() {
        forbiddenHost.forEach { cp ->
            assertTrue(isForbiddenDomainCodePoint(cp), "host code point must also be domain-forbidden: U+${cp.code}")
        }
    }

    @Test
    fun `isForbiddenDomainCodePoint additionally forbids percent every C0 control and DELETE`() {
        val extra = listOf('%', Char(0x00), Char(0x01), Char(0x1F), Char(0x7F))
        extra.forEach { cp ->
            assertTrue(isForbiddenDomainCodePoint(cp), "expected forbidden domain: U+${cp.code}")
        }
    }

    @Test
    fun `isForbiddenDomainCodePoint does not forbid non-ASCII or ordinary characters`() {
        listOf('a', 'Z', '0', '-', '.', Char(0x80), Char(0xFF)).forEach { cp ->
            assertFalse(isForbiddenDomainCodePoint(cp), "unexpected forbidden domain: U+${cp.code}")
        }
    }
}
