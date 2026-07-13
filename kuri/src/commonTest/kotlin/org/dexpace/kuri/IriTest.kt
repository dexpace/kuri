/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri

import org.dexpace.kuri.error.HostError
import org.dexpace.kuri.error.ParseResult
import org.dexpace.kuri.error.UriParseError
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

        assertEquals("/fa%C3%9F", uri.encodedPath)
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
        assertEquals("/%C3%BC", uri.encodedPath)
    }

    @Test
    fun `does not double-encode an existing triplet`() {
        val uri = Iri.toUri("http://h/%20$uUmlaut").getOrThrow()

        assertEquals("/%20%C3%BC", uri.encodedPath)
    }

    @Test
    fun `encodes a supplementary-plane path point as its four utf8 octets`() {
        val uri = Iri.toUri("http://h/$grinning").getOrThrow()

        assertEquals("/%F0%9F%98%80", uri.encodedPath)
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
    fun `rejects a non-ascii code point outside the iri repertoire`() {
        // U+FDD0 is a noncharacter: neither ucschar nor iprivate, so RFC 3987 §2.2 excludes it.
        val nonCharacter = Char(0xFDD0).toString()
        val iri = "http://h/$nonCharacter"

        val error = assertIs<UriParseError.IriInvalidCodePoint>(assertIs<ParseResult.Err>(Iri.toUri(iri)).error)

        assertEquals(0xFDD0, error.codePoint)
        assertEquals(iri.indexOf(nonCharacter), error.at)
    }

    @Test
    fun `accepts an iprivate code point only in the query component`() {
        // U+E000 is iprivate: legal in iquery (RFC 3987 §2.2) but not in path, userinfo, or fragment.
        val iprivate = Char(0xE000).toString()

        val uri = Iri.toUri("http://h/?q=$iprivate").getOrThrow()

        assertEquals("q=%EE%80%80", uri.query)
    }

    @Test
    fun `rejects an iprivate code point in the path`() {
        val iprivate = Char(0xE000).toString()
        val iri = "http://h/$iprivate"

        val error = assertIs<UriParseError.IriInvalidCodePoint>(assertIs<ParseResult.Err>(Iri.toUri(iri)).error)

        assertEquals(0xE000, error.codePoint)
        assertEquals(iri.indexOf(iprivate), error.at)
    }

    @Test
    fun `rejects an iprivate code point in userinfo`() {
        val iprivate = Char(0xE000).toString()
        val iri = "http://u$iprivate@h/"

        assertIs<UriParseError.IriInvalidCodePoint>(assertIs<ParseResult.Err>(Iri.toUri(iri)).error)
    }

    @Test
    fun `rejects an iprivate code point in the fragment`() {
        val iprivate = Char(0xE000).toString()
        val iri = "http://h/#$iprivate"

        assertIs<UriParseError.IriInvalidCodePoint>(assertIs<ParseResult.Err>(Iri.toUri(iri)).error)
    }

    @Test
    fun `rejects a bidi formatting character anywhere in the iri`() {
        // U+200E LEFT-TO-RIGHT MARK is one of the seven formatting characters RFC 3987 §4.1 forbids.
        val lrm = Char(0x200E).toString()
        val iri = "http://h/$lrm"

        val error =
            assertIs<UriParseError.IriBidiFormattingCharacter>(assertIs<ParseResult.Err>(Iri.toUri(iri)).error)

        assertEquals(0x200E, error.codePoint)
        assertEquals(iri.indexOf(lrm), error.at)
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

    @Test
    fun `renders a genuinely encoded replacement character for display`() {
        // %EF%BF%BD is well-formed UTF-8 for U+FFFD, so it round-trips and must decode for display.
        val uri = Uri.parse("http://h/%EF%BF%BD").getOrThrow()

        assertEquals("http://h/" + Char(0xFFFD), Iri.toUnicode(uri))
    }

    @Test
    fun `emits an ipv6 literal host verbatim with its port for display`() {
        // A non-reg-name host takes the non-Idna arm of hostDisplay: the bracketed literal and port
        // are shown exactly as serialized, never run through ToUnicode.
        val uri = Uri.parse("http://[::1]:8080/p").getOrThrow()

        assertEquals("http://[::1]:8080/p", Iri.toUnicode(uri))
    }

    @Test
    fun `renders a hostless opaque uri unchanged for display`() {
        // No authority means the display transform skips the authority section entirely.
        val uri = Uri.parse("mailto:user@example.com").getOrThrow()

        assertEquals("mailto:user@example.com", Iri.toUnicode(uri))
    }

    @Test
    fun `renders a scheme-relative authority with userinfo and port for display`() {
        // A scheme-less reference exercises the no-scheme arm while still emitting userinfo and port.
        val uri = Uri.parse("//user@h:9/p").getOrThrow()

        assertEquals("//user@h:9/p", Iri.toUnicode(uri))
    }

    @Test
    fun `emits an ipv4 literal host verbatim for display`() {
        // An Ipv4 host takes the non-reg-name arm of hostDisplay: the dotted-decimal literal is shown
        // exactly as serialized, never run through IDNA ToUnicode.
        val uri = Uri.parse("http://192.168.0.1/p").getOrThrow()

        assertEquals("http://192.168.0.1/p", Iri.toUnicode(uri))
    }

    @Test
    fun `emits an ipvfuture literal host verbatim for display`() {
        // An IPvFuture host also takes the non-reg-name arm; the bracketed literal is emitted as-is.
        val uri = Uri.parse("foo://[v1.fe]/p").getOrThrow()

        assertEquals("foo://[v1.fe]/p", Iri.toUnicode(uri))
    }

    @Test
    fun `maps a supplementary-plane host label to its punycode form`() {
        // A supplementary code point in the host drives the surrogate-pair arm of the code-point scan;
        // UTS-46 maps the emoji label to its ASCII (Punycode) form rather than rejecting it.
        val uri = Iri.toUri("http://$grinning.example/").getOrThrow()
        val host = assertIs<Host.RegName>(uri.host)
        assertTrue(host.value.startsWith("xn--"), "the emoji label becomes a Punycode label")
        assertTrue(host.value.endsWith(".example"), "the trailing ascii label is preserved")
    }
}
