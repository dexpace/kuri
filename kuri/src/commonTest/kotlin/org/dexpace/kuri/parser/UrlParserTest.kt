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
        assertIs<ComponentPath.Segments>(path, "expected a segment path, was $path")
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
    fun `records an invalid-credentials validation error for userinfo`() {
        val url = parsed("http://u:p@h/")

        assertTrue(ValidationError.INVALID_CREDENTIALS in url.validationErrors)
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
        assertEquals(ComponentPath.Opaque("a@b"), url.path)
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

    @Test
    fun `resolves a fragment-only reference against an opaque base`() {
        val base = parsed("mailto:x@y")
        val url = parsed("#frag", base)
        assertEquals("mailto", url.scheme)
        assertEquals(ComponentPath.Opaque("x@y"), url.path)
        assertEquals("frag", url.fragment)
    }

    @Test
    fun `records a backslash after a relative slash for a special base`() {
        val base = parsed("http://a/b/c")
        val url = parsed("/\\x", base)
        assertEquals(Host.RegName("x"), url.host)
        assertTrue(ValidationError.BACKSLASH_AS_SOLIDUS in url.validationErrors)
    }

    @Test
    fun `records missing authority slashes for a single-slash special scheme`() {
        val url = parsed("http:/x")
        assertEquals(Host.RegName("x"), url.host)
        assertTrue(ValidationError.MISSING_AUTHORITY_SLASHES in url.validationErrors)
    }

    @Test
    fun `records a backslash after a file scheme`() {
        val url = parsed("file:\\p")
        assertEquals("file", url.scheme)
        assertTrue(ValidationError.BACKSLASH_AS_SOLIDUS in url.validationErrors)
    }

    @Test
    fun `records a backslash in the file-slash state`() {
        val url = parsed("file:/\\p")
        assertEquals("file", url.scheme)
        assertTrue(ValidationError.BACKSLASH_AS_SOLIDUS in url.validationErrors)
    }

    @Test
    fun `carries a base drive letter into a rooted file reference`() {
        val base = parsed("file:///C:/y")
        val url = parsed("/x", base)
        assertEquals(listOf("C:", "x"), segments(url))
    }

    @Test
    fun `does not carry a non-drive base segment into a rooted file reference`() {
        // The file-slash base carry-over appends the base's first segment only when it is a
        // normalized Windows drive letter; a non-drive first segment ("foo") is the false arm, so
        // nothing is prepended and the rooted reference keeps only its own segment.
        val base = parsed("file:///foo/bar")
        val url = parsed("/x", base)
        assertEquals(Host.Empty, url.host)
        assertEquals(listOf("x"), segments(url))
    }

    @Test
    fun `keeps a windows drive when shortening a file path`() {
        val url = parsed("file:///C:/..")
        assertEquals(listOf("C:", ""), segments(url))
    }

    @Test
    fun `ends a file host scan at a query delimiter`() {
        val url = parsed("file://host?q")
        assertEquals(Host.RegName("host"), url.host)
        assertEquals("q", url.query)
    }

    @Test
    fun `ends a file host scan at a backslash`() {
        val url = parsed("file://h\\x")
        assertEquals(Host.RegName("h"), url.host)
        assertEquals(listOf("x"), segments(url))
    }

    @Test
    fun `keeps a port on a non-special scheme with no default`() {
        val url = parsed("sc://h:1/")
        // A non-special scheme parses its host as an opaque host, and the explicit port is kept
        // because a non-special scheme has no default port to elide against.
        assertEquals(Host.Opaque("h"), url.host)
        assertEquals(1, url.port)
    }

    @Test
    fun `rejects an out-of-range port`() {
        val result = UrlParser.parse("https://h:99999/")
        assertTrue(result is ParseResult.Err, "expected Err, was $result")
    }
}
