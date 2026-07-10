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
import kotlin.test.assertNotNull
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
    fun `encodedPath setter takes an already-encoded path distinct from the decoded getter`() {
        val uri =
            Uri
                .Builder()
                .scheme("http")
                .host("h")
                .encodedPath("/a%2Fb")
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
    fun `queryParameters is empty for a present-but-empty query like URLSearchParams`() {
        // A bare `?` decodes to no pairs, exactly as `URLSearchParams("")` yields none; the
        // absent-vs-present distinction stays observable on the raw `query` string, not the pair count.
        assertTrue(parseOk("http://h/p?").queryParameters().isEmpty())
        assertTrue(parseOk("http://h/p").queryParameters().isEmpty())
        assertEquals("", parseOk("http://h/p?").query)
        assertNull(parseOk("http://h/p").query)
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

    @Test
    fun `addQueryParameter on a bare question mark does not leak a leading ampersand`() {
        val uri = parseOk("http://h/p?").newBuilder().addQueryParameter("a", "1").build()

        assertEquals("http://h/p?a=1", uri.uriString)
    }

    @Test
    fun `removeAllQueryParameters is a no-op on a query-less URI`() {
        val uri = parseOk("http://h/p").newBuilder().removeAllQueryParameters("x").build()

        assertEquals("http://h/p", uri.uriString)
        assertNull(uri.query)
    }

    @Test
    fun `removeAllQueryParameters of the last pair leaves a present-but-empty query`() {
        // The Uri profile keeps a present-but-empty `?` when the last pair is removed, unlike Url which
        // drops the `?`; the absent-vs-present distinction stays observable on the raw query string.
        val uri = parseOk("http://h/p?a=1").newBuilder().removeAllQueryParameters("a").build()

        assertEquals("", uri.query)
        assertEquals("http://h/p?", uri.uriString)
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

    @Test
    fun `host sink round-trips a zoned ipv6 host when zone ids are enabled`() {
        val zoneOptions = ParseOptions.Builder().allowIpv6ZoneId(true).build()
        val zoned = Uri.parse("http://[fe80::1%25eth0]/", zoneOptions).getOrNull()?.host ?: fail("expected a host")

        val uri =
            Uri
                .Builder()
                .allowIpv6ZoneId(true)
                .scheme("http")
                .host(zoned)
                .encodedPath("/")
                .build()

        assertEquals("[fe80::1%25eth0]", uri.hostName)
    }

    // --- relativize ---

    @Test
    fun `relativize stays total when a dot-collapsed suffix reparses as an invalid authority`() {
        // The candidate suffix "/.//h:zz" is a valid rootless-path reference, but resolving it removes the
        // "/./" and yields "//h:zz", which reparses as an authority with a non-numeric port. That resolution
        // failure must surface as "no relative form" (null), never as a thrown exception.
        val base = parseOk("s:/a/b")
        val target = parseOk("s:/a//.//h:zz")

        assertNull(base.relativize(target))
    }

    @Test
    fun `relativize emits a relative path for a same-origin descendant`() {
        val base = parseOk("http://h/a/b/")
        val target = parseOk("http://h/a/b/c/d")

        val relative = assertNotNull(base.relativize(target))

        assertEquals("c/d", relative.uriString)
        assertEquals(target, base.resolveOrThrow(relative.uriString))
    }

    @Test
    fun `relativize returns null across different authorities`() {
        val target = parseOk("http://other/x")

        assertNull(parseOk("http://h/a/").relativize(target))
    }

    @Test
    fun `relativize returns null for an opaque path`() {
        val target = parseOk("mailto:b@example.com")

        assertNull(parseOk("mailto:a@example.com").relativize(target))
    }

    @Test
    fun `relativize round-trips when the base path has no trailing slash`() {
        // §5.2.3 merges onto the base's directory (/a/), so the reference must be "b/c", not "c".
        val base = parseOk("http://h/a/b")
        val target = parseOk("http://h/a/b/c")

        assertEquals(target, base.resolveOrThrow(assertNotNull(base.relativize(target)).uriString))
    }

    @Test
    fun `relativize round-trips for an empty suffix when the target is the base plus a slash`() {
        val base = parseOk("http://h/a/b")
        val target = parseOk("http://h/a/b/")

        assertEquals(target, base.resolveOrThrow(assertNotNull(base.relativize(target)).uriString))
    }

    @Test
    fun `relativize round-trips when the base carries a query the target drops`() {
        // An empty reference would re-inherit the base query; the reference must clear it.
        val base = parseOk("http://h/p?x=1")
        val target = parseOk("http://h/p")

        assertEquals(target, base.resolveOrThrow(assertNotNull(base.relativize(target)).uriString))
    }

    @Test
    fun `relativize returns null when the base has no scheme`() {
        // relativize inverts resolve, which requires an absolute base; a scheme-less relative reference
        // has no relative form, and must return null rather than throw.
        val base = parseOk("a/b/")
        val target = parseOk("a/b/c")

        assertNull(base.relativize(target))
    }

    @Test
    fun `relativize returns null when the target is not under the base directory`() {
        // Same scheme and authority, so the two share a hierarchy, but the target path lies outside the
        // base's directory, so no rootless suffix reaches it.
        val base = parseOk("http://h/a/")
        val target = parseOk("http://h/x")

        assertNull(base.relativize(target))
    }

    @Test
    fun `relativize yields a dot reference when the target is the base directory`() {
        // The target equals the base's own directory, so the reference is "." rather than an empty
        // suffix (which would re-inherit the base query on resolution).
        val base = parseOk("http://h/a/b")
        val target = parseOk("http://h/a/")

        val relative = assertNotNull(base.relativize(target))

        assertEquals(".", relative.uriString)
        assertEquals(target, base.resolveOrThrow(relative.uriString))
    }

    @Test
    fun `relativize round-trips for an IPv6-authority base`() {
        // The structured resolver re-serializes the inherited base authority; an IPv6 literal is the
        // non-trivial authority case, so pin that it still round-trips through resolve.
        val base = parseOk("http://[::1]/a/b/")
        val target = parseOk("http://[::1]/a/b/c")

        assertEquals(target, base.resolveOrThrow(assertNotNull(base.relativize(target)).uriString))
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
    fun `addPathSegments preserves an interior empty segment`() {
        val uri =
            Uri
                .Builder()
                .scheme("http")
                .host("h")
                .addPathSegments("a//b")
                .build()

        assertEquals("http://h/a//b", uri.uriString)
        assertEquals(listOf("a", "", "b"), uri.pathSegments)
    }

    @Test
    fun `addPathSegments preserves a trailing empty segment`() {
        val uri =
            Uri
                .Builder()
                .scheme("http")
                .host("h")
                .addPathSegments("a/b/")
                .build()

        assertEquals("http://h/a/b/", uri.uriString)
        assertEquals(listOf("a", "b", ""), uri.pathSegments)
    }

    @Test
    fun `addPathSegments onto a directory path fills the trailing slot rather than doubling it`() {
        val uri = parseOk("http://h/x/").newBuilder().addPathSegments("a").build()

        assertEquals("http://h/x/a", uri.uriString)
        assertEquals(listOf("x", "a"), uri.pathSegments)
    }

    @Test
    fun `addPathSegments drops a leading slash when building a path from scratch`() {
        val uri =
            Uri
                .Builder()
                .scheme("http")
                .host("h")
                .addPathSegments("/b")
                .build()

        assertEquals("http://h/b", uri.uriString)
        assertEquals(listOf("b"), uri.pathSegments)
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
        assertFailsWith<IndexOutOfBoundsException> {
            parseOk("http://h/a").newBuilder().setPathSegment(5, "x")
        }
    }

    @Test
    fun `setPathSegment to an empty leading segment is rejected rather than silently rooted`() {
        // "a/b" -> ["", "b"] would serialize as "/b" and re-parse as rooted; reject it as uncomposable
        // instead of throwing from buildOrNull (which must never throw).
        val builder =
            Uri
                .Builder()
                .scheme("http")
                .host("h")
                .addPathSegment("a")
                .addPathSegment("b")
                .setPathSegment(0, "")

        assertNull(builder.buildOrNull())
        assertFailsWith<IllegalArgumentException> { builder.build() }
    }

    @Test
    fun `setPathSegment emptying a parsed rootless first segment is rejected rather than silently rooted`() {
        // A newBuilder() of rootless "a/b" whose segment 0 becomes "" would serialize as "/b" and
        // re-parse as rooted — the same shape the from-scratch builder rejects. buildOrNull must never
        // throw (returns null); build surfaces the rejection as IllegalArgumentException.
        val builder = parseOk("a/b").newBuilder().setPathSegment(0, "")

        assertNull(builder.buildOrNull())
        assertFailsWith<IllegalArgumentException> { builder.build() }
    }

    @Test
    fun `removePathSegment emptying a parsed rootless first segment is rejected rather than silently rooted`() {
        // Removing segment 0 of rootless "a//b" leaves ["", "b"], which re-roots to "/b".
        val builder = parseOk("a//b").newBuilder().removePathSegment(0)

        assertNull(builder.buildOrNull())
        assertFailsWith<IllegalArgumentException> { builder.build() }
    }

    @Test
    fun `setPathSegment emptying the first segment under an authority stays a rooted path`() {
        // With a host the stored path is already rooted so the edit is representable and not rejected.
        val uri = parseOk("http://h/a/b").newBuilder().setPathSegment(0, "").build()

        assertEquals("http://h//b", uri.uriString)
    }

    @Test
    fun `setPathSegment emptying a rooted first segment without an authority is rejected`() {
        // RFC 3986 forbids an authority-less absolute path from beginning with '//'. Emptying segment 0
        // of "/a/b" yields "//b" whose leading "//" would re-parse as an authority; reject it instead of
        // silently injecting a '.' guard segment.
        for (input in listOf("/a/b", "foo:/a/b", "file:/a/b")) {
            val builder = parseOk(input).newBuilder().setPathSegment(0, "")

            assertNull(builder.buildOrNull(), "expected $input to reject an emptied leading segment")
            assertFailsWith<IllegalArgumentException> { builder.build() }
        }
    }

    @Test
    fun `fileName is the last non-empty decoded segment`() {
        assertEquals("c d.txt", parseOk("http://h/a/b/c%20d.txt").fileName())
        assertEquals("b", parseOk("http://h/a/b/").fileName())
        assertEquals("", parseOk("http://h/").fileName())
    }

    @Test
    fun `fileName and fileExtension are empty for an opaque path`() {
        for (input in listOf("mailto:john.doe@example.com", "urn:isbn:0451450523")) {
            val uri = parseOk(input)

            assertEquals("", uri.fileName(), "expected no file name for $input")
            assertEquals("", uri.fileExtension(), "expected no file extension for $input")
        }
    }

    @Test
    fun `fileExtension is the text after the last dot of the file name`() {
        assertEquals("gz", parseOk("http://h/a/archive.tar.gz").fileExtension())
        assertEquals("", parseOk("http://h/a/README").fileExtension())
        assertEquals("", parseOk("http://h/a/.bashrc").fileExtension())
    }

    @Test
    fun `fileExtension of an all dot stem is empty like a dotfile`() {
        val cases =
            mapOf(
                "http://h/archive.tar.gz" to "gz",
                "http://h/a.gz" to "gz",
                "http://h/..gz" to "",
                "http://h/.gz" to "",
                "http://h/file." to "",
            )
        for ((input, expected) in cases) {
            assertEquals(expected, parseOk(input).fileExtension(), "wrong extension for $input")
        }
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
    fun `isOpaquePath is false for a scheme with a rooted authority-less path`() {
        // A scheme and no authority, but the path is rooted ("/a/b"), so it is hierarchical rather than
        // the rootless opaque shape; this exercises the non-rootless arm of the opaque-path test.
        assertFalse(parseOk("foo:/a/b").isOpaquePath())
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
    fun `withPort is a no-op on an authority-less URI`() {
        // A port has nowhere to attach without an authority, so the value is returned unchanged.
        val uri = parseOk("mailto:a@example.com")

        assertEquals(uri, uri.withPort(25))
    }

    @Test
    fun `withFragment and withoutFragment rewrite the fragment`() {
        assertEquals("x", parseOk("http://h/p").withFragment("x").fragment)
        assertNull(parseOk("http://h/p#x").withoutFragment().fragment)
    }

    @Test
    fun `relativize returns null across different schemes`() {
        // The authorities match, but a differing scheme fails the shared-hierarchy check, so there is no
        // relative form even though the paths align; this exercises the scheme-mismatch arm.
        val base = parseOk("http://h/a/b/")
        val target = parseOk("https://h/a/b/c")

        assertNull(base.relativize(target))
    }

    @Test
    fun `isOpaquePath is false for a scheme with an empty path`() {
        // A scheme, no authority, and an empty rootless path: the first-segment lookup finds nothing, so
        // the rootless-opaque test returns false rather than treating it as opaque.
        assertFalse(parseOk("foo:").isOpaquePath())
    }

    @Test
    fun `userInfo and authority reconstruct an empty-username credential`() {
        // An empty username with a present password takes the else arm of the userinfo reconstruction.
        val uri = parseOk("http://:pass@h:8/p")

        assertEquals(":pass", uri.userInfo)
        assertEquals(":pass@h:8", uri.authority)
    }

    @Test
    fun `authority reconstructs a host with no userinfo and no port`() {
        // No userinfo folds to the empty-credentials arm and the absent port folds to the empty-port arm.
        val uri = parseOk("http://h/p")

        assertNull(uri.userInfo)
        assertEquals("h", uri.authority)
    }
}
