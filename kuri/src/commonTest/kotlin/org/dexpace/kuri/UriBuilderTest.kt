/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri

import org.dexpace.kuri.error.getOrNull
import org.dexpace.kuri.error.getOrThrow
import org.dexpace.kuri.host.Host
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class UriBuilderTest {
    private fun parseOk(input: String): Uri = Uri.parse(input).getOrNull() ?: fail("expected $input to parse")

    @Test
    fun `builds a full generic uri from its components`() {
        val uri =
            Uri
                .Builder()
                .scheme("foo")
                .userInfo("u")
                .host("h")
                .port(8042)
                .encodedPath("/over/there")
                .query("q")
                .fragment("n")
                .build()

        assertEquals("foo://u@h:8042/over/there?q#n", uri.uriString)
    }

    @Test
    fun `builds a relative reference with a null scheme`() {
        val uri =
            Uri
                .Builder()
                .host("h")
                .encodedPath("/p")
                .build()

        assertNull(uri.scheme)
        assertEquals("//h/p", uri.uriString)
    }

    @Test
    fun `newBuilder then build reproduces an equal value`() {
        val original = parseOk("foo://u@h:8042/over/there?q#n")

        val rebuilt = original.newBuilder().build()

        assertEquals(original, rebuilt)
        assertEquals(original.uriString, rebuilt.uriString)
    }

    @Test
    fun `newBuilder then build reproduces a rootless reference`() {
        // The pre-fill copies source.path, so the round-trip depends on the rootless-aware path getter.
        val original = parseOk("a/b/c")

        val rebuilt = original.newBuilder().build()

        assertEquals(original, rebuilt)
        assertEquals("a/b/c", rebuilt.uriString)
    }

    @Test
    fun `builds a rootless relative reference from a bare encoded path`() {
        val uri = Uri.Builder().encodedPath("a/b").build()

        assertNull(uri.scheme)
        assertEquals("a/b", uri.uriString)
    }

    @Test
    fun `a setter override changes only the targeted component`() {
        val original = parseOk("foo://h/a/b?q#n")

        val rebuilt =
            original
                .newBuilder()
                .fragment(null)
                .query(null)
                .build()

        assertEquals("foo://h/a/b", rebuilt.uriString)
    }

    @Test
    fun `the explicit port is preserved through a build`() {
        val uri =
            Uri
                .Builder()
                .scheme("http")
                .host("h")
                .port(80)
                .encodedPath("/")
                .build()

        assertEquals("http://h:80/", uri.uriString)
    }

    @Test
    fun `an invalid scheme is rejected eagerly`() {
        assertFailsWith<IllegalArgumentException> { Uri.Builder().scheme("1bad") }
    }

    @Test
    fun `a malformed component fails the build`() {
        val builder =
            Uri
                .Builder()
                .scheme("foo")
                .host("h")
                .encodedPath("/p?a=%2g")

        assertFailsWith<IllegalArgumentException> { builder.build() }
    }

    @Test
    fun `a colon in the first path segment is guarded so it is not read as a scheme`() {
        val uri = Uri.Builder().encodedPath("a:b").build()

        assertNull(uri.scheme)
        assertEquals("./a:b", uri.uriString)
        assertEquals(uri, uri.newBuilder().build())
    }

    @Test
    fun `a colon in a later path segment is not guarded`() {
        val uri = Uri.Builder().encodedPath("a/b:c").build()

        assertNull(uri.scheme)
        assertEquals("a/b:c", uri.uriString)
    }

    @Test
    fun `a present scheme absorbs a first-segment colon without a guard`() {
        val uri =
            Uri
                .Builder()
                .scheme("foo")
                .encodedPath("a:b")
                .build()

        assertEquals("foo", uri.scheme)
        assertEquals("foo:a:b", uri.uriString)
    }

    @Test
    fun `a leading double slash is guarded when there is no authority`() {
        val uri = Uri.Builder().encodedPath("//not-authority").build()

        assertNull(uri.host)
        assertEquals("/.//not-authority", uri.uriString)
    }

    @Test
    fun `a leading double slash is guarded even with a scheme present`() {
        val uri =
            Uri
                .Builder()
                .scheme("foo")
                .encodedPath("//bar")
                .build()

        assertNull(uri.host)
        assertEquals("foo:/.//bar", uri.uriString)
    }

    @Test
    fun `an authority paired with a rootless path is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            Uri
                .Builder()
                .host("h")
                .encodedPath("p")
                .build()
        }
    }

    @Test
    fun `an authority paired with an empty path is allowed`() {
        val uri =
            Uri
                .Builder()
                .scheme("http")
                .host("h")
                .build()

        assertEquals("http://h", uri.uriString)
    }

    @Test
    fun `userInfo without a host is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            Uri
                .Builder()
                .userInfo("u")
                .encodedPath("/p")
                .build()
        }
    }

    @Test
    fun `a port without a host is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            Uri
                .Builder()
                .port(8080)
                .encodedPath("/p")
                .build()
        }
    }

    @Test
    fun `userInfo with an empty host is allowed`() {
        val uri =
            Uri
                .Builder()
                .userInfo("u")
                .host("")
                .encodedPath("/p")
                .build()

        assertEquals("//u@/p", uri.uriString)
    }

    @Test
    fun `the guards never alter a value that already parsed`() {
        val original = parseOk("a/b:c/d")

        val rebuilt = original.newBuilder().build()

        assertEquals(original, rebuilt)
        assertEquals("a/b:c/d", rebuilt.uriString)
    }

    @Test
    fun `normalizing a colon-first path re-applies the guard so no phantom scheme returns`() {
        // The builder guards "a:b" to "./a:b"; normalized() strips the "./" dot-segment, which would
        // leave the stored path re-parsing as scheme "a" — the serializer must re-apply the guard.
        val u =
            Uri
                .Builder()
                .encodedPath("a:b")
                .build()
                .normalized()

        assertTrue(u.uriString.startsWith("./"), "the serialized normalized value keeps the ./ guard")
        assertNull(Uri.parse(u.uriString).getOrThrow().scheme, "no phantom scheme is reintroduced")
    }

    @Test
    fun `normalizing a double-slash path re-applies the guard so no phantom authority returns`() {
        // The builder guards "//x/y" to "/.//x/y"; normalized() strips the "/." dot-segment, which
        // would leave a "//"-leading stored path re-parsing with an authority — reguard on output.
        val u =
            Uri
                .Builder()
                .encodedPath("//x/y")
                .build()
                .normalized()

        assertTrue(u.uriString.startsWith("/."), "the serialized normalized value keeps the /. guard")
        assertNull(Uri.parse(u.uriString).getOrThrow().host, "no phantom authority is reintroduced")
    }

    @Test
    fun `a normally-parsed colon uri keeps its scheme and is left unguarded`() {
        // Parsing never produces the unsafe scheme-less colon-first state, so the guard never fires:
        // a parsed "a:b" has scheme "a", and an authority value serializes unchanged.
        assertEquals("a", parseOk("a:b").scheme)
        assertEquals("http://h/p", parseOk("http://h/p").uriString)
    }

    @Test
    fun `newBuilder then build round-trips a zoned ipv6 uri`() {
        val zoneOptions = ParseOptions.Builder().allowIpv6ZoneId(true).build()
        val original = Uri.parse("foo://[fe80::1%25eth0]/a", zoneOptions).getOrThrow()

        val rebuilt = original.newBuilder().build()

        assertEquals(original, rebuilt)
        assertEquals("foo://[fe80::1%25eth0]/a", rebuilt.uriString)
        assertEquals(Host.Ipv6(listOf(0xFE80, 0, 0, 0, 0, 0, 0, 1), zoneId = "eth0"), rebuilt.host)
    }
}
