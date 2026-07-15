/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.template

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Direct tests of [pctEncode] and [String.takeCodePoints] (RFC 6570 §3.2.1), exercising edge cases —
 * literal percent-triplet passthrough, non-ASCII and surrogate-pair encoding, prefix truncation of zero
 * or negative length — that a template expansion end-to-end test would only reach incidentally.
 */
class TemplateEncodingTest {
    // Built from explicit code units rather than typed glyphs: a typed 'é' or emoji can be stored by the
    // editor/filesystem in either precomposed or decomposed NFC form, which would silently change the
    // UTF-16 length (and therefore the expected UTF-8 triplets) this test asserts on.
    private val eAcute: String = Char(0x00E9).toString()
    private val grinningFace: String = "${Char(0xD83D)}${Char(0xDE00)}"

    @Test
    fun `pctEncode passes an existing valid triplet through unchanged when reserved is allowed`() {
        assertEquals("50%25off", pctEncode("50%25off", allowReserved = true))
        assertEquals("x%2Fy", pctEncode("x%2Fy", allowReserved = true))
    }

    @Test
    fun `pctEncode encodes a percent sign that is not part of a valid triplet`() {
        assertEquals("ab%25", pctEncode("ab%", allowReserved = true))
        assertEquals("a%251z", pctEncode("a%1z", allowReserved = true))
        assertEquals("a%25z1", pctEncode("a%z1", allowReserved = true))
    }

    @Test
    fun `pctEncode percent-encodes a percent sign when reserved is not allowed`() {
        // Unlike the allowReserved=true case above, an existing "%25" triplet is not left alone: its
        // own '%' is encoded too, since isPercentTriplet's passthrough only applies when reserved
        // characters and pre-encoded triplets are allowed.
        assertEquals("50%2525off", pctEncode("50%25off", allowReserved = false))
    }

    @Test
    fun `pctEncode utf8-encodes a non-ascii bmp character`() {
        assertEquals("caf%C3%A9", pctEncode("caf$eAcute", allowReserved = false))
    }

    @Test
    fun `pctEncode utf8-encodes a supplementary-plane surrogate pair as one unit`() {
        assertEquals("%F0%9F%98%80", pctEncode(grinningFace, allowReserved = false))
    }

    @Test
    fun `takeCodePoints returns empty for zero or negative counts`() {
        assertEquals("", "hello".takeCodePoints(0))
        assertEquals("", "hello".takeCodePoints(-1))
    }

    @Test
    fun `takeCodePoints never splits a surrogate pair`() {
        assertEquals(grinningFace, (grinningFace + "X").takeCodePoints(1))
    }
}
