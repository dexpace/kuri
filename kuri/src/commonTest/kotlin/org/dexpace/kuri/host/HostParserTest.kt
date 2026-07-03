/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.host

import org.dexpace.kuri.ParseProfile
import org.dexpace.kuri.error.HostError
import org.dexpace.kuri.error.ParseResult
import org.dexpace.kuri.error.UriParseError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Tests for [HostParser.parse], the profile-aware host dispatcher of SPEC §7.1 ([HOST-3]).
 *
 * Covers the §13.3 category-G host cases plus the host-layer cross-checks: the `Url`
 * special pipeline (IDNA domain, IPv4 shorthand, IPv6 literal, empty-host rules,
 * forbidden-domain re-scan, [HOST-26]..[HOST-40]), the `Url` non-special opaque path
 * ([HOST-40]), and the `Uri` RFC 3986 host grammar (IP-literal/IPvFuture, IPv4address,
 * reg-name preserve, [HOST-5]/[HOST-24]/[HOST-32]/[HOST-42]).
 */
class HostParserTest {
    // --- Url profile, special scheme -------------------------------------------------

    @Test
    fun `parse yields a lowercase RegName for a plain special domain`() {
        val result = HostParser.parse("example.com", ParseProfile.URL, isSpecial = true)

        assertEquals(ParseResult.Ok(Host.RegName("example.com")), result)
    }

    @Test
    fun `parse lowercases a special domain via IDNA mapping`() {
        val result = HostParser.parse("EXAMPLE.com", ParseProfile.URL, isSpecial = true)

        assertEquals(ParseResult.Ok(Host.RegName("example.com")), result)
    }

    @Test
    fun `parse round-trips an already-ASCII xn-- label`() {
        val result = HostParser.parse("xn--fa-hia.de", ParseProfile.URL, isSpecial = true)

        assertEquals(ParseResult.Ok(Host.RegName("xn--fa-hia.de")), result)
    }

    @Test
    fun `parse ACE-encodes a Unicode special domain`() {
        // bücher.de -> the U+00FC label becomes its Punycode ACE form ([HOST-26]).
        val result = HostParser.parse("bücher.de", ParseProfile.URL, isSpecial = true)

        assertEquals(ParseResult.Ok(Host.RegName("xn--bcher-kva.de")), result)
    }

    @Test
    fun `parse classifies a dotted-decimal special host as Ipv4`() {
        val result = HostParser.parse("192.168.0.1", ParseProfile.URL, isSpecial = true)

        assertEquals(ParseResult.Ok(Host.Ipv4(0xC0A80001.toInt())), result)
    }

    @Test
    fun `parse accepts the Url-profile IPv4 hex shorthand`() {
        // 0x7f.1 -> 127.0.0.1, the last part packed into the low 24 bits ([HOST-22]).
        val result = HostParser.parse("0x7f.1", ParseProfile.URL, isSpecial = true)

        assertEquals(ParseResult.Ok(Host.Ipv4(0x7F000001)), result)
    }

    @Test
    fun `parse reads a bracketed IPv6 literal in the Url profile`() {
        val result = HostParser.parse("[::1]", ParseProfile.URL, isSpecial = true)

        assertEquals(ParseResult.Ok(Host.Ipv6(listOf(0, 0, 0, 0, 0, 0, 0, 1))), result)
    }

    @Test
    fun `parse fails an empty special non-file host with EmptyHost`() {
        val result = HostParser.parse("", ParseProfile.URL, isSpecial = true, isFile = false)

        assertEquals(ParseResult.Err(UriParseError.EmptyHost), result)
    }

    @Test
    fun `parse permits an empty file host as Empty`() {
        val result = HostParser.parse("", ParseProfile.URL, isSpecial = true, isFile = true)

        assertEquals(ParseResult.Ok(Host.Empty), result)
    }

    @Test
    fun `parse rejects a special domain bearing a space`() {
        // U+0020 survives UTS-46 and is caught by the forbidden-domain re-scan ([HOST-30]).
        val result = HostParser.parse("a b.com", ParseProfile.URL, isSpecial = true)

        val err = assertIs<ParseResult.Err>(result)
        val cause = assertIs<UriParseError.ForbiddenHostCodePoint>(err.error)
        assertEquals(' '.code, cause.codePoint)
        assertEquals(1, cause.at)
    }

    @Test
    fun `parse rejects a special domain bearing a number sign`() {
        val result = HostParser.parse("a#b", ParseProfile.URL, isSpecial = true)

        val err = assertIs<ParseResult.Err>(result)
        val cause = assertIs<UriParseError.ForbiddenHostCodePoint>(err.error)
        assertEquals('#'.code, cause.codePoint)
    }

    @Test
    fun `parse rejects an NBSP-mapped special domain`() {
        // U+00A0 maps to U+0020 SPACE under UTS-46, which the re-scan then forbids ([HOST-30]).
        val result = HostParser.parse("www .", ParseProfile.URL, isSpecial = true)

        assertIs<ParseResult.Err>(result)
    }

    // --- Url profile, non-special opaque ---------------------------------------------

    @Test
    fun `parse yields an Opaque host for a non-special scheme`() {
        val result = HostParser.parse("foo", ParseProfile.URL, isSpecial = false)

        assertEquals(ParseResult.Ok(Host.Opaque("foo")), result)
    }

    @Test
    fun `parse preserves an existing percent triplet in an opaque host`() {
        val result = HostParser.parse("%2f", ParseProfile.URL, isSpecial = false)

        assertEquals(ParseResult.Ok(Host.Opaque("%2f")), result)
    }

    @Test
    fun `parse reads a bracketed IPv6 literal for a non-special Url scheme`() {
        // A bracketed literal is IPv6 regardless of scheme specialness ([HOST-4]).
        val result = HostParser.parse("[::1]", ParseProfile.URL, isSpecial = false)

        assertEquals(ParseResult.Ok(Host.Ipv6(listOf(0, 0, 0, 0, 0, 0, 0, 1))), result)
    }

    // --- Uri profile -----------------------------------------------------------------

    @Test
    fun `parse preserves reg-name case in the Uri profile`() {
        // No IDNA and no lowercasing for a Uri reg-name ([HOST-32]).
        val result = HostParser.parse("Example.COM", ParseProfile.URI, isSpecial = false)

        assertEquals(ParseResult.Ok(Host.RegName("Example.COM")), result)
    }

    @Test
    fun `parse reads a bracketed IPv6 literal in the Uri profile`() {
        val result = HostParser.parse("[::1]", ParseProfile.URI, isSpecial = false)

        assertEquals(ParseResult.Ok(Host.Ipv6(listOf(0, 0, 0, 0, 0, 0, 0, 1))), result)
    }

    @Test
    fun `parse reads a bracketed IPvFuture literal in the Uri profile`() {
        val result = HostParser.parse("[v1.fe]", ParseProfile.URI, isSpecial = false)

        assertEquals(ParseResult.Ok(Host.IpFuture("v1.fe")), result)
    }

    @Test
    fun `parse classifies a dotted-decimal Uri host as Ipv4`() {
        val result = HostParser.parse("1.2.3.4", ParseProfile.URI, isSpecial = false)

        assertEquals(ParseResult.Ok(Host.Ipv4(0x01020304)), result)
    }

    @Test
    fun `parse accepts a sub-delim in a Uri reg-name`() {
        val result = HostParser.parse("a!b", ParseProfile.URI, isSpecial = false)

        assertEquals(ParseResult.Ok(Host.RegName("a!b")), result)
    }

    @Test
    fun `parse rejects an invalid reg-name code point in the Uri profile`() {
        // '/' is neither unreserved nor sub-delim nor part of a triplet ([HOST-33]).
        val result = HostParser.parse("a/b", ParseProfile.URI, isSpecial = false)

        val err = assertIs<ParseResult.Err>(result)
        val cause = assertIs<UriParseError.ForbiddenHostCodePoint>(err.error)
        assertEquals('/'.code, cause.codePoint)
        assertEquals(1, cause.at)
    }

    @Test
    fun `parse permits an empty Uri host as Empty`() {
        val result = HostParser.parse("", ParseProfile.URI, isSpecial = false)

        assertEquals(ParseResult.Ok(Host.Empty), result)
    }

    @Test
    fun `parse preserves a percent triplet in a Uri reg-name`() {
        val result = HostParser.parse("a%2Fb", ParseProfile.URI, isSpecial = false)

        assertEquals(ParseResult.Ok(Host.RegName("a%2Fb")), result)
    }

    @Test
    fun `parse fails a Url bracketed literal with no closing bracket`() {
        val result = HostParser.parse("[::1", ParseProfile.URL, isSpecial = true)

        assertIs<ParseResult.Err>(result)
    }

    @Test
    fun `parse fails a Uri bracketed literal with no closing bracket`() {
        // An IP-literal MUST end with `]`; an unterminated one is fatal ([HOST-5]).
        val result = HostParser.parse("[::1", ParseProfile.URI, isSpecial = false)

        assertIs<ParseResult.Err>(result)
    }

    @Test
    fun `parse fails a Uri bracketed IPvFuture that breaks the grammar`() {
        // Begins with 'v' but carries no hex version digit, so the production fails ([HOST-42]).
        val result = HostParser.parse("[v.fe]", ParseProfile.URI, isSpecial = false)

        assertIs<ParseResult.Err>(result)
    }

    // --- RFC 6874 zone identifiers ([HOST-17]/[HOST-18]) -----------------------------

    @Test
    fun `the Url profile rejects a zone id even when the flag is set`() {
        val result =
            HostParser.parse("[fe80::1%25eth0]", ParseProfile.URL, isSpecial = true, allowIpv6ZoneId = true)

        val err = assertIs<ParseResult.Err>(result)
        val cause = assertIs<UriParseError.InvalidHost>(err.error)
        assertEquals(HostError.ZoneIdRejected, cause.reason)
    }

    @Test
    fun `parse accepts an opted-in zone id in the Uri profile`() {
        val result =
            HostParser.parse("[fe80::1%25eth0]", ParseProfile.URI, isSpecial = false, allowIpv6ZoneId = true)

        assertEquals(ParseResult.Ok(Host.Ipv6(listOf(0xFE80, 0, 0, 0, 0, 0, 0, 1), zoneId = "eth0")), result)
    }

    @Test
    fun `parse rejects a zone id with the opt-in off in the Url profile`() {
        val result = HostParser.parse("[fe80::1%25eth0]", ParseProfile.URL, isSpecial = true)

        val err = assertIs<ParseResult.Err>(result)
        val cause = assertIs<UriParseError.InvalidHost>(err.error)
        assertEquals(HostError.ZoneIdRejected, cause.reason)
    }

    @Test
    fun `parse rejects a zone id with the opt-in off in the Uri profile`() {
        val result = HostParser.parse("[fe80::1%25eth0]", ParseProfile.URI, isSpecial = false)

        val err = assertIs<ParseResult.Err>(result)
        val cause = assertIs<UriParseError.InvalidHost>(err.error)
        assertEquals(HostError.ZoneIdRejected, cause.reason)
    }
}
