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
 * Tests for [UrlHostParser.parse] and [UriHostParser.parse], the per-spec host parsers of §7.1 ([HOST-3]).
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
        val result = UrlHostParser.parse("www .", isSpecial = true)

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

    // --- Uri profile -----------------------------------------------------------------

    @Test
    fun `parse preserves reg-name case in the Uri profile`() {
        // No IDNA and no lowercasing for a Uri reg-name ([HOST-32]).
        val result = UriHostParser.parse("Example.COM")

        assertEquals(ParseResult.Ok(Host.RegName("Example.COM")), result)
    }

    @Test
    fun `parse reads a bracketed IPv6 literal in the Uri profile`() {
        val result = UriHostParser.parse("[::1]")

        assertEquals(ParseResult.Ok(Host.Ipv6(listOf(0, 0, 0, 0, 0, 0, 0, 1))), result)
    }

    @Test
    fun `parse reads a bracketed IPvFuture literal in the Uri profile`() {
        val result = UriHostParser.parse("[v1.fe]")

        assertEquals(ParseResult.Ok(Host.IpFuture("v1.fe")), result)
    }

    @Test
    fun `parse classifies a dotted-decimal Uri host as Ipv4`() {
        val result = UriHostParser.parse("1.2.3.4")

        assertEquals(ParseResult.Ok(Host.Ipv4(0x01020304)), result)
    }

    @Test
    fun `parse accepts a sub-delim in a Uri reg-name`() {
        val result = UriHostParser.parse("a!b")

        assertEquals(ParseResult.Ok(Host.RegName("a!b")), result)
    }

    @Test
    fun `parse rejects an invalid reg-name code point in the Uri profile`() {
        // '/' is neither unreserved nor sub-delim nor part of a triplet ([HOST-33]).
        val result = UriHostParser.parse("a/b")

        val err = assertIs<ParseResult.Err>(result)
        val cause = assertIs<UriParseError.ForbiddenHostCodePoint>(err.error)
        assertEquals('/'.code, cause.codePoint)
        assertEquals(1, cause.at)
    }

    @Test
    fun `parse permits an empty Uri host as Empty`() {
        val result = UriHostParser.parse("")

        assertEquals(ParseResult.Ok(Host.Empty), result)
    }

    @Test
    fun `parse preserves a percent triplet in a Uri reg-name`() {
        val result = UriHostParser.parse("a%2Fb")

        assertEquals(ParseResult.Ok(Host.RegName("a%2Fb")), result)
    }

    @Test
    fun `parse fails a Url bracketed literal with no closing bracket`() {
        val result = UrlHostParser.parse("[::1", isSpecial = true)

        assertIs<ParseResult.Err>(result)
    }

    @Test
    fun `parse fails a Uri bracketed literal with no closing bracket`() {
        // An IP-literal MUST end with `]`; an unterminated one is fatal ([HOST-5]).
        val result = UriHostParser.parse("[::1")

        assertIs<ParseResult.Err>(result)
    }

    @Test
    fun `parse fails a Uri bracketed IPvFuture that breaks the grammar`() {
        // Begins with 'v' but carries no hex version digit, so the production fails ([HOST-42]).
        val result = UriHostParser.parse("[v.fe]")

        assertIs<ParseResult.Err>(result)
    }

    @Test
    fun `parse accepts an uppercase V IPvFuture version marker`() {
        // The version marker is 'v' or 'V'; the uppercase form reaches the same production ([HOST-42]).
        val result = UriHostParser.parse("[V1.fe]")

        assertEquals(ParseResult.Ok(Host.IpFuture("V1.fe")), result)
    }

    @Test
    fun `parse accepts colon and sub-delim code points in an IPvFuture payload`() {
        // The IPvFuture payload admits unreserved / sub-delims / ':' ([HOST-42]).
        assertEquals(
            ParseResult.Ok(Host.IpFuture("v1.a:b")),
            UriHostParser.parse("[v1.a:b]"),
        )
        assertEquals(
            ParseResult.Ok(Host.IpFuture("v1.a!b")),
            UriHostParser.parse("[v1.a!b]"),
        )
    }

    @Test
    fun `parse rejects IPvFuture literals that break the grammar`() {
        // No '.' separator, a non-hex version digit, an empty payload, and an out-of-set payload
        // code point each fail the IPvFuture production ([HOST-42]).
        listOf("[v1eF]", "[vG1.fe]", "[v1.]", "[v1.a/b]").forEach { input ->
            val result = UriHostParser.parse(input)
            val err = assertIs<ParseResult.Err>(result, "expected Err for '$input'")
            val cause = assertIs<UriParseError.InvalidHost>(err.error)
            assertEquals(HostError.Ipv6Malformed, cause.reason, "input '$input'")
        }
    }

    @Test
    fun `parse rejects a malformed percent triplet in a Uri reg-name`() {
        // A '%' not followed by two hex digits is not a valid pct-encoded reg-name unit ([HOST-33]).
        val result = UriHostParser.parse("a%2g")

        val err = assertIs<ParseResult.Err>(result)
        val cause = assertIs<UriParseError.ForbiddenHostCodePoint>(err.error)
        assertEquals('%'.code, cause.codePoint)
        assertEquals(1, cause.at)
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

    @Test
    fun `parse accepts an opted-in zone id in the Uri profile`() {
        val result =
            UriHostParser.parse("[fe80::1%25eth0]", allowIpv6ZoneId = true)

        assertEquals(ParseResult.Ok(Host.Ipv6(listOf(0xFE80, 0, 0, 0, 0, 0, 0, 1), zoneId = "eth0")), result)
    }

    @Test
    fun `parse rejects a zone id with the opt-in off in the Uri profile`() {
        val result = UriHostParser.parse("[fe80::1%25eth0]")

        val err = assertIs<ParseResult.Err>(result)
        val cause = assertIs<UriParseError.InvalidHost>(err.error)
        assertEquals(HostError.ZoneIdRejected, cause.reason)
    }
}
