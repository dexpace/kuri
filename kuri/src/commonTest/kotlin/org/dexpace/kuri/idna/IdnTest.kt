/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.idna

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behavioural tests for the public [Idn] facade over the internal [Idna] engine (UTS-46, SPEC §7.4).
 * Verifies the fallible [Idn.toAscii] / best-effort [Idn.toUnicode] split; exhaustive UTS-46
 * behaviour is covered by [IdnaTest] and the WPT conformance suite.
 */
class IdnTest {
    // Non-ASCII literals are built from code points so their stored NFC form is deterministic.
    private val uUmlaut = Char(0x00FC).toString()

    @Test
    fun `toAscii encodes a non-ascii label with the xn-- prefix`() {
        val input = "b" + uUmlaut + "cher.example"
        assertEquals("xn--bcher-kva.example", Idn.toAscii(input).getOrNull())
    }

    @Test
    fun `toAscii lower-cases an all-ascii domain`() {
        assertEquals("example.com", Idn.toAscii("EXAMPLE.COM").getOrNull())
    }

    @Test
    fun `toAscii keeps an existing xn-- label across the round trip`() {
        assertEquals("xn--bcher-kva.example", Idn.toAscii("xn--bcher-kva.example").getOrNull())
    }

    @Test
    fun `toAscii keeps an invalid xn-- label as-is even beside a genuine unicode label`() {
        // "xn--a" is not valid Punycode (it decodes to a disallowed control code point); the
        // web-compat leniency for a malformed xn-- label must hold per label, not per whole
        // domain, so a non-ASCII sibling ("bücher") must not turn this label's failure fatal.
        val input = "xn--a.b" + uUmlaut + "cher"

        assertEquals("xn--a.xn--bcher-kva", Idn.toAscii(input).getOrNull())
    }

    @Test
    fun `toAscii recognizes the ideographic full stop as a per-label leniency separator`() {
        // U+3002 IDEOGRAPHIC FULL STOP is one of the three non-ASCII UTS-46 dot-separator
        // variants mapped to U+002E; the per-label ASCII/non-ASCII gate must see the boundary it
        // forms, not just a literal '.', so "xn--a" still gets the malformed-Punycode leniency.
        val ideographicFullStop = Char(0x3002).toString()
        val input = "xn--a" + ideographicFullStop + "b" + uUmlaut + "cher"
        val expected = Idn.toAscii("xn--a.b" + uUmlaut + "cher")

        assertTrue(expected.isOk())
        assertEquals(expected.getOrNull(), Idn.toAscii(input).getOrNull())
    }

    @Test
    fun `toAscii recognizes the fullwidth full stop as a per-label leniency separator`() {
        // U+FF0E FULLWIDTH FULL STOP is another UTS-46 dot-separator variant mapped to U+002E.
        val fullwidthFullStop = Char(0xFF0E).toString()
        val input = "xn--a" + fullwidthFullStop + "b" + uUmlaut + "cher"
        val expected = Idn.toAscii("xn--a.b" + uUmlaut + "cher")

        assertTrue(expected.isOk())
        assertEquals(expected.getOrNull(), Idn.toAscii(input).getOrNull())
    }

    @Test
    fun `toAscii recognizes the halfwidth ideographic full stop as a per-label leniency separator`() {
        // U+FF61 HALFWIDTH IDEOGRAPHIC FULL STOP is the third UTS-46 dot-separator variant mapped
        // to U+002E.
        val halfwidthIdeographicFullStop = Char(0xFF61).toString()
        val input = "xn--a" + halfwidthIdeographicFullStop + "b" + uUmlaut + "cher"
        val expected = Idn.toAscii("xn--a.b" + uUmlaut + "cher")

        assertTrue(expected.isOk())
        assertEquals(expected.getOrNull(), Idn.toAscii(input).getOrNull())
    }

    @Test
    fun `toAscii rejects a fullwidth label that maps down to a literal xn-- ascii string`() {
        // U+FF58 U+FF4E U+FF0D U+FF0D 'a' (fullwidth x, n, hyphen-minus x2, then ascii 'a') maps
        // down to the literal ASCII text "xn--a" under UTS-46 mapping. The per-label ASCII/
        // non-ASCII routing decision must be made on this original non-ASCII label, not on its
        // post-mapping text, or the label slips past the Punycode decode/validate step that
        // Idna.domainToAscii("xn--a") itself fails.
        val fullwidthLabel = Char(0xFF58).toString() + Char(0xFF4E) + Char(0xFF0D) + Char(0xFF0D) + "a"

        assertFalse(Idn.toAscii(fullwidthLabel).isOk())
    }

    @Test
    fun `toAscii fails on a disallowed code point`() {
        // U+0080 is a plain disallowed code point, so ToASCII must reject it fatally.
        val input = "exa" + Char(0x0080) + "mple.com"
        val result = Idn.toAscii(input)
        assertFalse(result.isOk())
        assertNull(result.getOrNull())
    }

    @Test
    fun `toUnicode decodes an xn-- label back to unicode`() {
        assertEquals("b" + uUmlaut + "cher.example", Idn.toUnicode("xn--bcher-kva.example"))
    }

    @Test
    fun `toUnicode passes an ascii domain through unchanged`() {
        assertEquals("example.com", Idn.toUnicode("example.com"))
    }

    @Test
    fun `toUnicode is total and leaves an undecodable label unchanged`() {
        // A payload that is not valid Punycode is best-effort returned verbatim, never rejected.
        val undecodable = "xn--" + Char(0x00E9)
        assertEquals(undecodable, Idn.toUnicode(undecodable))
    }

    @Test
    fun `toAscii and toUnicode round-trip a unicode domain`() {
        val unicode = "b" + uUmlaut + "cher.example"
        val ascii = assertNotNull(Idn.toAscii(unicode).getOrNull())
        assertEquals("xn--bcher-kva.example", ascii)
        assertEquals(unicode, Idn.toUnicode(ascii))
    }
}
