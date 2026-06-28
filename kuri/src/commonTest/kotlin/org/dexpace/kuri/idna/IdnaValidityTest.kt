/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.idna

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies the two deferred UTS-46 label-validity rules (SPEC 7.4): the leading-combining-mark
 * rule via [IdnaValidity.startsWithCombiningMark] and the RFC 5892 ContextJ join-control rules
 * (A.1 ZWNJ / A.2 ZWJ) via [IdnaValidity.checkJoiners]. Code points use `\u` escapes because the
 * controls (U+200C ZWNJ, U+200D ZWJ) and the marks are zero-width or non-spacing.
 */
class IdnaValidityTest {
    @Test
    fun `rejects label starting with a combining mark`() {
        // U+0300 COMBINING GRAVE ACCENT is General_Category Mn.
        assertTrue(IdnaValidity.startsWithCombiningMark("\u0300abc"))
    }

    @Test
    fun `accepts label starting with a base letter`() {
        assertFalse(IdnaValidity.startsWithCombiningMark("abc"))
    }

    @Test
    fun `accepts an empty label as having no leading mark`() {
        assertFalse(IdnaValidity.startsWithCombiningMark(""))
    }

    @Test
    fun `accepts ZWNJ immediately after a Virama`() {
        // U+0915 KA, U+094D DEVANAGARI SIGN VIRAMA (CCC 9), U+200C ZWNJ, U+0915 KA.
        assertTrue(IdnaValidity.checkJoiners("\u0915\u094d\u200c\u0915"))
    }

    @Test
    fun `rejects a bare ZWNJ with no joining context`() {
        // 'a' is Non_Joining and not a Virama, so the ZWNJ has no valid context.
        assertFalse(IdnaValidity.checkJoiners("a\u200cb"))
    }

    @Test
    fun `accepts ZWJ immediately after a Virama`() {
        assertTrue(IdnaValidity.checkJoiners("\u0915\u094d\u200d"))
    }

    @Test
    fun `rejects a bare ZWJ with no preceding Virama`() {
        assertFalse(IdnaValidity.checkJoiners("a\u200db"))
    }

    @Test
    fun `accepts ZWNJ between dual-joining Arabic letters`() {
        // U+0628 BEH (Dual_Joining) ZWNJ U+062C JEEM (Dual_Joining): RFC 5892 A.1 regex clause.
        assertTrue(IdnaValidity.checkJoiners("\u0628\u200c\u062c"))
    }

    @Test
    fun `accepts ZWNJ when only Transparent marks separate the joiners`() {
        // U+064B FATHATAN is Transparent and must be skipped on the left scan.
        assertTrue(IdnaValidity.checkJoiners("\u0628\u064b\u200c\u062c"))
    }

    @Test
    fun `rejects ZWNJ when the following side is not a joiner`() {
        // U+0628 BEH on the left, ASCII 'z' (Non_Joining) on the right.
        assertFalse(IdnaValidity.checkJoiners("\u0628\u200cz"))
    }

    @Test
    fun `accepts a label with no join controls`() {
        assertTrue(IdnaValidity.checkJoiners("example"))
    }
}
