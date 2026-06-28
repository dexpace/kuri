/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.parser

import org.dexpace.kuri.error.ParseResult
import org.dexpace.kuri.error.ValidationError
import org.dexpace.kuri.host.Host
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behavioural tests for [UrlParser] against SPEC §8 (the `Url`-profile basic URL parser).
 *
 * A focused, hand-picked set covering decomposition, default-port elision, backslash and
 * tab/newline quirks, userinfo splitting, dot-segment removal, opaque and file paths, relative
 * resolution, and the query/fragment presence distinction.
 */
class UrlParserTest {
    private fun parsed(
        input: String,
        base: ParsedComponents? = null,
    ): ParsedComponents {
        val result = UrlParser.parse(input, base)
        assertIs<ParseResult.Ok<ParsedComponents>>(result, "expected Ok for <$input>, was $result")
        return result.value
    }

    private fun segments(components: ParsedComponents): List<String> {
        val path = components.path
        assertIs<UrlPath.Segments>(path, "expected a segment path, was $path")
        return path.segments
    }

    @Test
    fun `decomposes a full special URL`() {
        val url = parsed("https://example.com/a/b?q#f")
        assertEquals("https", url.scheme)
        assertEquals(Host.RegName("example.com"), url.host)
        assertNull(url.port)
        assertEquals(listOf("a", "b"), segments(url))
        assertEquals("q", url.query)
        assertEquals("f", url.fragment)
        assertEquals("", url.username)
        assertEquals("", url.password)
    }

    @Test
    fun `elides the default port for the scheme`() {
        val url = parsed("https://h:443/")
        assertEquals(Host.RegName("h"), url.host)
        assertNull(url.port)
        assertEquals(listOf(""), segments(url))
    }

    @Test
    fun `keeps a non-default port`() {
        val url = parsed("https://h:8443/")
        assertEquals(8443, url.port)
    }

    @Test
    fun `treats backslashes as solidus for special schemes`() {
        val url = parsed("http:\\\\h\\p")
        assertEquals("http", url.scheme)
        assertEquals(Host.RegName("h"), url.host)
        assertEquals(listOf("p"), segments(url))
        assertTrue(ValidationError.BACKSLASH_AS_SOLIDUS in url.validationErrors)
    }

    @Test
    fun `strips embedded tab before parsing`() {
        val url = parsed("htt\tp://h")
        assertEquals("http", url.scheme)
        assertEquals(Host.RegName("h"), url.host)
        assertTrue(ValidationError.TAB_OR_NEWLINE_REMOVED in url.validationErrors)
    }

    @Test
    fun `splits userinfo on the first colon`() {
        val url = parsed("http://u:p@h/")
        assertEquals("u", url.username)
        assertEquals("p", url.password)
        assertEquals(Host.RegName("h"), url.host)
    }

    @Test
    fun `encodes a non-final at-sign in userinfo`() {
        val url = parsed("http://a@b@h/")
        assertEquals("a%40b", url.username)
        assertEquals("", url.password)
        assertEquals(Host.RegName("h"), url.host)
    }

    @Test
    fun `removes a double-dot path segment`() {
        val url = parsed("http://h/a/../b")
        assertEquals(listOf("b"), segments(url))
    }

    @Test
    fun `removes a single-dot path segment`() {
        val url = parsed("http://h/a/./b")
        assertEquals(listOf("a", "b"), segments(url))
    }

    @Test
    fun `stores a non-special scheme path as opaque`() {
        val url = parsed("mailto:a@b")
        assertEquals("mailto", url.scheme)
        assertNull(url.host)
        assertEquals(UrlPath.Opaque("a@b"), url.path)
    }

    @Test
    fun `parses a file URL with an empty host`() {
        val url = parsed("file:///c/d")
        assertEquals("file", url.scheme)
        assertEquals(Host.Empty, url.host)
        assertEquals(listOf("c", "d"), segments(url))
    }

    @Test
    fun `maps a file localhost to the empty host`() {
        val url = parsed("file://localhost/x")
        assertEquals(Host.Empty, url.host)
        assertEquals(listOf("x"), segments(url))
    }

    @Test
    fun `resolves a dot-dot reference against a base`() {
        val base = parsed("http://a/b/c/d")
        val url = parsed("../g", base)
        assertEquals("http", url.scheme)
        assertEquals(Host.RegName("a"), url.host)
        assertEquals(listOf("b", "g"), segments(url))
    }

    @Test
    fun `canonicalizes a special empty path to root`() {
        val url = parsed("http://h")
        assertEquals(Host.RegName("h"), url.host)
        assertEquals(listOf(""), segments(url))
    }

    @Test
    fun `distinguishes an empty query from an absent one`() {
        assertEquals("", parsed("http://h/?").query)
        assertNull(parsed("http://h/").query)
    }

    @Test
    fun `distinguishes an empty fragment from an absent one`() {
        assertEquals("", parsed("http://h/#").fragment)
        assertNull(parsed("http://h/").fragment)
    }

    @Test
    fun `fails a special scheme with no host`() {
        val result = UrlParser.parse("http://")
        assertTrue(result is ParseResult.Err, "expected Err, was $result")
    }
}
