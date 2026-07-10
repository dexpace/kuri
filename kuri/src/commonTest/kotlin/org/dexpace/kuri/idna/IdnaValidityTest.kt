/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.idna

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies the UTS-46 label-validity rules beyond the mapping table (SPEC 7.4): the
 * leading-combining-mark rule via [IdnaValidity.startsWithCombiningMark], the RFC 5892 ContextJ
 * join-control rules (A.1 ZWNJ / A.2 ZWJ) via [IdnaValidity.checkJoiners], and the RFC 5893 Bidi
 * rule via [IdnaValidity.checkBidi]. Code points use `\u` escapes because the controls (U+200C
 * ZWNJ, U+200D ZWJ), the marks, and the RTL letters are zero-width, non-spacing, or non-Latin.
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
    fun `rejects a ZWNJ whose preceding side is not a joiner`() {
        // ASCII 'z' (Non_Joining) precedes the ZWNJ, so the left scan finds no L or D joiner while it is
        // still in bounds (a distinct arm from the off-the-end preceding case).
        assertFalse(IdnaValidity.checkJoiners("z\u200c\u062c"))
    }

    @Test
    fun `rejects a leading ZWNJ at the start of the label`() {
        // The ZWNJ is the first code point: the left scan cursor falls below zero with no joiner found.
        assertFalse(IdnaValidity.checkJoiners("\u200c\u062c"))
    }

    @Test
    fun `rejects a trailing ZWNJ after a left-joining letter`() {
        // U+0628 BEH (Dual_Joining) then a ZWNJ at the end: the right scan runs off the end of the label
        // without finding an R or D joiner.
        assertFalse(IdnaValidity.checkJoiners("\u0628\u200c"))
    }

    @Test
    fun `accepts ZWNJ when Transparent marks separate it from the right joiner`() {
        // U+0628 BEH (D) ZWNJ U+064B FATHATAN (Transparent) U+062C JEEM (D): the right scan skips the
        // Transparent mark and reaches the dual-joining JEEM.
        assertTrue(IdnaValidity.checkJoiners("\u0628\u200c\u064b\u062c"))
    }

    @Test
    fun `accepts a label with no join controls`() {
        assertTrue(IdnaValidity.checkJoiners("example"))
    }

    @Test
    fun `accepts a label with no right-to-left code point as exempt from the Bidi rule`() {
        assertTrue(IdnaValidity.checkBidi("example"))
    }

    @Test
    fun `accepts an empty label under the Bidi rule`() {
        assertTrue(IdnaValidity.checkBidi(""))
    }

    @Test
    fun `rejects an LTR label containing a right-to-left letter`() {
        // 'a' is L, U+05D0 HEBREW ALEF is R: RFC 5893 condition 5 forbids R in an LTR label.
        assertFalse(IdnaValidity.checkBidi("a\u05d0"))
    }

    @Test
    fun `accepts a valid right-to-left label`() {
        // U+05D0 ALEF, U+05D1 BET are both R: first R, all R, ends R.
        assertTrue(IdnaValidity.checkBidi("\u05d0\u05d1"))
    }

    @Test
    fun `accepts a right-to-left label ending in trailing combining marks`() {
        // U+0591 HEBREW ACCENT ETNAHTA is NSM: condition 3 permits trailing NSM after the R end.
        assertTrue(IdnaValidity.checkBidi("\u05d0\u0591"))
    }

    @Test
    fun `accepts a right-to-left label ending in a European number`() {
        // U+05D0 ALEF (R) then ASCII '9' (EN): condition 3 permits an EN end in an RTL label.
        assertTrue(IdnaValidity.checkBidi("\u05d09"))
    }

    @Test
    fun `accepts an Arabic-letter label ending in an Arabic letter`() {
        // U+0627 ALEF and U+0628 BEH are both AL: the AL first code point selects the RTL conditions and
        // the AL end satisfies condition 3.
        assertTrue(IdnaValidity.checkBidi("\u0627\u0628"))
    }

    @Test
    fun `accepts a right-to-left label ending in an Arabic number`() {
        // U+0627 ALEF (AL) then U+0667 ARABIC-INDIC DIGIT SEVEN (AN): condition 3 permits an AN end.
        assertTrue(IdnaValidity.checkBidi("\u0627\u0667"))
    }

    @Test
    fun `rejects a Bidi label starting with an Arabic number`() {
        // U+0667 (AN) makes the label RTL yet condition 1 requires an L R or AL first code point.
        assertFalse(IdnaValidity.checkBidi("\u0667\u0627"))
    }

    @Test
    fun `rejects a Bidi label starting with a European number`() {
        // '1' is EN, U+0627 ARABIC ALEF is AL: condition 1 requires an L, R, or AL first code point.
        assertFalse(IdnaValidity.checkBidi("1\u0627"))
    }

    @Test
    fun `rejects a right-to-left label mixing European and Arabic numbers`() {
        // U+05D0 (R), '9' (EN), U+0667 ARABIC-INDIC DIGIT SEVEN (AN): condition 4 forbids EN with AN.
        assertFalse(IdnaValidity.checkBidi("\u05d09\u0667"))
    }

    @Test
    fun `rejects a right-to-left label with an embedded left-to-right letter`() {
        // U+05D0 ALEF (R), ASCII 'a' (L), U+05D0 ALEF (R): condition 2 forbids an L inside an RTL label.
        assertFalse(IdnaValidity.checkBidi("\u05d0a\u05d0"))
    }

    @Test
    fun `rejects a right-to-left label ending in a separator`() {
        // U+05D0 ALEF (R) then U+002D HYPHEN-MINUS (ES): condition 3 requires the last non-NSM code point
        // to be R, AL, EN, or AN, so an ES end (permitted mid-label by condition 2) is rejected.
        assertFalse(IdnaValidity.checkBidi("\u05d0-"))
    }
}
