/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.parser

import org.dexpace.kuri.ParseOptions
import org.dexpace.kuri.Uri
import org.dexpace.kuri.error.ParseResult
import org.dexpace.kuri.error.UriParseError
import org.dexpace.kuri.host.Host
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Structural tests for [IriMapping], the RFC 3987 §3.1 IRI-to-URI mapping. Covers the
 * authority-less split (a scheme with no host) and the plain host-with-port split, complementing the
 * host-centric coverage in the public `IriTest`. The non-ASCII path point is built from an explicit
 * code point so its stored form is deterministic.
 */
internal class IriMappingTest {
    // U+00E9 é — a precomposed NFC scalar, built explicitly to pin its form regardless of the editor.
    private val eAcute: String = Char(0x00E9).toString()

    @Test
    fun `maps a scheme-only iri with no authority`() {
        val result = IriMapping.toUri("foo:b$eAcute")

        val uri = assertIs<ParseResult.Ok<Uri>>(result).value
        assertEquals("foo", uri.scheme)
        assertEquals(null, uri.host)
        assertEquals("b%C3%A9", uri.encodedPath)
    }

    @Test
    fun `maps a plain host with an explicit port`() {
        val result = IriMapping.toUri("http://host.example:8080/caf$eAcute")

        val uri = assertIs<ParseResult.Ok<Uri>>(result).value
        assertEquals(Host.RegName("host.example"), uri.host)
        assertEquals(8080, uri.port)
        assertEquals("/caf%C3%A9", uri.encodedPath)
    }

    @Test
    fun `maps a scheme-less relative iri`() {
        // No scheme colon, so the reassembly skips the scheme append and the whole hier is the path.
        val result = IriMapping.toUri("caf$eAcute/x")

        val uri = assertIs<ParseResult.Ok<Uri>>(result).value
        assertNull(uri.scheme)
        assertEquals("caf%C3%A9/x", uri.encodedPath)
    }

    @Test
    fun `rejects an iri whose scheme candidate is invalid`() {
        // "1" is a colon-preceded but invalid scheme candidate, so detectScheme returns null and the
        // strict engine rejects the reassembled "1:2".
        val result = IriMapping.toUri("1:2")

        assertIs<ParseResult.Err>(result)
    }

    @Test
    fun `maps an authority with no path`() {
        val result = IriMapping.toUri("http://host.example")

        val uri = assertIs<ParseResult.Ok<Uri>>(result).value
        assertEquals(Host.RegName("host.example"), uri.host)
    }

    @Test
    fun `maps a bracketed host with a port`() {
        val result = IriMapping.toUri("http://[::1]:8080/x")

        val uri = assertIs<ParseResult.Ok<Uri>>(result).value
        assertEquals(Host.Ipv6(listOf(0, 0, 0, 0, 0, 0, 0, 1)), uri.host)
        assertEquals(8080, uri.port)
    }

    @Test
    fun `maps a bracketed host with no port`() {
        val result = IriMapping.toUri("http://[::1]/x")

        val uri = assertIs<ParseResult.Ok<Uri>>(result).value
        assertEquals(Host.Ipv6(listOf(0, 0, 0, 0, 0, 0, 0, 1)), uri.host)
        assertNull(uri.port)
    }

    @Test
    fun `rejects an unterminated bracketed host`() {
        val result = IriMapping.toUri("http://[oops/x")

        assertIs<ParseResult.Err>(result)
    }

    @Test
    fun `rejects an iri whose percent-encoded form exceeds a lowered expandedLength`() {
        // Each é maps to the three-code-unit "%C3%A9", so the encoded path is three times the raw
        // run: the raw iri fits inputLength but the expanded ASCII form exceeds expandedLength.
        val iri = "foo:" + eAcute.repeat(10)
        val options = ParseOptions.Builder().expandedLength(20).build()

        val result = IriMapping.toUri(iri, options)

        val error = assertIs<ParseResult.Err>(result).error
        val tooLong = assertIs<UriParseError.InputTooLong>(error)
        assertEquals(20, tooLong.max)
    }

    @Test
    fun `rejects an iri whose IDNA-expanded host exceeds a lowered expandedLength`() {
        // The non-ASCII host runs UTS-46 ToASCII to an xn-- form longer than the raw host; a lowered
        // expandedLength rejects the expanded authority even though the raw iri is short.
        val iri = "http://b${eAcute}cher.example/"
        val options = ParseOptions.Builder().expandedLength(15).build()

        val result = IriMapping.toUri(iri, options)

        val error = assertIs<ParseResult.Err>(result).error
        val tooLong = assertIs<UriParseError.InputTooLong>(error)
        assertEquals(15, tooLong.max)
    }

    @Test
    fun `rejects an iri longer than a lowered inputLength before mapping`() {
        val options = ParseOptions.Builder().inputLength(5).build()

        val result = IriMapping.toUri("foo:abcdef", options)

        val error = assertIs<ParseResult.Err>(result).error
        val tooLong = assertIs<UriParseError.InputTooLong>(error)
        assertEquals(10, tooLong.length)
        assertEquals(5, tooLong.max)
    }
}
