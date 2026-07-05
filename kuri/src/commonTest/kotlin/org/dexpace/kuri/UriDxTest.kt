/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri

import org.dexpace.kuri.host.Host
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class UriDxTest {
    private fun parseOk(input: String): Uri = Uri.parse(input).getOrNull() ?: fail("expected $input to parse")

    // --- decoded path vs verbatim encodedPath ---

    @Test
    fun `path decodes each segment while encodedPath stays verbatim`() {
        val uri = parseOk("http://h/a%2Fb")

        assertEquals("/a/b", uri.path)
        assertEquals("/a%2Fb", uri.encodedPath)
        assertEquals(listOf("a/b"), uri.pathSegments)
    }

    @Test
    fun `path decodes a percent-encoded space`() {
        val uri = parseOk("http://h/a%20b")

        assertEquals("/a b", uri.path)
        assertEquals("/a%20b", uri.encodedPath)
    }

    @Test
    fun `path decodes a rootless reference without adding a leading slash`() {
        val uri = parseOk("a%2Fb")

        assertEquals("a/b", uri.path)
        assertEquals("a%2Fb", uri.encodedPath)
        assertEquals(listOf("a/b"), uri.pathSegments)
    }

    @Test
    fun `builder path setter takes an already-encoded path`() {
        val uri =
            Uri
                .Builder()
                .scheme("http")
                .host("h")
                .path("/a%2Fb")
                .build()

        assertEquals("/a%2Fb", uri.encodedPath)
        assertEquals("/a/b", uri.path)
    }

    // --- queryParameters() ---

    @Test
    fun `queryParameters decodes the pairs preserving duplicates`() {
        val params = parseOk("http://h/p?a=1&a=2&b=3").queryParameters()

        assertEquals(listOf("1", "2"), params.getAll("a"))
        assertTrue(params.has("b"))
        assertEquals(3, params.size)
    }

    @Test
    fun `queryParameters is empty when the query is absent`() {
        assertTrue(parseOk("http://h/p").queryParameters().isEmpty())
    }

    @Test
    fun `queryParameters keeps a present-but-empty query distinct from an absent one`() {
        assertEquals(1, parseOk("http://h/p?").queryParameters().size)
        assertTrue(parseOk("http://h/p").queryParameters().isEmpty())
    }

    // --- builder query setters ---

    @Test
    fun `setQueryParameter replaces the first pair and drops the rest`() {
        val uri = parseOk("http://h/p?a=1&a=2&b=3").newBuilder().setQueryParameter("a", "9").build()

        assertEquals(listOf("9"), uri.queryParameters().getAll("a"))
        assertEquals("3", uri.queryParameters()["b"])
    }

    @Test
    fun `addQueryParameter appends without deduplicating`() {
        val uri = parseOk("http://h/p?a=1").newBuilder().addQueryParameter("a", "2").build()

        assertEquals(listOf("1", "2"), uri.queryParameters().getAll("a"))
    }

    @Test
    fun `removeAllQueryParameters drops every matching pair`() {
        val uri = parseOk("http://h/p?a=1&b=2&a=3").newBuilder().removeAllQueryParameters("a").build()

        assertFalse(uri.queryParameters().has("a"))
        assertTrue(uri.queryParameters().has("b"))
    }

    // --- resolve / toUrl parity ---

    @Test
    fun `resolveOrThrow resolves a relative reference against an absolute base`() {
        val resolved = parseOk("http://h/a/b").resolveOrThrow("c")

        assertEquals("http://h/a/c", resolved.uriString)
    }

    @Test
    fun `resolveOrNull returns null when the base has no scheme`() {
        assertNull(parseOk("/only/a/path").resolveOrNull("x"))
    }

    @Test
    fun `toUrlOrThrow converts a special-scheme uri`() {
        assertEquals("http://h/p", parseOk("http://h/p").toUrlOrThrow().href)
    }

    @Test
    fun `toUrlOrNull returns null for a relative reference`() {
        assertNull(parseOk("/only/a/path").toUrlOrNull())
    }

    // --- buildOrNull ---

    @Test
    fun `buildOrNull mirrors a successful build`() {
        val builder =
            Uri
                .Builder()
                .scheme("http")
                .host("h")
                .encodedPath("/p")

        assertEquals(builder.build(), builder.buildOrNull())
    }

    @Test
    fun `buildOrNull returns null when userInfo has no host`() {
        val builder = Uri.Builder().userInfo("u").encodedPath("/p")

        assertNull(builder.buildOrNull())
        assertFailsWith<IllegalArgumentException> { builder.build() }
    }

    @Test
    fun `buildOrNull returns null on a bad host`() {
        val builder = Uri.Builder().scheme("http").host("[g::1]")

        assertNull(builder.buildOrNull())
        assertFailsWith<IllegalArgumentException> { builder.build() }
    }

    // --- host(Host) sink ---

    @Test
    fun `host sink accepts a structured reg-name`() {
        val uri =
            Uri
                .Builder()
                .scheme("http")
                .host(Host.RegName("example.com"))
                .build()

        assertEquals("example.com", uri.hostName)
    }

    @Test
    fun `host sink re-canonicalizes a structured ipv6 literal`() {
        val source = parseOk("http://[0:0:0:0:0:0:0:1]/").host ?: fail("expected a host")

        val uri =
            Uri
                .Builder()
                .scheme("http")
                .host(source)
                .encodedPath("/")
                .build()

        assertEquals("[::1]", uri.hostName)
    }

    // --- relativize ---

    @Test
    fun `relativize emits a relative path for a same-origin descendant`() {
        val base = parseOk("http://h/a/b/")
        val target = parseOk("http://h/a/b/c/d")

        val relative = base.relativize(target)

        assertEquals("c/d", relative.uriString)
        assertEquals(target, base.resolveOrThrow(relative.uriString))
    }

    @Test
    fun `relativize returns the target unchanged across different authorities`() {
        val target = parseOk("http://other/x")

        assertEquals(target, parseOk("http://h/a/").relativize(target))
    }

    @Test
    fun `relativize returns the target unchanged for an opaque path`() {
        val target = parseOk("mailto:b@example.com")

        assertEquals(target, parseOk("mailto:a@example.com").relativize(target))
    }

    // --- path-segment editing + fileName / fileExtension ---

    @Test
    fun `addPathSegments splits on slash and encodes each part`() {
        val uri =
            Uri
                .Builder()
                .scheme("http")
                .host("h")
                .addPathSegments("a/b c/d")
                .build()

        assertEquals("http://h/a/b%20c/d", uri.uriString)
        assertEquals(listOf("a", "b c", "d"), uri.pathSegments)
    }

    @Test
    fun `setPathSegment replaces one decoded segment in place`() {
        val uri = parseOk("http://h/a/b/c").newBuilder().setPathSegment(1, "x").build()

        assertEquals("/a/x/c", uri.encodedPath)
    }

    @Test
    fun `removePathSegment drops the segment at the index`() {
        val uri = parseOk("http://h/a/b/c").newBuilder().removePathSegment(0).build()

        assertEquals("/b/c", uri.encodedPath)
    }

    @Test
    fun `setPathSegment rejects an out-of-bounds index`() {
        assertFailsWith<IllegalArgumentException> {
            parseOk("http://h/a").newBuilder().setPathSegment(5, "x")
        }
    }

    @Test
    fun `fileName is the last non-empty decoded segment`() {
        assertEquals("c d.txt", parseOk("http://h/a/b/c%20d.txt").fileName())
        assertEquals("b", parseOk("http://h/a/b/").fileName())
        assertEquals("", parseOk("http://h/").fileName())
    }

    @Test
    fun `fileExtension is the text after the last dot of the file name`() {
        assertEquals("gz", parseOk("http://h/a/archive.tar.gz").fileExtension())
        assertEquals("", parseOk("http://h/a/README").fileExtension())
        assertEquals("", parseOk("http://h/a/.bashrc").fileExtension())
    }

    // --- predicates / ergonomics ---

    @Test
    fun `isAbsolute reflects the presence of a scheme`() {
        assertTrue(parseOk("http://h/p").isAbsolute())
        assertFalse(parseOk("//h/p").isAbsolute())
        assertFalse(parseOk("/rel").isAbsolute())
    }

    @Test
    fun `isOpaquePath distinguishes opaque from hierarchical paths`() {
        assertTrue(parseOk("mailto:a@example.com").isOpaquePath())
        assertTrue(parseOk("urn:isbn:0451450523").isOpaquePath())
        assertFalse(parseOk("http://h/p").isOpaquePath())
        assertFalse(parseOk("/rel").isOpaquePath())
    }

    @Test
    fun `effectivePort falls back to the scheme default then null`() {
        assertEquals(80, parseOk("http://h/").effectivePort())
        assertEquals(8080, parseOk("http://h:8080/").effectivePort())
        assertNull(parseOk("mailto:a@example.com").effectivePort())
        assertNull(parseOk("/rel").effectivePort())
    }

    @Test
    fun `withPort sets and elides the port`() {
        assertEquals(2, parseOk("http://h:1/").withPort(2).port)
        assertNull(parseOk("http://h:1/").withPort(null).port)
    }

    @Test
    fun `withFragment and withoutFragment rewrite the fragment`() {
        assertEquals("x", parseOk("http://h/p").withFragment("x").fragment)
        assertNull(parseOk("http://h/p#x").withoutFragment().fragment)
    }
}
