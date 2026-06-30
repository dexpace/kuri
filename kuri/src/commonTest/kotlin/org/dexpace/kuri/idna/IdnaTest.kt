/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.idna

import org.dexpace.kuri.error.HostError
import org.dexpace.kuri.error.ParseResult
import org.dexpace.kuri.error.UriParseError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exercises [Idna.domainToAscii] / [Idna.domainToUnicode] over the mapping table + Punycode under
 * the `Url`-profile parameter set (SPEC §7.4, [HOST-26]/[HOST-28]). Broad NFC, ContextJ, and Bidi
 * coverage lives in the WPT corpora ([IdnaConformanceTest]); this suite covers the mapping/Punycode
 * round-trips and the decoded-A-label re-validation branches ([Idna.isValidDecodedALabel]).
 */
class IdnaTest {
    private fun ascii(domain: String): String {
        val result = Idna.domainToAscii(domain)
        check(result is ParseResult.Ok) { "expected Ok but was $result" }
        return result.value
    }

    @Test
    fun `passes an already-ASCII domain through unchanged`() {
        assertEquals("example.com", ascii("example.com"))
    }

    @Test
    fun `lowercases an uppercase ASCII domain via the mapping step`() {
        assertEquals("example.com", ascii("EXAMPLE.COM"))
    }

    @Test
    fun `drops an ignored code point during mapping`() {
        // U+00AD SOFT HYPHEN is an ignored code point and is removed from the label.
        assertEquals("example.com", ascii("exa­mple.com"))
    }

    @Test
    fun `encodes a non-ASCII label with the xn-- prefix`() {
        assertEquals("xn--bcher-kva.de", ascii("bücher.de"))
    }

    @Test
    fun `maps then encodes an uppercase non-ASCII label`() {
        // 'B' lowercases to 'b' and U+00DC maps to U+00FC before Punycode encoding.
        assertEquals("xn--bcher-kva.de", ascii("BÜCHER.DE"))
    }

    @Test
    fun `keeps an existing xn-- label across a round trip`() {
        assertEquals("xn--bcher-kva.de", ascii("xn--bcher-kva.de"))
    }

    @Test
    fun `produces an empty ASCII domain for an empty input`() {
        assertEquals("", ascii(""))
    }

    @Test
    fun `fails with IdnaFailed on a disallowed code point`() {
        // U+0080 is a plain disallowed code point (not affected by UseSTD3ASCIIRules).
        val input = "example.com"
        val expected = ParseResult.Err(UriParseError.InvalidHost(input, HostError.IdnaFailed))
        assertEquals(expected, Idna.domainToAscii(input))
    }

    @Test
    fun `fails when an xn-- label cannot be Punycode-decoded`() {
        // 'é' is not a valid Punycode digit, so decoding the payload fails fatally for ToASCII.
        val input = "xn--é.de"
        val expected = ParseResult.Err(UriParseError.InvalidHost(input, HostError.IdnaFailed))
        assertEquals(expected, Idna.domainToAscii(input))
    }

    @Test
    fun `decodes an xn-- label back to Unicode for display`() {
        assertEquals("bücher.de", Idna.domainToUnicode("xn--bcher-kva.de"))
    }

    @Test
    fun `passes an ASCII domain through ToUnicode unchanged`() {
        assertEquals("example.com", Idna.domainToUnicode("example.com"))
    }

    @Test
    fun `leaves an undecodable xn-- label unchanged in ToUnicode`() {
        // ToUnicode is best-effort: a payload that is not valid Punycode is returned verbatim.
        assertEquals("xn--é", Idna.domainToUnicode("xn--é"))
    }

    @Test
    fun `rejects a decoded A-label that is empty`() {
        assertFalse(Idna.isValidDecodedALabel(""))
    }

    @Test
    fun `rejects a decoded A-label that is all ASCII`() {
        // An all-ASCII U-label should never have been ACE-encoded (whatwg/url#760).
        assertFalse(Idna.isValidDecodedALabel("abc"))
    }

    @Test
    fun `rejects a decoded A-label that is not in NFC`() {
        // "e" + U+0301 COMBINING ACUTE ACCENT is the decomposed e-acute; NFC composes it, so V1 rejects.
        val notNfc = "e" + Char(0x0301)
        assertFalse(Idna.isValidDecodedALabel(notNfc))
    }

    @Test
    fun `rejects a decoded A-label that is itself an A-label`() {
        // A decoded label beginning with the ACE prefix is double-encoded (whatwg/url#803). U+00E9 is
        // non-empty, non-ASCII, and in NFC, so the leading "xn--" is the only conjunct that rejects it.
        val doubleEncoded = "xn--" + Char(0x00E9)
        assertFalse(Idna.isValidDecodedALabel(doubleEncoded))
    }

    @Test
    fun `accepts a well-formed decoded A-label`() {
        // Non-empty, carries a non-ASCII code point (U+00FC), already in NFC, and not ACE-prefixed.
        val uLabel = "b" + Char(0x00FC) + "cher"
        assertTrue(Idna.isValidDecodedALabel(uLabel))
    }
}
