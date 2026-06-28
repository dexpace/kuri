/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.percent

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Membership tests for the named percent-encode sets against the SPEC §5.1 master matrix and the
 * per-set definitions [PCT-5]-[PCT-13], [PCT-40].
 */
class PercentEncodeSetTest {
    private val allSets =
        listOf(
            PercentEncodeSets.C0_CONTROL,
            PercentEncodeSets.FRAGMENT,
            PercentEncodeSets.QUERY,
            PercentEncodeSets.SPECIAL_QUERY,
            PercentEncodeSets.PATH,
            PercentEncodeSets.USERINFO,
            PercentEncodeSets.COMPONENT,
            PercentEncodeSets.FORM_URLENCODED,
        )

    // Code points outside the printable range that the universal rule [PCT-1] always encodes.
    private val nul = 0x00
    private val unitSeparator = 0x1F
    private val del = 0x7F
    private val firstNonAscii = 0x80
    private val snowman = 0x2603

    private fun assertEncodes(
        set: PercentEncodeSet,
        vararg chars: Char,
    ) {
        for (c in chars) {
            assertTrue(set.shouldEncode(c.code), "expected U+${c.code} to encode")
        }
    }

    private fun assertIdentity(
        set: PercentEncodeSet,
        vararg chars: Char,
    ) {
        for (c in chars) {
            assertFalse(set.shouldEncode(c.code), "expected U+${c.code} to pass identity")
        }
    }

    @Test
    fun `every set encodes c0 controls del and non-ascii per PCT-1`() {
        for (set in allSets) {
            assertTrue(set.shouldEncode(nul))
            assertTrue(set.shouldEncode(unitSeparator))
            assertTrue(set.shouldEncode(del))
            assertTrue(set.shouldEncode(firstNonAscii))
            assertTrue(set.shouldEncode(snowman))
        }
    }

    @Test
    fun `alphanumerics and star dash dot underscore pass identity in every set per PCT-3`() {
        for (set in allSets) {
            assertIdentity(set, 'a', 'Z', '0', '9', '*', '-', '.', '_')
        }
    }

    @Test
    fun `c0 control set passes all printable punctuation per PCT-5`() {
        val set = PercentEncodeSets.C0_CONTROL
        assertIdentity(set, ' ', '"', '#', '<', '>', '?', '%', '/', ':', '@', '`')
    }

    @Test
    fun `fragment set encodes only space quote angle brackets and backtick per PCT-6`() {
        val set = PercentEncodeSets.FRAGMENT
        assertEncodes(set, ' ', '"', '<', '>', '`')
        assertIdentity(set, '#', '?', '\'', '/', ':', '@', '{', '}', '^', '|')
    }

    @Test
    fun `query set encodes space quote hash and angle brackets per PCT-7`() {
        val set = PercentEncodeSets.QUERY
        assertEncodes(set, ' ', '"', '#', '<', '>')
        assertIdentity(set, '\'', '?', '`', '{', '}', '/', ':', '^', '@')
    }

    @Test
    fun `special-query set adds apostrophe to the query set per PCT-8`() {
        val set = PercentEncodeSets.SPECIAL_QUERY
        assertEncodes(set, ' ', '"', '#', '<', '>', '\'')
        assertIdentity(set, '?', '`', '/', '^')
    }

    @Test
    fun `path set adds question mark backtick braces and caret to the query set per PCT-9`() {
        val set = PercentEncodeSets.PATH
        assertEncodes(set, ' ', '"', '#', '<', '>', '?', '`', '{', '}', '^')
        assertIdentity(set, '/', ':', ';', '=', '@', '[', ']', '|', '\'')
    }

    @Test
    fun `userinfo set adds the authority delimiters to the path set per PCT-10`() {
        val set = PercentEncodeSets.USERINFO
        assertEncodes(set, '?', '`', '{', '}', '^', '/', ':', ';', '=', '@', '[', '\\', ']', '|')
        assertIdentity(set, '\'', '$', '&', '+', ',', '!', '(', ')', '~')
    }

    @Test
    fun `component set adds dollar percent ampersand plus and comma to the userinfo set per PCT-40`() {
        val set = PercentEncodeSets.COMPONENT
        assertEncodes(set, '/', ':', '@', '[', '|', '$', '%', '&', '+', ',')
        // encodeURIComponent leaves these unescaped, so they remain identity.
        assertIdentity(set, '\'', '!', '(', ')', '~', '*', '-', '.', '_')
    }

    @Test
    fun `form set encodes everything but alphanumerics and star dash dot underscore per PCT-13`() {
        val set = PercentEncodeSets.FORM_URLENCODED
        assertEncodes(set, ' ', '"', '#', '%', '&', '+', '=', '?', '/', ':', '@', '!', '~', '\'')
        assertIdentity(set, 'a', 'Z', '0', '9', '*', '-', '.', '_')
    }
}
