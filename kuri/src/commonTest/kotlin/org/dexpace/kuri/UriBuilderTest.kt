/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri

import org.dexpace.kuri.error.UriSyntaxException
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
    fun `a leading double slash without an authority is rejected`() {
        // An authority-less absolute path may not begin with '//' (RFC 3986 §3.3): the leading '//'
        // would re-parse as an authority, so the builder rejects it rather than silently guarding it.
        val builder = Uri.Builder().encodedPath("//not-authority")

        assertNull(builder.buildOrNull())
        assertFailsWith<IllegalArgumentException> { builder.build() }
    }

    @Test
    fun `a leading double slash without an authority is rejected even with a scheme present`() {
        val builder =
            Uri
                .Builder()
                .scheme("foo")
                .encodedPath("//bar")

        assertNull(builder.buildOrNull())
        assertFailsWith<IllegalArgumentException> { builder.build() }
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
        // Normalizing "/.//x/y" strips the "." dot-segment, which would leave a "//"-leading stored
        // path re-parsing with an authority — the serializer must re-apply the "/." guard on output.
        val u = parseOk("/.//x/y").normalized()

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

    @Test
    fun `builds a zoned ipv6 host from scratch when the zone-id opt-in is set`() {
        val uri =
            Uri
                .Builder()
                .scheme("foo")
                .allowIpv6ZoneId(true)
                .host("[fe80::1%25eth0]")
                .build()

        assertEquals("foo://[fe80::1%25eth0]", uri.uriString)
        assertEquals(Host.Ipv6(listOf(0xFE80, 0, 0, 0, 0, 0, 0, 1), zoneId = "eth0"), uri.host)
    }

    @Test
    fun `rejects a from-scratch zoned host without the opt-in`() {
        assertFailsWith<UriSyntaxException> {
            Uri
                .Builder()
                .scheme("foo")
                .host("[fe80::1%25eth0]")
                .build()
        }
    }

    @Test
    fun `addPathSegment percent-encodes a slash so it stays within one segment`() {
        val uri =
            Uri
                .Builder()
                .scheme("foo")
                .host("h")
                .addPathSegment("a b")
                .addPathSegment("c/d")
                .build()

        assertEquals("foo://h/a%20b/c%2Fd", uri.uriString)
        assertEquals(listOf("a b", "c/d"), uri.pathSegments)
    }

    @Test
    fun `addEncodedPathSegment appends the segment verbatim`() {
        val uri =
            Uri
                .Builder()
                .scheme("foo")
                .host("h")
                .addEncodedPathSegment("a")
                .addEncodedPathSegment("b%20c")
                .build()

        assertEquals("foo://h/a/b%20c", uri.uriString)
        assertEquals(listOf("a", "b c"), uri.pathSegments)
    }

    @Test
    fun `path is decoded while encodedPath is verbatim`() {
        val uri = parseOk("http://h/a/b%20c")

        assertEquals("/a/b c", uri.path)
        assertEquals("/a/b%20c", uri.encodedPath)
    }

    @Test
    fun `two segments under an authority join with a single separator`() {
        val uri =
            Uri
                .Builder()
                .scheme("http")
                .host("h")
                .addPathSegment("a")
                .addPathSegment("b")
                .build()

        assertEquals("http://h/a/b", uri.uriString)
        assertEquals(listOf("a", "b"), uri.pathSegments)
    }

    @Test
    fun `builds a rootless urn path segment-wise`() {
        val uri =
            Uri
                .Builder()
                .scheme("urn")
                .addPathSegment("isbn:0451450523")
                .build()

        assertEquals("urn:isbn%3A0451450523", uri.uriString)
        assertEquals(listOf("isbn:0451450523"), uri.pathSegments)
    }

    @Test
    fun `builds a rootless urn path from an encoded segment`() {
        val uri =
            Uri
                .Builder()
                .scheme("urn")
                .addEncodedPathSegment("isbn:0451450523")
                .build()

        assertEquals("urn:isbn:0451450523", uri.uriString)
    }

    @Test
    fun `a segment fills the open slot left by a trailing slash`() {
        val rebuilt = parseOk("http://h/").newBuilder().addPathSegment("x").build()

        assertEquals("http://h/x", rebuilt.uriString)
    }

    @Test
    fun `an empty then non-empty segment collapses into the open slot`() {
        val uri =
            Uri
                .Builder()
                .scheme("http")
                .host("h")
                .addPathSegment("")
                .addPathSegment("x")
                .build()

        assertEquals("http://h/x", uri.uriString)
    }

    @Test
    fun `an empty segment materializes as a trailing slash`() {
        val uri =
            Uri
                .Builder()
                .scheme("http")
                .host("h")
                .addPathSegment("a")
                .addPathSegment("")
                .build()

        assertEquals("http://h/a/", uri.uriString)
        assertEquals(listOf("a", ""), uri.pathSegments)
    }

    @Test
    fun `a lone empty segment with no authority contributes nothing`() {
        val uri =
            Uri
                .Builder()
                .scheme("urn")
                .addPathSegment("")
                .build()

        assertEquals("urn:", uri.uriString)
    }

    @Test
    fun `segment rooting does not depend on setter order`() {
        val segmentThenHost =
            Uri
                .Builder()
                .addPathSegment("a")
                .host("h")
                .build()
        val hostThenSegment =
            Uri
                .Builder()
                .host("h")
                .addPathSegment("a")
                .build()

        assertEquals("//h/a", segmentThenHost.uriString)
        assertEquals("//h/a", hostThenSegment.uriString)
    }

    @Test
    fun `clearing the host before build yields the rootless form`() {
        val uri =
            Uri
                .Builder()
                .host("h")
                .addPathSegment("a")
                .host(null)
                .scheme("urn")
                .build()

        assertEquals("urn:a", uri.uriString)
    }

    @Test
    fun `a scheme-less colon-first pushed segment keeps the dot guard`() {
        val uri = Uri.Builder().addEncodedPathSegment("a:b").build()

        assertNull(uri.scheme)
        assertEquals("./a:b", uri.uriString)
    }

    @Test
    fun `addEncodedPathSegment rejects a raw delimiter that would re-split the value`() {
        for (bad in listOf("a/b", "a?b", "a#b", "a\\b")) {
            assertFailsWith<IllegalArgumentException>("expected $bad to be rejected") {
                Uri.Builder().addEncodedPathSegment(bad)
            }
        }
    }

    @Test
    fun `addPathSegment carries a delimiter as data within one segment`() {
        val uri =
            Uri
                .Builder()
                .scheme("http")
                .host("h")
                .addPathSegment("a#b")
                .build()

        assertEquals("http://h/a%23b", uri.uriString)
        assertEquals(listOf("a#b"), uri.pathSegments)
    }

    @Test
    fun `addEncodedPathSegment preserves a dot segment verbatim in the uri profile`() {
        val uri =
            Uri
                .Builder()
                .scheme("http")
                .host("h")
                .addEncodedPathSegment("a")
                .addEncodedPathSegment("..")
                .build()

        assertEquals("http://h/a/..", uri.uriString)
    }

    @Test
    fun `an interior empty segment is expressible through encodedPath`() {
        val uri =
            Uri
                .Builder()
                .scheme("http")
                .host("h")
                .encodedPath("/a//b")
                .build()

        assertEquals("http://h/a//b", uri.uriString)
        assertEquals(uri, uri.newBuilder().build())
    }

    @Test
    fun `interior empty segments survive a setPathSegment edit of another segment`() {
        val uri =
            Uri
                .Builder()
                .scheme("http")
                .host("h")
                .encodedPath("/a//b/c")
                .setPathSegment(0, "z")
                .build()

        assertEquals("http://h/z//b/c", uri.uriString)
        assertEquals(listOf("z", "", "b", "c"), uri.pathSegments)
    }

    @Test
    fun `a trailing empty segment survives a removePathSegment edit`() {
        val uri =
            Uri
                .Builder()
                .scheme("http")
                .host("h")
                .addPathSegment("a")
                .addPathSegment("b")
                .addPathSegment("")
                .removePathSegment(0)
                .build()

        assertEquals("http://h/b/", uri.uriString)
        assertEquals(listOf("b", ""), uri.pathSegments)
    }

    @Test
    fun `addPathSegments interior and trailing empties survive a later setPathSegment edit`() {
        val uri =
            Uri
                .Builder()
                .scheme("http")
                .host("h")
                .addPathSegments("a//b/")
                .setPathSegment(2, "x")
                .build()

        assertEquals("http://h/a//x/", uri.uriString)
        assertEquals(listOf("a", "", "x", ""), uri.pathSegments)
    }

    @Test
    fun `addPathSegments after a segment append preserves interior empties`() {
        val uri =
            Uri
                .Builder()
                .scheme("http")
                .host("h")
                .addPathSegment("a")
                .addPathSegments("b//c")
                .build()

        assertEquals("http://h/a/b//c", uri.uriString)
        assertEquals(listOf("a", "b", "", "c"), uri.pathSegments)
    }

    @Test
    fun `a verbatim query is not canonically re-encoded`() {
        val uri =
            Uri
                .Builder()
                .scheme("http")
                .host("h")
                .encodedPath("/p")
                .query("a=%41")
                .build()

        assertEquals("a=%41", uri.query)
        assertEquals("http://h/p?a=%41", uri.uriString)
    }

    @Test
    fun `a chain of query parameter edits stays correct`() {
        val uri =
            Uri
                .Builder()
                .scheme("http")
                .host("h")
                .encodedPath("/p")
                .query("a=1&b=2&a=3")
                .addQueryParameter("c", "4")
                .setQueryParameter("a", "9")
                .removeAllQueryParameters("b")
                .build()

        assertEquals("a=9&c=4", uri.query)
        assertEquals("http://h/p?a=9&c=4", uri.uriString)
    }

    @Test
    fun `port rejects a negative value`() {
        // The Uri profile applies no upper cap but still requires a non-negative port.
        assertFailsWith<IllegalArgumentException> { Uri.Builder().port(-1) }
    }

    @Test
    fun `a null scheme is accepted and yields a relative reference`() {
        // scheme(null) takes the null short-circuit arm of the scheme validation and clears any scheme.
        val uri =
            Uri
                .Builder()
                .scheme(null)
                .encodedPath("/p")
                .build()

        assertNull(uri.scheme)
        assertEquals("/p", uri.encodedPath)
    }
}
