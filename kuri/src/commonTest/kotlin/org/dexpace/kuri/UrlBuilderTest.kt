/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri

import org.dexpace.kuri.error.UriSyntaxException
import org.dexpace.kuri.error.getOrNull
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
    fun `addPathSegment encodes a slash while addEncodedPathSegment keeps it as a separator`() {
        val base = parseOk("https://h/")

        val decoded = base.newBuilder().addPathSegment("x/y").build()
        val encoded = base.newBuilder().addEncodedPathSegment("x/y").build()

        assertEquals("/x%2Fy", decoded.encodedPath)
        assertEquals(listOf("x/y"), decoded.pathSegments)
        assertEquals("/x/y", encoded.encodedPath)
        assertEquals(listOf("x", "y"), encoded.pathSegments)
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
}
