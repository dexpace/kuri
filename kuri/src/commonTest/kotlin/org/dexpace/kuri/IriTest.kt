/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri

import org.dexpace.kuri.error.HostError
import org.dexpace.kuri.error.ParseResult
import org.dexpace.kuri.error.UriParseError
import org.dexpace.kuri.error.getOrThrow
import org.dexpace.kuri.error.isOk
import org.dexpace.kuri.host.Host
import org.dexpace.kuri.idna.Idna
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Behavioural tests for the RFC 3987 conversion facility [Iri]: the §3.1 IRI-to-URI mapping
 * ([Iri.toUri]) layered on the unchanged strict [Uri] engine, and the §3.2 best-effort display
 * transform ([Iri.toUnicode]). Non-ASCII literals are built from explicit code points so their NFC
 * form is deterministic regardless of how the source file is saved.
 */
class IriTest {
    // U+00E4 ä, U+00FC ü, U+00DF ß — precomposed NFC scalars, built explicitly to pin their form.
    private val aUmlaut: String = Char(0x00E4).toString()
    private val uUmlaut: String = Char(0x00FC).toString()
    private val sharpS: String = Char(0x00DF).toString()

    // U+1F600 GRINNING FACE, a supplementary-plane code point (a UTF-16 surrogate pair).
    private val grinning: String = "\uD83D\uDE00"

    // مثال ("example"): four Arabic letters (Bidi class AL), a valid right-to-left label.
    private val arabicExample: String =
        "${Char(0x0645)}${Char(0x062B)}${Char(0x0627)}${Char(0x0644)}"

    @Test
    fun `maps a non-ascii host to its ace form and yields an all-ascii uri`() {
        val host = "$aUmlaut.example"
        val expected = Idna.domainToAscii(host).getOrThrow()

        val uri = Iri.toUri("http://$host/").getOrThrow()

        assertEquals(Host.RegName(expected), uri.host)
        assertTrue(uri.uriString.all { it.code < 0x80 }, "the mapped uri must be fully ASCII")
    }

    @Test
    fun `percent-encodes a non-ascii path point as utf8 octets`() {
        val uri = Iri.toUri("http://h/fa$sharpS").getOrThrow()

        assertEquals("/fa%C3%9F", uri.path)
    }

    @Test
    fun `percent-encodes non-ascii in userinfo query and fragment`() {
        val uri = Iri.toUri("http://u$uUmlaut@h/p?q=$uUmlaut#f$uUmlaut").getOrThrow()

        assertEquals("u%C3%BC", uri.userInfo)
        assertEquals("q=%C3%BC", uri.query)
        assertEquals("f%C3%BC", uri.fragment)
    }

    @Test
    fun `preserves an all-ascii host verbatim even when it is not idna-valid`() {
        val uri = Iri.toUri("http://foo_bar/").getOrThrow()

        assertEquals(Host.RegName("foo_bar"), uri.host)
    }

    @Test
    fun `leaves an ipv6 literal untouched while encoding the path`() {
        val uri = Iri.toUri("http://[::1]/$uUmlaut").getOrThrow()

        assertEquals("[::1]", uri.hostName)
        assertEquals("/%C3%BC", uri.path)
    }

    @Test
    fun `does not double-encode an existing triplet`() {
        val uri = Iri.toUri("http://h/%20$uUmlaut").getOrThrow()

        assertEquals("/%20%C3%BC", uri.path)
    }

    @Test
    fun `encodes a supplementary-plane path point as its four utf8 octets`() {
        val uri = Iri.toUri("http://h/$grinning").getOrThrow()

        assertEquals("/%F0%9F%98%80", uri.path)
    }

    @Test
    fun `fails when a non-ascii host is idna-invalid`() {
        val leadingCombiningMark = Char(0x0301).toString()

        val result = Iri.toUri("http://${leadingCombiningMark}x/")

        val error = assertIs<ParseResult.Err>(result).error
        val host = assertIs<UriParseError.InvalidHost>(error)
        assertEquals(HostError.IdnaFailed, host.reason)
    }

    @Test
    fun `encodes any non-ascii code point even outside the iri repertoire`() {
        // U+FDD0 is a noncharacter: neither ucschar nor iprivate, yet the converter still encodes it.
        val nonCharacter = Char(0xFDD0).toString()

        val uri = Iri.toUri("http://h/$nonCharacter").getOrThrow()

        assertEquals("/%EF%B7%90", uri.path)
    }

    @Test
    fun `strict uri parse rejects a raw non-ascii host while the facility maps it`() {
        val iri = "http://$aUmlaut.example/"

        assertIs<ParseResult.Err>(Uri.parse(iri))
        assertTrue(Iri.toUri(iri).isOk(), "only the Iri facility accepts a raw non-ascii host")
    }

    @Test
    fun `a valid right-to-left host round-trips through the facility`() {
        val uri = Iri.toUri("http://$arabicExample/").getOrThrow()

        val host = assertIs<Host.RegName>(uri.host)
        assertTrue(host.value.startsWith("xn--"), "an RTL host maps to an ACE label")
        assertTrue(Iri.toUnicode(uri).contains(arabicExample), "the display form restores the RTL label")
    }

    @Test
    fun `a bidi-invalid host label fails conversion`() {
        // U+0628 ب is Bidi class AL (right-to-left); a trailing ASCII 'a' (class L) violates the rule.
        val bidiInvalid = "${Char(0x0628)}a"

        assertIs<ParseResult.Err>(Iri.toUri("http://$bidiInvalid/"))
    }

    @Test
    fun `renders a mapped host back to unicode for display`() {
        val uri = Iri.toUri("http://$aUmlaut.example/").getOrThrow()

        assertTrue(Iri.toUnicode(uri).contains("$aUmlaut.example"))
    }

    @Test
    fun `decodes non-ascii triplet runs for display`() {
        val uri = Uri.parse("http://h/fa%C3%9F").getOrThrow()

        assertEquals("http://h/fa$sharpS", Iri.toUnicode(uri))
    }

    @Test
    fun `keeps ascii triplets literal in the display form`() {
        val uri = Uri.parse("http://h/a%2Fb").getOrThrow()

        assertEquals("http://h/a%2Fb", Iri.toUnicode(uri))
    }

    @Test
    fun `keeps an invalid utf8 triplet run literal in the display form`() {
        val uri = Uri.parse("http://h/%C3%C3").getOrThrow()

        assertEquals("http://h/%C3%C3", Iri.toUnicode(uri))
    }

    @Test
    fun `returns a plain-ascii uri unchanged for display`() {
        val uri = Uri.parse("http://h/a/b?c=d#e").getOrThrow()

        assertEquals("http://h/a/b?c=d#e", Iri.toUnicode(uri))
    }

    @Test
    fun `decodes a supplementary-plane triplet run for display`() {
        val uri = Uri.parse("http://h/%F0%9F%98%80").getOrThrow()

        assertEquals("http://h/$grinning", Iri.toUnicode(uri))
    }
}
