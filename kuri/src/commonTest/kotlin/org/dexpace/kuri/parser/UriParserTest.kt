/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.parser

import org.dexpace.kuri.error.ParseResult
import org.dexpace.kuri.error.UriParseError
import org.dexpace.kuri.host.Host
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Tests for [UriParser.parse], the `Uri`-profile RFC 3986 component splitter of SPEC §8
 * ([PARSE-7]..[PARSE-45]) and RFC 3986 §3 / Appendix B.
 *
 * Covers the canonical RFC examples (full five-component split, the `urn`/`mailto` rootless cases),
 * scheme-relative and relative references, the strict-RFC postures (no dot-segment removal, no
 * backslash specialness, case preserved), and the fatal paths (embedded control, ill-formed scheme,
 * invalid port).
 */
class UriParserTest {
    private fun parsed(input: String): ParsedComponents {
        val result = UriParser.parse(input)
        assertIs<ParseResult.Ok<ParsedComponents>>(result, "expected Ok for <$input>")
        return result.value
    }

    private fun segments(components: ParsedComponents): List<String> {
        val path = components.path
        assertIs<ComponentPath.Segments>(path, "expected a segmented path")
        return path.segments
    }

    // --- canonical RFC 3986 §3 examples -----------------------------------------------------------

    @Test
    fun `parse splits the full RFC three-six example into all five components`() {
        val components = parsed("foo://example.com:8042/over/there?name=ferret#nose")

        assertEquals("foo", components.scheme)
        assertEquals(Host.RegName("example.com"), components.host)
        assertEquals(8042, components.port)
        assertEquals(listOf("over", "there"), segments(components))
        assertEquals("name=ferret", components.query)
        assertEquals("nose", components.fragment)
    }

    @Test
    fun `parse keeps a urn path rootless with no authority`() {
        val components = parsed("urn:example:animal:ferret:nose")

        assertEquals("urn", components.scheme)
        assertNull(components.host)
        assertEquals(listOf("example:animal:ferret:nose"), segments(components))
        assertEquals(false, (components.path as ComponentPath.Segments).rooted)
        assertNull(components.query)
        assertNull(components.fragment)
    }

    @Test
    fun `parse keeps a mailto address as a rootless path with no authority`() {
        val components = parsed("mailto:John.Doe@example.com")

        assertEquals("mailto", components.scheme)
        assertNull(components.host)
        assertEquals(listOf("John.Doe@example.com"), segments(components))
        assertEquals(false, (components.path as ComponentPath.Segments).rooted)
    }

    // --- authority presence and relative references ([PARSE-19]/[PARSE-50]) ----------------------

    @Test
    fun `parse treats a leading double slash as authority with no scheme`() {
        val components = parsed("//example.com/path")

        assertNull(components.scheme)
        assertEquals(Host.RegName("example.com"), components.host)
        assertEquals(listOf("path"), segments(components))
    }

    @Test
    fun `parse reads an absolute-path relative reference with no scheme`() {
        val components = parsed("/relative/path")

        assertNull(components.scheme)
        assertNull(components.host)
        assertEquals(listOf("relative", "path"), segments(components))
        assertEquals(true, (components.path as ComponentPath.Segments).rooted)
    }

    @Test
    fun `parse reads a path-noscheme relative reference`() {
        val components = parsed("a/b/c")

        assertNull(components.scheme)
        assertNull(components.host)
        assertEquals(listOf("a", "b", "c"), segments(components))
        assertEquals(false, (components.path as ComponentPath.Segments).rooted)
    }

    @Test
    fun `parse yields the empty path for empty input`() {
        val components = parsed("")

        assertNull(components.scheme)
        assertNull(components.host)
        assertEquals(emptyList<String>(), segments(components))
        assertNull(components.query)
        assertNull(components.fragment)
    }

    // --- strict-RFC posture ([PARSE-39]/[PARSE-49], host preserve) -------------------------------

    @Test
    fun `parse preserves dot segments without resolution`() {
        val components = parsed("http://h/a/../b")

        assertEquals(Host.RegName("h"), components.host)
        assertEquals(listOf("a", "..", "b"), segments(components))
    }

    @Test
    fun `parse preserves host case in the Uri profile`() {
        val components = parsed("http://EXAMPLE.com/")

        assertEquals(Host.RegName("EXAMPLE.com"), components.host)
        assertEquals(listOf(""), segments(components))
    }

    @Test
    fun `parse keeps a backslash as an ordinary path code point`() {
        val components = parsed("http://h/a\\b")

        assertEquals(Host.RegName("h"), components.host)
        assertEquals(listOf("a\\b"), segments(components))
    }

    // --- delimiter edge cases --------------------------------------------------------------------

    @Test
    fun `parse keeps a second question mark inside the query`() {
        val components = parsed("http://h/p?a?b")

        assertEquals("a?b", components.query)
    }

    @Test
    fun `parse retains a present-but-empty fragment distinct from an absent one`() {
        val withHash = parsed("http://h/#")
        val withoutHash = parsed("http://h/")

        assertEquals("", withHash.fragment)
        assertNull(withoutHash.fragment)
    }

    @Test
    fun `parse splits an IPv6 literal host from its port`() {
        val components = parsed("http://[::1]:8080/p")

        assertEquals(Host.Ipv6(listOf(0, 0, 0, 0, 0, 0, 0, 1)), components.host)
        assertEquals(8080, components.port)
    }

    @Test
    fun `parse reads an empty authority as the empty host`() {
        val components = parsed("foo:///x")

        assertEquals("foo", components.scheme)
        assertEquals(Host.Empty, components.host)
        assertEquals(listOf("x"), segments(components))
    }

    // --- fatal paths ([PARSE-4]/[ERR-9]/[PARSE-32]) ----------------------------------------------

    @Test
    fun `parse rejects an embedded control code point in the host`() {
        val result = UriParser.parse("http://h\u0001ost/p")

        assertIs<ParseResult.Err>(result)
        val error = assertIs<UriParseError.ForbiddenHostCodePoint>(result.error)
        assertEquals(1, error.codePoint)
        // UriHostParser reports index 1 relative to the host substring; UriParser must rebase that
        // to the full-input offset 8 (the host starts right after "http://").
        assertEquals(8, error.at)
    }

    @Test
    fun `parse rebases a forbidden host code point past userinfo credentials`() {
        // The host still reports its own offense at its local index 1, but here the host starts at
        // full-input index 12 (right after "http://user@"), so the rebased offset must be 13.
        val result = UriParser.parse("http://user@h\u0001ost/p")

        assertIs<ParseResult.Err>(result)
        val error = assertIs<UriParseError.ForbiddenHostCodePoint>(result.error)
        assertEquals(1, error.codePoint)
        assertEquals(13, error.at)
    }

    @Test
    fun `parse rejects an embedded control code point in the path`() {
        val result = UriParser.parse("http://h/pa\u0001th")

        assertIs<ParseResult.Err>(result)
        assertIs<UriParseError.InvalidPercentEncoding>(result.error)
    }

    @Test
    fun `parse rejects a scheme that starts with a digit`() {
        val result = UriParser.parse("1http://example.com")

        assertIs<ParseResult.Err>(result)
        assertIs<UriParseError.InvalidScheme>(result.error)
    }

    @Test
    fun `parse rejects a non-digit port`() {
        val result = UriParser.parse("http://h:80a/p")

        assertIs<ParseResult.Err>(result)
        assertEquals(UriParseError.InvalidPort("80a"), result.error)
    }

    @Test
    fun `parse rejects a port that overflows Int`() {
        val result = UriParser.parse("http://h:99999999999/p")

        assertIs<ParseResult.Err>(result)
        assertEquals(UriParseError.InvalidPort("99999999999"), result.error)
    }

    @Test
    fun `parse rejects a malformed percent triplet in the query`() {
        val result = UriParser.parse("http://h/p?a=%2")

        assertIs<ParseResult.Err>(result)
        assertIs<UriParseError.InvalidPercentEncoding>(result.error)
    }

    @Test
    fun `parse accepts a large in-range Uri port without the Url cap`() {
        val components = parsed("foo://h:70000/p")

        assertEquals(70000, components.port)
    }

    @Test
    fun `parse rejects input longer than the maximum length`() {
        val tooLong = "a".repeat(8193)

        val result = UriParser.parse(tooLong)

        assertIs<ParseResult.Err>(result)
        assertIs<UriParseError.InputTooLong>(result.error)
    }

    @Test
    fun `parse locates a bad continuation code point in the scheme`() {
        // The candidate starts with an ASCII alpha but carries a disallowed '*' at index 2 -- the
        // continuation-char scan branch that a digit-led scheme never reaches.
        val result = UriParser.parse("ht*tp:x")

        assertIs<ParseResult.Err>(result)
        val error = assertIs<UriParseError.InvalidScheme>(result.error)
        assertEquals(2, error.at)
    }

    @Test
    fun `parse rejects a forbidden control code point in the userinfo`() {
        val result = UriParser.parse("http://us\u0001er@h/p")

        assertIs<ParseResult.Err>(result)
        assertIs<UriParseError.InvalidPercentEncoding>(result.error)
    }

    @Test
    fun `parse splits userinfo into username and password`() {
        val components = parsed("http://user:pw@h/p")

        assertEquals("user", components.username)
        assertEquals("pw", components.password)
        assertEquals(Host.RegName("h"), components.host)
    }

    @Test
    fun `parse leaves username and password null when no at-sign is present`() {
        val components = parsed("http://h/p")

        assertNull(components.username)
        assertNull(components.password)
    }

    @Test
    fun `parse distinguishes an empty-but-present userinfo from no userinfo`() {
        // Regression for #104: an at-sign with nothing before it is a present, empty userinfo
        // (username == ""), distinct from no at-sign at all (username == null).
        val components = parsed("http://@h/")

        assertEquals("", components.username)
        assertNull(components.password)
    }

    @Test
    fun `parse distinguishes an empty-but-present password from no password`() {
        // Regression for #104: a colon with nothing after it is a present, empty password
        // (password == ""), distinct from no colon at all (password == null).
        val components = parsed("http://u:@h/")

        assertEquals("u", components.username)
        assertEquals("", components.password)
    }

    @Test
    fun `parse rejects a DEL code point in the path`() {
        val result = UriParser.parse("http://h/p\u007Fq")

        assertIs<ParseResult.Err>(result)
        assertIs<UriParseError.InvalidPercentEncoding>(result.error)
    }

    @Test
    fun `parse rejects an unterminated IPv6 host literal`() {
        val result = UriParser.parse("foo://[oops")

        assertIs<ParseResult.Err>(result)
    }

    @Test
    fun `parse reports the earliest offense when a control and a bad percent coexist`() {
        // The query "%1\u0001" carries a bad percent triplet at query index 0 and a control at index
        // 2; the earlier (index 0) offense wins, exercising the two-offense minimum. The query begins
        // at input index 11 (after "http://h/p?").
        val result = UriParser.parse("http://h/p?%1\u0001")

        assertIs<ParseResult.Err>(result)
        val error = assertIs<UriParseError.InvalidPercentEncoding>(result.error)
        assertEquals(11, error.at)
    }
}
