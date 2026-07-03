/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri

import org.dexpace.kuri.error.HostError
import org.dexpace.kuri.error.ParseResult
import org.dexpace.kuri.error.UriParseError
import org.dexpace.kuri.error.getOrNull
import org.dexpace.kuri.host.Host
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class UrlTest {
    private fun parseOk(input: String): Url = Url.parse(input).getOrNull() ?: fail("expected $input to parse")

    @Test
    fun `exposes every component of a full url`() {
        val url = parseOk("https://user:pass@example.com:8443/path/to/page?q=1&lang=en#section")

        assertEquals("https", url.scheme)
        assertEquals("user", url.username)
        assertEquals("pass", url.password)
        assertEquals(Host.RegName("example.com"), url.host)
        assertEquals("example.com", url.hostName)
        assertEquals(8443, url.port)
        assertEquals(8443, url.effectivePort)
        assertEquals(listOf("path", "to", "page"), url.pathSegments)
        assertEquals("/path/to/page", url.encodedPath)
        assertEquals("q=1&lang=en", url.query)
        assertEquals("section", url.fragment)
        assertEquals("section", url.encodedFragment)
        assertEquals("user:pass@example.com:8443", url.authority)
        assertEquals("https://example.com:8443", url.origin)
    }

    @Test
    fun `href and toString are the canonical serialization`() {
        val href = "https://user:pass@example.com:8443/path/to/page?q=1&lang=en#section"
        val url = parseOk(href)

        assertEquals(href, url.href)
        assertEquals(href, url.toString())
    }

    @Test
    fun `queryParameters expose decoded pairs`() {
        val url = parseOk("https://h/?q=hello%20world&lang=en&lang=fr")

        assertEquals("hello world", url.queryParameters.get("q"))
        assertEquals(listOf<String?>("en", "fr"), url.queryParameters.getAll("lang"))
        assertEquals(setOf("q", "lang"), url.queryParameters.names())
    }

    @Test
    fun `parseOrNull returns null on failure`() {
        assertNull(Url.parseOrNull("http://["))
    }

    @Test
    fun `canParse reflects parse success`() {
        assertTrue(Url.canParse("https://example.com/"))
        assertFalse(Url.canParse("http://["))
    }

    @Test
    fun `default port is elided from the canonical href`() {
        assertEquals("https://example.com/", parseOk("https://example.com:443/").href)
        assertEquals("http://example.com/", parseOk("http://example.com:80/").href)
        assertNull(parseOk("https://example.com:443/").port)
    }

    @Test
    fun `effectivePort recovers the scheme default when the port is elided`() {
        val url = parseOk("https://example.com/")

        assertNull(url.port)
        assertEquals(443, url.effectivePort)
    }

    @Test
    fun `origin omits the port when it equals the scheme default`() {
        assertEquals("https://example.com", parseOk("https://example.com:443/").origin)
    }

    @Test
    fun `origin is opaque for a non-special scheme`() {
        assertEquals("null", parseOk("git://github.com/foo/bar.git").origin)
    }

    @Test
    fun `origin of a blob url adopts the inner https origin`() {
        assertEquals("https://example.com", parseOk("blob:https://example.com:443/").origin)
        assertEquals("http://example.org:88", parseOk("blob:http://example.org:88/").origin)
    }

    @Test
    fun `origin is opaque for a file url`() {
        assertEquals("null", parseOk("file:///etc/hosts").origin)
    }

    @Test
    fun `origin is opaque when a blob inner scheme is not unwrappable`() {
        assertEquals("null", parseOk("blob:ftp://host/path").origin)
    }

    @Test
    fun `origin is opaque when a blob path is not a url`() {
        assertEquals("null", parseOk("blob:d3958f5c-0777-0845-9dcf-2cb28783acaf").origin)
    }

    @Test
    fun `equals and hashCode are canonical-href based and case-folding`() {
        val canonical = parseOk("http://h/")
        val mixedCase = parseOk("HTTP://H/")

        assertEquals(canonical, mixedCase)
        assertEquals(canonical.hashCode(), mixedCase.hashCode())
    }

    @Test
    fun `is usable as a hash set element`() {
        val set = hashSetOf(parseOk("HTTP://H/"))

        assertTrue(parseOk("http://h/") in set)
    }

    @Test
    fun `resolve applies dot-segment reference resolution against a base`() {
        val base = parseOk("http://a/b/c/d")

        val resolved = base.resolve("../g").getOrNull() ?: fail("resolve failed")
        assertEquals("http://a/b/g", resolved.href)
    }

    @Test
    fun `newBuilder then build reproduces an equal value`() {
        val original = parseOk("https://user:pass@example.com:8443/path?q=1#frag")

        val rebuilt = original.newBuilder().build()
        assertEquals(original, rebuilt)
        assertEquals(original.href, rebuilt.href)
    }

    @Test
    fun `static parse factory is reachable`() {
        val result = Url.parse("https://example.com/")
        assertEquals("https://example.com/", result.getOrNull()?.href)
    }

    // --- RFC 6874 zone identifiers ([HOST-17]) ---------------------------------------

    @Test
    fun `the Url profile rejects an IPv6 zone id because WHATWG has no zone-id production`() {
        // The Url profile is the WHATWG profile and accepts no ParseOptions: a `%` in an IPv6
        // literal is always rejected, with no opt-in to relax it ([HOST-17]).
        val err = assertIs<ParseResult.Err>(Url.parse("http://[fe80::1%25eth0]/"))
        val cause = assertIs<UriParseError.InvalidHost>(err.error)
        assertEquals(HostError.ZoneIdRejected, cause.reason)
    }
}
