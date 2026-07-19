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
    fun `hasOpaqueOrigin tracks a blob url's inner origin`() {
        // A blob adopts its inner URL's origin: an http-family inner yields a tuple origin (not
        // opaque), while an inner the blob cannot unwrap to a tuple origin stays opaque.
        assertFalse(parseOk("blob:https://example.com:443/").hasOpaqueOrigin())
        assertTrue(parseOk("blob:ftp://host/path").hasOpaqueOrigin())
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
    fun `removePathSegment of the only segment re-canonicalizes to the root path`() {
        // Emptying the path leaves the WHATWG parser to re-canonicalize a special-scheme empty path
        // back to the root '/', so the shared split/rejoin helpers must not regress this.
        val url = parseOk("http://h/a").newBuilder().removePathSegment(0).build()

        assertEquals("http://h/", url.toString())
    }

    @Test
    fun `addPathSegments appends each slash-separated piece`() {
        val url = parseOk("https://h/").newBuilder().addPathSegments("x/y/z").build()

        assertEquals("https://h/x/y/z", url.href)
        assertEquals(listOf("x", "y", "z"), url.pathSegments)
    }

    @Test
    fun `addPathSegments preserves an interior empty segment`() {
        val url =
            Url
                .Builder()
                .scheme("https")
                .host("h")
                .addPathSegments("a//b")
                .build()

        assertEquals("https://h/a//b", url.href)
        assertEquals(listOf("a", "", "b"), url.pathSegments)
    }

    @Test
    fun `addPathSegments preserves a trailing empty segment`() {
        val url =
            Url
                .Builder()
                .scheme("https")
                .host("h")
                .addPathSegments("a/b/")
                .build()

        assertEquals("https://h/a/b/", url.href)
        assertEquals(listOf("a", "b", ""), url.pathSegments)
    }

    @Test
    fun `addPathSegments onto a directory path fills the trailing slot rather than doubling it`() {
        val url = parseOk("https://h/x/").newBuilder().addPathSegments("a").build()

        assertEquals("https://h/x/a", url.href)
        assertEquals(listOf("x", "a"), url.pathSegments)
    }

    @Test
    fun `addPathSegments drops a leading slash when building a path from scratch`() {
        val url =
            Url
                .Builder()
                .scheme("https")
                .host("h")
                .addPathSegments("/b")
                .build()

        assertEquals("https://h/b", url.href)
        assertEquals(listOf("b"), url.pathSegments)
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
    fun `setPathSegment emptying the first segment of an opaque path is rejected rather than silently rooted`() {
        // A non-special scheme yields a rootless opaque path; emptying segment 0 would serialize with a
        // leading '/' and re-parse as rooted. buildOrNull must never throw (returns null); build throws.
        val builder = parseOk("a:b/c").newBuilder().setPathSegment(0, "")

        assertNull(builder.buildOrNull())
        assertFailsWith<IllegalArgumentException> { builder.build() }
    }

    @Test
    fun `setPathSegment emptying the first segment under an authority stays a rooted path`() {
        // With a host the stored path is already rooted so the edit is representable and not rejected.
        val url = parseOk("https://h/a/b").newBuilder().setPathSegment(0, "").build()

        assertEquals("https://h//b", url.href)
    }

    @Test
    fun `setPathSegment emptying a rooted first segment without an authority is rejected`() {
        // Emptying segment 0 of "/a/b" yields "//b"; the leading "//" would re-parse as an authority, so
        // an authority-less path may not begin with it. buildOrNull must never throw (returns null).
        val builder = parseOk("foo:/a/b").newBuilder().setPathSegment(0, "")

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
    fun `fileName and fileExtension are empty for an opaque path`() {
        val url = parseOk("foo:bar/baz.txt")

        assertEquals("", url.fileName())
        assertEquals("", url.fileExtension())
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
    fun `fileExtension of an all dot stem is empty like a dotfile`() {
        assertEquals("gz", parseOk("https://h/archive.tar.gz").fileExtension())
        assertEquals("gz", parseOk("https://h/a.gz").fileExtension())
        assertEquals("", parseOk("https://h/..gz").fileExtension())
        assertEquals("", parseOk("https://h/.gz").fileExtension())
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
    fun `redact strips userinfo query and fragment but keeps scheme host port and path`() {
        val url = parseOk("https://user:pass@h:8443/p/a?q=1#frag")

        val redacted = url.redact()

        assertEquals("https://h:8443/p/a", redacted.href)
        assertEquals("", redacted.username)
        assertEquals("", redacted.password)
        assertNull(redacted.query)
        assertNull(redacted.fragment)
        assertEquals("h", redacted.hostName)
        assertEquals(8443, redacted.port)
        assertEquals("/p/a", redacted.encodedPath)
    }

    @Test
    fun `redact is a no-op in value when there is no userinfo query or fragment already`() {
        val url = parseOk("https://h:8443/p")

        assertEquals(url, url.redact())
    }

    @Test
    fun `redact on an opaque-path URL clears only its query and fragment`() {
        // mailto: has no authority at all, so its "userinfo" is really part of the opaque path and is
        // untouched; only the real query and fragment components are stripped.
        val url = parseOk("mailto:a@example.com?subject=hi#top")

        val redacted = url.redact()

        assertEquals("mailto:a@example.com", redacted.href)
        assertNull(redacted.query)
        assertNull(redacted.fragment)
    }

    @Test
    fun `isDirectory and hasTrailingSlash agree on a trailing empty segment`() {
        val trailingSlash = parseOk("https://h/a/")

        assertTrue(trailingSlash.isDirectory())
        assertTrue(trailingSlash.hasTrailingSlash())

        val noTrailingSlash = parseOk("https://h/a")

        assertFalse(noTrailingSlash.isDirectory())
        assertFalse(noTrailingSlash.hasTrailingSlash())
    }

    @Test
    fun `isDirectory is true for the root path`() {
        assertTrue(parseOk("https://h/").isDirectory())
        // A special-scheme empty path is WHATWG-canonicalized to the root "/", which is itself a
        // directory path, so this also exercises the empty-input edge case.
        assertTrue(parseOk("https://h").isDirectory())
    }

    @Test
    fun `isDirectory is false for an opaque path with no trailing slash`() {
        assertFalse(parseOk("mailto:a@example.com").isDirectory())
    }

    @Test
    fun `isDirectory is true for an opaque path ending in a slash`() {
        val url = parseOk("urn:example:a/")

        // Confirms the path really is opaque (verbatim, colon-bearing text) rather than having
        // been reinterpreted as segments, so the assertions below can't pass vacuously.
        assertEquals("urn:example:a/", url.href)

        assertTrue(url.isDirectory())
        assertTrue(url.hasTrailingSlash())
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
