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
    fun `toAscii keeps an invalid xn-- label as-is when the whole domain is ascii`() {
        // "xn--pokxncvks" is not valid Punycode, but per [HOST-48] an all-ASCII domain's ToASCII
        // failure is only a validation error, never fatal, for web compatibility — the domain is
        // kept lowercased verbatim rather than rejected.
        assertEquals("xn--pokxncvks.example", Idn.toAscii("xn--pokxncvks.example").getOrNull())
    }

    @Test
    fun `toAscii fails on an invalid xn-- label beside a genuine unicode sibling`() {
        // "xn--a" is not valid Punycode (it decodes to a disallowed control code point). Per
        // [HOST-48], the web-compat ToASCII leniency applies only when the *whole* percent-decoded
        // domain is ASCII; here a non-ASCII sibling label ("bücher") makes the domain non-ASCII, so
        // the full UTS-46 pipeline runs over the whole domain and "xn--a"'s failure is fatal, not
        // rescued by the leniency (regression test for #107).
        val input = "xn--a.b" + uUmlaut + "cher"

        assertFalse(Idn.toAscii(input).isOk())
    }

    @Test
    fun `toAscii rejects a fullwidth domain that maps down to a literal xn-- ascii string`() {
        // U+FF58 U+FF4E U+FF0D U+FF0D 'a' (fullwidth x, n, hyphen-minus x2, then ascii 'a') maps
        // down to the literal ASCII text "xn--a" under UTS-46 mapping. The ASCII/non-ASCII routing
        // decision is made on this original, pre-mapping domain text — which is non-ASCII — so it
        // runs the full UTS-46 pipeline rather than being kept verbatim, and fails the same way
        // Idna.domainToAscii("xn--a") itself fails.
        val fullwidthLabel = Char(0xFF58).toString() + Char(0xFF4E) + Char(0xFF0D) + Char(0xFF0D) + "a"

        assertFalse(Idn.toAscii(fullwidthLabel).isOk())
    }

    @Test
    fun `toAscii fails on an invalid xn-- label separated only by a non-ascii dot variant`() {
        // U+3002 IDEOGRAPHIC FULL STOP is itself a non-ASCII code point, so a domain using it as a
        // separator is not an ASCII string even if every label is otherwise plain ASCII text; per
        // [HOST-48] the whole-domain leniency gate does not apply, so "xn--pokxncvks"'s invalid
        // Punycode is fatal for the whole domain, not leniently kept.
        val ideographicFullStop = Char(0x3002).toString()
        val input = "xn--pokxncvks" + ideographicFullStop + "example"

        assertFalse(Idn.toAscii(input).isOk())
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
