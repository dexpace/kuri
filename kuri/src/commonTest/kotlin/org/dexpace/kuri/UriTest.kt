/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri

import org.dexpace.kuri.error.getOrNull
import org.dexpace.kuri.host.Host
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class UriTest {
    private fun parseOk(input: String): Uri = Uri.parse(input).getOrNull() ?: fail("expected $input to parse")

    @Test
    fun `exposes every component of a generic uri`() {
        val uri = parseOk("foo://u@h:8042/over/there?q#n")

        assertEquals("foo", uri.scheme)
        assertEquals("u", uri.userInfo)
        assertEquals(Host.RegName("h"), uri.host)
        assertEquals("h", uri.hostName)
        assertEquals(8042, uri.port)
        assertEquals("/over/there", uri.path)
        assertEquals(listOf("over", "there"), uri.pathSegments)
        assertEquals("q", uri.query)
        assertEquals("n", uri.fragment)
        assertEquals("u@h:8042", uri.authority)
    }

    @Test
    fun `splits a user colon password userinfo`() {
        val uri = parseOk("foo://user:secret@h/p")

        assertEquals("user:secret", uri.userInfo)
        assertEquals("user:secret@h", uri.authority)
    }

    @Test
    fun `uriString and toString are the canonical unnormalized serialization`() {
        val input = "foo://u@h:8042/over/there?q#n"
        val uri = parseOk(input)

        assertEquals(input, uri.uriString)
        assertEquals(input, uri.toString())
    }

    @Test
    fun `a scheme-relative reference carries no scheme`() {
        val uri = parseOk("//h/p")

        assertNull(uri.scheme)
        assertEquals(Host.RegName("h"), uri.host)
        assertEquals(listOf("p"), uri.pathSegments)
    }

    @Test
    fun `an absolute-path relative reference carries no scheme`() {
        val uri = parseOk("/p")

        assertNull(uri.scheme)
        assertNull(uri.host)
        assertEquals("/p", uri.path)
    }

    @Test
    fun `a rootless relative reference carries no scheme`() {
        val uri = parseOk("a/b")

        assertNull(uri.scheme)
        assertNull(uri.host)
        assertEquals(listOf("a", "b"), uri.pathSegments)
        assertNull(uri.userInfo)
        assertNull(uri.authority)
    }

    @Test
    fun `the explicit port is preserved without default-port elision`() {
        val uri = parseOk("http://h:80/")

        assertEquals(80, uri.port)
        assertEquals("http://h:80/", uri.uriString)
    }

    @Test
    fun `dot segments in the path are preserved verbatim`() {
        val uri = parseOk("http://h/a/../b")

        assertEquals("/a/../b", uri.path)
        assertEquals(listOf("a", "..", "b"), uri.pathSegments)
    }

    @Test
    fun `scheme and host case are preserved and never normalized in equals`() {
        val preserved = parseOk("HTTP://H/")

        assertEquals("HTTP", preserved.scheme)
        assertEquals("H", preserved.hostName)
        assertEquals("HTTP://H/", preserved.uriString)
        assertEquals(preserved, parseOk("HTTP://H/"))
    }

    @Test
    fun `differently-cased equivalents are structurally unequal`() {
        assertFalse(parseOk("HTTP://H/") == parseOk("http://h/"))
    }

    @Test
    fun `normalized applies the RFC six-two syntax normalizations`() {
        val normalized = parseOk("HTTP://www.EXAMPLE.com/%7e").normalized()

        assertEquals("http://www.example.com/~", normalized.uriString)
    }

    @Test
    fun `normalizedEquals folds case-only differences`() {
        assertTrue(parseOk("HTTP://H/").normalizedEquals(parseOk("http://h/")))
    }

    @Test
    fun `normalizedEquals stays false for genuinely different values`() {
        assertFalse(parseOk("http://h/a").normalizedEquals(parseOk("http://h/b")))
    }

    @Test
    fun `resolve applies dot-segment reference resolution against a base`() {
        val base = parseOk("http://a/b/c/d")

        val resolved = base.resolve("../g").getOrNull() ?: fail("resolve failed")
        assertEquals("http://a/b/g", resolved.uriString)
    }

    @Test
    fun `equals and hashCode are structural over the canonical string`() {
        val left = parseOk("foo://h/p?q#n")
        val right = parseOk("foo://h/p?q#n")

        assertEquals(left, right)
        assertEquals(left.hashCode(), right.hashCode())
    }

    @Test
    fun `is usable as a hash set element`() {
        val set = hashSetOf(parseOk("foo://h/p"))

        assertTrue(parseOk("foo://h/p") in set)
    }

    @Test
    fun `parseOrNull returns null on failure`() {
        assertNull(Uri.parseOrNull("http://h:99999999999999999999/p"))
    }

    @Test
    fun `canParse reflects parse success`() {
        assertTrue(Uri.canParse("foo://h/p"))
        assertFalse(Uri.canParse("http://h/p?a=%2"))
    }

    @Test
    fun `static parse factory is reachable`() {
        val result = Uri.parse("foo://h/p")
        assertEquals("foo://h/p", result.getOrNull()?.uriString)
    }
}
