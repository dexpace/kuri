/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri

import org.dexpace.kuri.error.UriSyntaxException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.fail

class UrlBuilderTest {
    private fun parseOk(input: String): Url = Url.parse(input).getOrNull() ?: fail("expected $input to parse")

    @Test
    fun `changing the host produces a new canonical href`() {
        val rebuilt = parseOk("https://example.com/p").newBuilder().host("other.test").build()

        assertEquals("https://other.test/p", rebuilt.href)
    }

    @Test
    fun `changing the port produces a new canonical href`() {
        val rebuilt = parseOk("https://example.com/").newBuilder().port(8443).build()

        assertEquals("https://example.com:8443/", rebuilt.href)
        assertEquals(8443, rebuilt.port)
    }

    @Test
    fun `setting the port to a scheme default elides it`() {
        val rebuilt = parseOk("https://example.com:8443/").newBuilder().port(443).build()

        assertEquals("https://example.com/", rebuilt.href)
    }

    @Test
    fun `adding a path segment extends the path`() {
        val rebuilt = parseOk("https://example.com/a").newBuilder().addPathSegment("b").build()

        assertEquals("/a/b", rebuilt.encodedPath)
    }

    @Test
    fun `setting a query parameter rewrites the query`() {
        val rebuilt = parseOk("https://example.com/").newBuilder().setQueryParameter("k", "v").build()

        assertEquals("k=v", rebuilt.query)
        assertEquals("v", rebuilt.queryParameters.get("k"))
    }

    @Test
    fun `setting the fragment rewrites it`() {
        val rebuilt = parseOk("https://example.com/").newBuilder().fragment("top").build()

        assertEquals("top", rebuilt.fragment)
        assertEquals("https://example.com/#top", rebuilt.href)
    }

    @Test
    fun `addPathSegment percent-encodes a slash so it stays within one segment`() {
        val decoded = parseOk("https://h/").newBuilder().addPathSegment("x/y").build()

        assertEquals("/x%2Fy", decoded.encodedPath)
        assertEquals(listOf("x/y"), decoded.pathSegments)
    }

    @Test
    fun `addEncodedPathSegment rejects a raw delimiter that would re-split the value`() {
        val base = parseOk("https://h/")

        for (bad in listOf("x/y", "a?b", "a#b", "a\\b")) {
            assertFailsWith<IllegalArgumentException>("expected $bad to be rejected") {
                base.newBuilder().addEncodedPathSegment(bad)
            }
        }
    }

    @Test
    fun `addPathSegment percent-encodes a backslash so the special-scheme parser cannot split it`() {
        val rebuilt = parseOk("https://h/").newBuilder().addPathSegment("a\\b").build()

        assertEquals("/a%5Cb", rebuilt.encodedPath)
        assertEquals(listOf("a\\b"), rebuilt.pathSegments)
    }

    @Test
    fun `addPathSegment treats a percent sign as data so the segment round-trips`() {
        val rebuilt = parseOk("https://h/").newBuilder().addPathSegment("a%2Fb").build()

        assertEquals("/a%252Fb", rebuilt.encodedPath)
        assertEquals(listOf("a%2Fb"), rebuilt.pathSegments)
    }

    @Test
    fun `username and password setters percent-encode userinfo`() {
        val rebuilt =
            parseOk("https://example.com/")
                .newBuilder()
                .username("a b")
                .password("p@ss")
                .build()

        assertEquals("a%20b", rebuilt.username)
        assertEquals("p%40ss", rebuilt.password)
    }

    @Test
    fun `encodedPath replaces the whole path`() {
        val rebuilt = parseOk("https://example.com/a/b").newBuilder().encodedPath("/c/d").build()

        assertEquals("/c/d", rebuilt.encodedPath)
    }

    @Test
    fun `a fresh builder assembles a url from scratch`() {
        val url =
            Url
                .Builder()
                .scheme("https")
                .host("example.com")
                .addPathSegment("docs")
                .setQueryParameter("page", "2")
                .fragment("intro")
                .build()

        assertEquals("https://example.com/docs?page=2#intro", url.href)
    }

    @Test
    fun `build throws when the assembled components are invalid`() {
        assertFailsWith<UriSyntaxException> { Url.Builder().build() }
    }

    @Test
    fun `setting an out-of-range port is rejected`() {
        assertFailsWith<IllegalArgumentException> { Url.Builder().port(70000) }
    }

    @Test
    fun `setting an invalid scheme is rejected`() {
        assertFailsWith<IllegalArgumentException> { Url.Builder().scheme("ht tp") }
    }

    @Test
    fun `two segments under an authority join with a single separator`() {
        val url =
            Url
                .Builder()
                .scheme("http")
                .host("h")
                .addPathSegment("a")
                .addPathSegment("b")
                .build()

        assertEquals("http://h/a/b", url.href)
        assertEquals(listOf("a", "b"), url.pathSegments)
    }

    @Test
    fun `a segment fills the open slot left by a trailing slash`() {
        val rebuilt = parseOk("http://h/").newBuilder().addPathSegment("x").build()

        assertEquals("http://h/x", rebuilt.href)
    }

    @Test
    fun `an empty then non-empty segment collapses into the open slot like okhttp`() {
        val url =
            Url
                .Builder()
                .scheme("http")
                .host("h")
                .addPathSegment("")
                .addPathSegment("x")
                .build()

        assertEquals("http://h/x", url.href)
    }

    @Test
    fun `an empty segment materializes as a trailing slash`() {
        val url =
            Url
                .Builder()
                .scheme("http")
                .host("h")
                .addPathSegment("a")
                .addPathSegment("")
                .build()

        assertEquals("http://h/a/", url.href)
        assertEquals(listOf("a", ""), url.pathSegments)
    }

    @Test
    fun `builds an opaque mailto path segment-wise`() {
        val url =
            Url
                .Builder()
                .scheme("mailto")
                .addPathSegment("o@x")
                .build()

        assertEquals("mailto:o@x", url.href)
    }

    @Test
    fun `builds a rootless urn path segment-wise`() {
        val url =
            Url
                .Builder()
                .scheme("urn")
                .addPathSegment("isbn:0451450523")
                .build()

        assertEquals("urn:isbn:0451450523", url.href)
    }

    @Test
    fun `a lone empty segment with no authority contributes nothing`() {
        val url =
            Url
                .Builder()
                .scheme("urn")
                .addPathSegment("")
                .build()

        assertEquals("urn:", url.href)
    }

    @Test
    fun `segment rooting does not depend on setter order`() {
        val segmentThenHost =
            Url
                .Builder()
                .scheme("https")
                .addPathSegment("a")
                .host("h")
                .build()
        val hostThenSegment =
            Url
                .Builder()
                .scheme("https")
                .host("h")
                .addPathSegment("a")
                .build()

        assertEquals("https://h/a", segmentThenHost.href)
        assertEquals("https://h/a", hostThenSegment.href)
    }

    @Test
    fun `a host-less special scheme is rejected instead of reading the first path segment as a host`() {
        assertFailsWith<IllegalArgumentException> {
            Url
                .Builder()
                .scheme("https")
                .addPathSegment("a")
                .build()
        }
        assertFailsWith<IllegalArgumentException> {
            Url
                .Builder()
                .scheme("https")
                .encodedPath("a/b")
                .build()
        }
    }

    @Test
    fun `a host-less file scheme still builds`() {
        val url =
            Url
                .Builder()
                .scheme("file")
                .addPathSegment("p")
                .build()

        assertEquals("file:///p", url.href)
    }

    @Test
    fun `a verbatim rootless path with an authority is rejected instead of merging into the host`() {
        assertFailsWith<IllegalArgumentException> {
            Url
                .Builder()
                .scheme("https")
                .host("h")
                .encodedPath("a/b")
                .build()
        }
    }

    @Test
    fun `addEncodedPathSegment collapses a dot segment at build per whatwg shortening`() {
        val url =
            Url
                .Builder()
                .scheme("https")
                .host("h")
                .addEncodedPathSegment("a")
                .addEncodedPathSegment("..")
                .build()

        assertEquals("https://h/", url.href)
    }

    @Test
    fun `setQueryParameter preserves every pair of a large existing query`() {
        val pairs = (0 until 1500).joinToString("&") { "k$it=v$it" }
        val url = Url.parseOrThrow("https://h/?$pairs")

        val rebuilt = url.newBuilder().setQueryParameter("added", "yes").build()

        val params = rebuilt.queryParameters
        assertEquals(1501, params.size)
        assertEquals("v0", params.get("k0"))
        assertEquals("v1499", params.get("k1499"))
        assertEquals("yes", params.get("added"))
    }

    @Test
    fun `queryParameters projects every pair of a large query losslessly`() {
        val pairs = (0 until 1500).joinToString("&") { "k$it=v$it" }
        val url = Url.parseOrThrow("https://h/?$pairs")

        val params = url.queryParameters

        assertEquals(1500, params.size)
        assertEquals("v0", params.get("k0"))
        assertEquals("v1499", params.get("k1499"))
    }
}
