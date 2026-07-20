/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.host

import org.dexpace.kuri.error.HostError
import org.dexpace.kuri.error.ParseResult
import org.dexpace.kuri.error.UriParseError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Tests for [UrlHostParser.parse], the WHATWG host parser of SPEC §7.1 ([HOST-3]).
 *
 * Covers the `Url` special pipeline (IDNA domain, IPv4 shorthand, IPv6 literal,
 * empty-host rules, forbidden-domain re-scan, [HOST-26]..[HOST-40]) and the `Url`
 * non-special opaque path ([HOST-40]).
 */
class UrlHostParserTest {
    // --- Url profile, special scheme -------------------------------------------------

    @Test
    fun `parse yields a lowercase RegName for a plain special domain`() {
        val result = UrlHostParser.parse("example.com", isSpecial = true)

        assertEquals(ParseResult.Ok(Host.RegName("example.com")), result)
    }

    @Test
    fun `parse lowercases a special domain via IDNA mapping`() {
        val result = UrlHostParser.parse("EXAMPLE.com", isSpecial = true)

        assertEquals(ParseResult.Ok(Host.RegName("example.com")), result)
    }

    @Test
    fun `parse round-trips an already-ASCII xn-- label`() {
        val result = UrlHostParser.parse("xn--fa-hia.de", isSpecial = true)

        assertEquals(ParseResult.Ok(Host.RegName("xn--fa-hia.de")), result)
    }

    @Test
    fun `parse ACE-encodes a Unicode special domain`() {
        // bücher.de -> the U+00FC label becomes its Punycode ACE form ([HOST-26]).
        val result = UrlHostParser.parse("bücher.de", isSpecial = true)

        assertEquals(ParseResult.Ok(Host.RegName("xn--bcher-kva.de")), result)
    }

    @Test
    fun `parse classifies a dotted-decimal special host as Ipv4`() {
        val result = UrlHostParser.parse("192.168.0.1", isSpecial = true)

        assertEquals(ParseResult.Ok(Host.Ipv4(0xC0A80001.toInt())), result)
    }

    @Test
    fun `parse accepts the Url-profile IPv4 hex shorthand`() {
        // 0x7f.1 -> 127.0.0.1, the last part packed into the low 24 bits ([HOST-22]).
        val result = UrlHostParser.parse("0x7f.1", isSpecial = true)

        assertEquals(ParseResult.Ok(Host.Ipv4(0x7F000001)), result)
    }

    @Test
    fun `parse reads a bracketed IPv6 literal in the Url profile`() {
        val result = UrlHostParser.parse("[::1]", isSpecial = true)

        assertEquals(ParseResult.Ok(Host.Ipv6(listOf(0, 0, 0, 0, 0, 0, 0, 1))), result)
    }

    @Test
    fun `parse fails an empty special non-file host with EmptyHost`() {
        val result = UrlHostParser.parse("", isSpecial = true, isFile = false)

        assertEquals(ParseResult.Err(UriParseError.EmptyHost), result)
    }

    @Test
    fun `parse permits an empty file host as Empty`() {
        val result = UrlHostParser.parse("", isSpecial = true, isFile = true)

        assertEquals(ParseResult.Ok(Host.Empty), result)
    }

    @Test
    fun `parse rejects a special domain bearing a space`() {
        // U+0020 survives UTS-46 and is caught by the forbidden-domain re-scan ([HOST-30]).
        val result = UrlHostParser.parse("a b.com", isSpecial = true)

        val err = assertIs<ParseResult.Err>(result)
        val cause = assertIs<UriParseError.ForbiddenHostCodePoint>(err.error)
        assertEquals(' '.code, cause.codePoint)
        assertEquals(1, cause.at)
    }

    @Test
    fun `parse rejects a special domain bearing a number sign`() {
        val result = UrlHostParser.parse("a#b", isSpecial = true)

        val err = assertIs<ParseResult.Err>(result)
        val cause = assertIs<UriParseError.ForbiddenHostCodePoint>(err.error)
        assertEquals('#'.code, cause.codePoint)
    }

    @Test
    fun `parse rejects an NBSP-mapped special domain`() {
        // U+00A0 maps to U+0020 SPACE under UTS-46, which the re-scan then forbids ([HOST-30]).
        val result = UrlHostParser.parse("www" + Char(0x00A0) + ".", isSpecial = true)

        assertIs<ParseResult.Err>(result)
    }

    // --- Url profile, non-special opaque ---------------------------------------------

    @Test
    fun `parse yields an Opaque host for a non-special scheme`() {
        val result = UrlHostParser.parse("foo", isSpecial = false)

        assertEquals(ParseResult.Ok(Host.Opaque("foo")), result)
    }

    @Test
    fun `parse preserves an existing percent triplet in an opaque host`() {
        val result = UrlHostParser.parse("%2f", isSpecial = false)

        assertEquals(ParseResult.Ok(Host.Opaque("%2f")), result)
    }

    @Test
    fun `parse reads a bracketed IPv6 literal for a non-special Url scheme`() {
        // A bracketed literal is IPv6 regardless of scheme specialness ([HOST-4]).
        val result = UrlHostParser.parse("[::1]", isSpecial = false)

        assertEquals(ParseResult.Ok(Host.Ipv6(listOf(0, 0, 0, 0, 0, 0, 0, 1))), result)
    }

    @Test
    fun `parse fails a Url bracketed literal with no closing bracket`() {
        val result = UrlHostParser.parse("[::1", isSpecial = true)

        assertIs<ParseResult.Err>(result)
    }

    // --- RFC 6874 zone identifiers ([HOST-17]/[HOST-18]) -----------------------------

    @Test
    fun `the Url profile rejects a zone id`() {
        // The WHATWG URL host parser has no zone-id production, so a `%25`-bearing literal is fatal
        // ([HOST-17]); unlike the Uri parser there is no opt-in flag to relax this.
        val result = UrlHostParser.parse("[fe80::1%25eth0]", isSpecial = true)

        val err = assertIs<ParseResult.Err>(result)
        val cause = assertIs<UriParseError.InvalidHost>(err.error)
        assertEquals(HostError.ZoneIdRejected, cause.reason)
    }
}
