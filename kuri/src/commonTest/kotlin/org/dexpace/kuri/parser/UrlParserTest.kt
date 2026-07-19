/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.parser

import org.dexpace.kuri.error.ParseResult
import org.dexpace.kuri.error.ValidationErrorKind
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

    /** True when [components] recorded at least one validation error of [kind]. */
    private fun hasKind(
        components: ParsedComponents,
        kind: ValidationErrorKind,
    ): Boolean = components.validationErrors.any { it.kind == kind }

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
        assertTrue(hasKind(url, ValidationErrorKind.BACKSLASH_AS_SOLIDUS))
    }

    @Test
    fun `strips embedded tab before parsing and records its original offset`() {
        val url = parsed("htt\tp://h")
        assertEquals("http", url.scheme)
        assertEquals(Host.RegName("h"), url.host)
        val tabErrors = url.validationErrors.filter { it.kind == ValidationErrorKind.TAB_OR_NEWLINE_REMOVED }
        assertEquals(listOf(3), tabErrors.map { it.at })
    }

    @Test
    fun `records a leading C0-or-space trim at offset 0`() {
        val url = parsed("  http://h")
        val trimErrors =
            url.validationErrors.filter { it.kind == ValidationErrorKind.LEADING_OR_TRAILING_C0_CONTROL_OR_SPACE }
        assertEquals(listOf(0), trimErrors.map { it.at })
    }

    @Test
    fun `records a trailing-only C0-or-space trim at the first trimmed offset`() {
        val url = parsed("http://h  ")
        val trimErrors =
            url.validationErrors.filter { it.kind == ValidationErrorKind.LEADING_OR_TRAILING_C0_CONTROL_OR_SPACE }
        assertEquals(listOf(8), trimErrors.map { it.at })
    }

    @Test
    fun `shifts a tab offset by a preceding leading trim`() {
        // The leading space at 0 is trimmed first (shifting the tab-scan input by one code unit),
        // so the embedded tab -- originally at index 4 -- must still be reported at its true offset
        // in the untrimmed input, not at its post-trim local index of 3.
        val url = parsed(" htt\tp://h")
        val tabErrors = url.validationErrors.filter { it.kind == ValidationErrorKind.TAB_OR_NEWLINE_REMOVED }
        assertEquals(listOf(4), tabErrors.map { it.at })
    }

    @Test
    fun `splits userinfo on the first colon`() {
        val url = parsed("http://u:p@h/")
        assertEquals("u", url.username)
        assertEquals("p", url.password)
        assertEquals(Host.RegName("h"), url.host)
    }

    @Test
    fun `records a single invalid-credentials validation error at the at-sign offset`() {
        val url = parsed("http://u:p@h/")

        val credentialErrors = url.validationErrors.filter { it.kind == ValidationErrorKind.INVALID_CREDENTIALS }
        assertEquals(listOf(10), credentialErrors.map { it.at })
    }

    @Test
    fun `records one invalid-credentials error per at-sign in the authority`() {
        // SPEC [PARSE-56]: each `@` the AUTHORITY state encounters is recorded, not just the
        // final delimiter -- so "a@b@c.example" (the last-`@` userinfo split) yields two entries.
        val url = parsed("http://a@b@c.example/")

        val credentialErrors = url.validationErrors.filter { it.kind == ValidationErrorKind.INVALID_CREDENTIALS }
        assertEquals(listOf(8, 10), credentialErrors.map { it.at })
        assertEquals("a%40b", url.username)
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
        assertTrue(hasKind(url, ValidationErrorKind.BACKSLASH_AS_SOLIDUS))
    }

    @Test
    fun `records missing authority slashes for a single-slash special scheme`() {
        val url = parsed("http:/x")
        assertEquals(Host.RegName("x"), url.host)
        assertTrue(hasKind(url, ValidationErrorKind.MISSING_AUTHORITY_SLASHES))
    }

    @Test
    fun `records a backslash after a file scheme`() {
        val url = parsed("file:\\p")
        assertEquals("file", url.scheme)
        assertTrue(hasKind(url, ValidationErrorKind.BACKSLASH_AS_SOLIDUS))
    }

    @Test
    fun `records a backslash in the file-slash state`() {
        val url = parsed("file:/\\p")
        assertEquals("file", url.scheme)
        assertTrue(hasKind(url, ValidationErrorKind.BACKSLASH_AS_SOLIDUS))
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

    @Test
    fun `records file-invalid-Windows-drive-letter for a drive-letter file-base reference`() {
        // Issue reproduction: against base file:///a/b, "C|/x" hits the file-base branch that
        // clears the copied path because the remaining input is itself a drive letter ([PARSE-57]).
        val base = parsed("file:///a/b")
        val url = parsed("C|/x", base)

        assertEquals(listOf("C:", "x"), segments(url))
        val driveErrors =
            url.validationErrors.filter { it.kind == ValidationErrorKind.FILE_INVALID_WINDOWS_DRIVE_LETTER }
        assertEquals(listOf(0), driveErrors.map { it.at })
    }

    @Test
    fun `records file-invalid-Windows-drive-letter-host for a drive-letter file host`() {
        // Issue reproduction: "file://C:/foo" scans "C:" as the file host, which is a drive
        // letter reinterpreted as a path instead ([PARSE-58]).
        val url = parsed("file://C:/foo")

        assertEquals(Host.Empty, url.host)
        assertEquals(listOf("C:", "foo"), segments(url))
        val driveErrors =
            url.validationErrors.filter { it.kind == ValidationErrorKind.FILE_INVALID_WINDOWS_DRIVE_LETTER_HOST }
        assertEquals(listOf(7), driveErrors.map { it.at })
    }

    @Test
    fun `records an invalid-URL-unit error for a raw space in the path`() {
        val url = parsed("http://h/a b")

        val unitErrors = url.validationErrors.filter { it.kind == ValidationErrorKind.INVALID_URL_UNIT }
        assertEquals(listOf(10), unitErrors.map { it.at })
    }

    @Test
    fun `records an invalid-URL-unit error for a raw space in the query`() {
        val url = parsed("http://h/?a b")

        val unitErrors = url.validationErrors.filter { it.kind == ValidationErrorKind.INVALID_URL_UNIT }
        assertEquals(listOf(11), unitErrors.map { it.at })
    }

    @Test
    fun `records an invalid-URL-unit error for a raw space in the opaque path`() {
        val url = parsed("mailto:a b")

        val unitErrors = url.validationErrors.filter { it.kind == ValidationErrorKind.INVALID_URL_UNIT }
        assertEquals(listOf(8), unitErrors.map { it.at })
    }

    @Test
    fun `does not record an invalid-URL-unit error for a valid percent escape`() {
        val url = parsed("http://h/a%20b")

        assertTrue(url.validationErrors.none { it.kind == ValidationErrorKind.INVALID_URL_UNIT })
    }
}
