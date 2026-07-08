/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri

import kotlin.test.Test
import kotlin.test.assertEquals

class UrlSetterTest {
    private fun url(s: String): Url = Url.parseOrThrow(s)

    @Test
    fun `withProtocol changes a special scheme to another special scheme`() {
        assertEquals("https://example.net/", url("http://example.net/").withProtocol("https").href)
    }

    @Test
    fun `withProtocol is a no-op when crossing the special boundary`() {
        val original = url("http://example.net/")
        assertEquals(original.href, original.withProtocol("fake").href)
    }

    @Test
    fun `withUsername percent-encodes and sets the user`() {
        assertEquals("wss://me@example.org/", url("wss://example.org/").withUsername("me").href)
    }

    @Test
    fun `withUsername is a no-op when the url has no host`() {
        val original = url("mailto:x@example.com")
        assertEquals(original.href, original.withUsername("me").href)
    }

    @Test
    fun `withPassword sets the password`() {
        assertEquals("wss://:pw@example.org/", url("wss://example.org/").withPassword("pw").href)
    }

    @Test
    fun `withHostname replaces the host and preserves the port`() {
        assertEquals("http://example.com:90/", url("http://h:90/").withHostname("example.com").href)
    }

    @Test
    fun `withHost replaces host and port together`() {
        assertEquals("http://example.com:81/", url("http://h:90/").withHost("example.com:81").href)
    }

    @Test
    fun `withHostname is a no-op on an opaque-path url`() {
        val original = url("mailto:x@example.com")
        assertEquals(original.href, original.withHostname("example.org").href)
    }

    @Test
    fun `withHost stops at a path terminator and leaves the path unchanged`() {
        assertEquals("http://example.com/p", url("http://h/p").withHost("example.com/x").href)
    }

    @Test
    fun `withPort string sets the port`() {
        assertEquals("http://h:8080/", url("http://h/").withPort("8080").href)
    }

    @Test
    fun `withPort empty string removes the port`() {
        assertEquals("http://h/", url("http://h:8080/").withPort("").href)
    }

    @Test
    fun `withPort ignores trailing junk after the digits`() {
        assertEquals("http://h:8080/", url("http://h/").withPort("8080stuff").href)
    }

    @Test
    fun `withPort is a no-op on a hostless url`() {
        val original = url("mailto:x@example.com")
        assertEquals(original.href, original.withPort("80").href)
    }

    @Test
    fun `withPathname replaces the path`() {
        assertEquals("http://h/a/b", url("http://h/old").withPathname("/a/b").href)
    }

    @Test
    fun `withPathname is a no-op on an opaque-path url`() {
        val original = url("mailto:x@example.com")
        assertEquals(original.href, original.withPathname("/a").href)
    }
}
