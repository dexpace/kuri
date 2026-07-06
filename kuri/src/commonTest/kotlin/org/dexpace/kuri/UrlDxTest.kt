/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri

import org.dexpace.kuri.error.ValidationError
import org.dexpace.kuri.host.Host
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class UrlDxTest {
    private fun parseOk(input: String): Url = Url.parse(input).getOrNull() ?: fail("expected $input to parse")

    @Test
    fun `origin serializes a tuple and hasOpaqueOrigin is false when the scheme is special`() {
        val url = parseOk("https://user@example.com:8443/p")

        assertEquals("https://example.com:8443", url.origin)
        assertFalse(url.hasOpaqueOrigin())
    }

    @Test
    fun `origin is the literal null and hasOpaqueOrigin is true for an opaque origin`() {
        for (input in listOf("mailto:a@b.example", "file:///etc/hosts")) {
            val url = parseOk(input)

            assertEquals("null", url.origin, "expected $input to have an opaque origin")
            assertTrue(url.hasOpaqueOrigin(), "expected $input to report an opaque origin")
        }
    }

    @Test
    fun `validationErrors is empty for a conformant input`() {
        val url = parseOk("https://example.com/a/b")

        assertTrue(url.validationErrors().isEmpty())
    }

    @Test
    fun `validationErrors records the backslash repair while the parse still succeeds`() {
        val url = parseOk("https://example.com/a\\b")

        assertEquals("https://example.com/a/b", url.href)
        assertContains(url.validationErrors(), ValidationError.BACKSLASH_AS_SOLIDUS)
    }

    @Test
    fun `addQueryParameter appends without removing a same-named pair`() {
        val url = parseOk("https://h/?a=1").newBuilder().addQueryParameter("a", "2").build()

        assertEquals("a=1&a=2", url.query)
        assertEquals(listOf<String?>("1", "2"), url.queryParameters.getAll("a"))
    }

    @Test
    fun `removeAllQueryParameters drops every matching pair and keeps the rest`() {
        val url = parseOk("https://h/?a=1&b=2&a=3").newBuilder().removeAllQueryParameters("a").build()

        assertEquals("b=2", url.query)
    }

    @Test
    fun `removeAllQueryParameters removing the last pair drops the question mark`() {
        val url = parseOk("https://h/?a=1").newBuilder().removeAllQueryParameters("a").build()

        assertNull(url.query)
        assertEquals("https://h/", url.href)
    }

    @Test
    fun `addQueryParameter on a bare question mark does not leak a leading ampersand`() {
        val url = parseOk("https://h/?").newBuilder().addQueryParameter("a", "1").build()

        assertEquals("https://h/?a=1", url.href)
    }

    @Test
    fun `removeAllQueryParameters is a no-op on a query-less URL`() {
        val url = parseOk("https://h/p").newBuilder().removeAllQueryParameters("x").build()

        assertNull(url.query)
        assertEquals("https://h/p", url.href)
    }

    @Test
    fun `buildOrNull returns the canonical url when the components are valid`() {
        val url =
            Url
                .Builder()
                .scheme("https")
                .host("example.com")
                .buildOrNull()

        assertNotNull(url)
        assertEquals("https://example.com/", url.href)
    }

    @Test
    fun `buildOrNull returns null when a special scheme has no host`() {
        assertNull(Url.Builder().scheme("https").buildOrNull())
    }

    @Test
    fun `buildOrNull returns null when the host cannot be parsed`() {
        assertNull(
            Url
                .Builder()
                .scheme("https")
                .host("[")
                .buildOrNull(),
        )
    }

    @Test
    fun `build throws where buildOrNull returns null`() {
        assertFailsWith<IllegalArgumentException> {
            Url.Builder().scheme("https").build()
        }
    }

    @Test
    fun `host from a structured Host is serialized onto the builder`() {
        val rebuilt =
            Url
                .Builder()
                .scheme("http")
                .host(Host.RegName("example.com"))
                .build()

        assertEquals("http://example.com/", rebuilt.href)
    }

    @Test
    fun `host from a structured IPv6 host round-trips with brackets`() {
        val host = requireNotNull(parseOk("https://[2001:db8::1]/").host)

        val rebuilt =
            Url
                .Builder()
                .scheme("https")
                .host(host)
                .build()

        assertEquals("https://[2001:db8::1]/", rebuilt.href)
    }

    @Test
    fun `resolveOrThrow resolves a relative reference against this url`() {
        val resolved = parseOk("https://h/a/b").resolveOrThrow("../c")

        assertEquals("https://h/c", resolved.href)
    }

    @Test
    fun `resolveOrNull returns null when the reference does not resolve`() {
        assertNull(parseOk("https://h/").resolveOrNull("http://["))
    }

    @Test
    fun `resolve is equivalent to parsing against this url as base`() {
        val base = parseOk("https://h/a/b")

        assertEquals(Url.parse("../c", base).getOrNull(), base.resolve("../c").getOrNull())
    }

    @Test
    fun `setPathSegment replaces the segment at an index`() {
        val url = parseOk("https://h/a/b/c").newBuilder().setPathSegment(1, "X").build()

        assertEquals("https://h/a/X/c", url.href)
    }

    @Test
    fun `removePathSegment drops the segment at an index`() {
        val url = parseOk("https://h/a/b/c").newBuilder().removePathSegment(0).build()

        assertEquals("https://h/b/c", url.href)
    }

    @Test
    fun `addPathSegments appends each slash-separated piece`() {
        val url = parseOk("https://h/").newBuilder().addPathSegments("x/y/z").build()

        assertEquals("https://h/x/y/z", url.href)
        assertEquals(listOf("x", "y", "z"), url.pathSegments)
    }

    @Test
    fun `setPathSegment rejects an out-of-bounds index`() {
        assertFailsWith<IndexOutOfBoundsException> {
            parseOk("https://h/a").newBuilder().setPathSegment(5, "x")
        }
    }

    @Test
    fun `setPathSegment to an empty leading segment is rejected rather than silently rooted`() {
        val builder =
            Url
                .Builder()
                .scheme("https")
                .host("h")
                .addPathSegment("a")
                .addPathSegment("b")
                .setPathSegment(0, "")

        assertNull(builder.buildOrNull())
        assertFailsWith<IllegalArgumentException> { builder.build() }
    }

    @Test
    fun `fileName is the last non-empty decoded segment`() {
        assertEquals("b.txt", parseOk("https://h/a/b.txt").fileName())
        assertEquals("a", parseOk("https://h/a/").fileName())
        assertEquals("", parseOk("https://h/").fileName())
    }

    @Test
    fun `fileExtension is the text after the last interior dot`() {
        val cases =
            mapOf(
                "https://h/a/photo.jpeg" to "jpeg",
                "https://h/archive.tar.gz" to "gz",
                "https://h/README" to "",
                "https://h/.bashrc" to "",
                "https://h/file." to "",
            )
        for ((input, expected) in cases) {
            assertEquals(expected, parseOk(input).fileExtension(), "wrong extension for $input")
        }
    }

    @Test
    fun `withPort sets an explicit port`() {
        val url = parseOk("https://h/").withPort(8443)

        assertEquals("https://h:8443/", url.href)
        assertEquals(8443, url.port)
    }

    @Test
    fun `withPort elides a port equal to the scheme default`() {
        val url = parseOk("https://h:8443/").withPort(443)

        assertEquals("https://h/", url.href)
        assertNull(url.port)
    }

    @Test
    fun `withPort rejects an out-of-range port`() {
        assertFailsWith<IllegalArgumentException> { parseOk("https://h/").withPort(99999) }
    }

    @Test
    fun `withPort is a no-op when a port cannot attach`() {
        // WHATWG's port setter ignores a port on a file URL or one with no or empty host.
        val fileUrl = parseOk("file:///tmp/x")
        val opaque = parseOk("mailto:a@b.example")

        assertEquals(fileUrl, fileUrl.withPort(8080))
        assertEquals(opaque, opaque.withPort(8080))
    }

    @Test
    fun `withFragment sets and withoutFragment clears the fragment`() {
        val base = parseOk("https://h/p")

        assertEquals("https://h/p#top", base.withFragment("top").href)
        assertEquals("https://h/p#", base.withFragment("").href)
        assertNull(base.withFragment("top").withoutFragment().fragment)
    }

    @Test
    fun `isSpecial reflects whether the scheme is a WHATWG special scheme`() {
        val special = listOf("https://h/", "ws://h/", "ftp://h/", "file:///x")
        for (input in special) {
            assertTrue(parseOk(input).isSpecial(), "expected $input to be special")
        }
        assertFalse(parseOk("mailto:a@b.example").isSpecial())
    }

    @Test
    fun `relativize round-trips through resolve for a same-origin descendant`() {
        val base = parseOk("https://example.com/a/b/")
        val target = parseOk("https://example.com/a/b/d?q=1")

        val relative = assertNotNull(base.relativize(target), "a same-origin descendant should relativize")

        assertFalse(relative.startsWith("https://"), "the reference should be relative, was $relative")
        assertEquals(target, base.resolveOrThrow(relative))
    }

    @Test
    fun `relativize returns null across differing origins`() {
        val base = parseOk("https://example.com/a")
        val crossHost = parseOk("https://other.example/a/b")

        assertNull(base.relativize(crossHost))
    }

    @Test
    fun `relativize round-trips when the base path has no trailing slash`() {
        val base = parseOk("https://h/a/b")
        val target = parseOk("https://h/a/b/c")

        assertEquals(target, base.resolveOrThrow(assertNotNull(base.relativize(target))))
    }

    @Test
    fun `relativize round-trips for an empty suffix when the target is the base plus a slash`() {
        val base = parseOk("https://h/a/b")
        val target = parseOk("https://h/a/b/")

        assertEquals(target, base.resolveOrThrow(assertNotNull(base.relativize(target))))
    }

    @Test
    fun `relativize round-trips when the base carries a query the target drops`() {
        val base = parseOk("https://h/p?x=1")
        val target = parseOk("https://h/p")

        assertEquals(target, base.resolveOrThrow(assertNotNull(base.relativize(target))))
    }
}
