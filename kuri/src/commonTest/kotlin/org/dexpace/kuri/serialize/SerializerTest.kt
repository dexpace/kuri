/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.serialize

import org.dexpace.kuri.host.Host
import org.dexpace.kuri.parser.ComponentPath
import org.dexpace.kuri.parser.ParsedComponents
import org.dexpace.kuri.parser.UriParser
import org.dexpace.kuri.parser.UrlParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * §11.2 serializer tests: WHATWG URL recomposition (round-trip, default-port elision, opaque and
 * `file` paths, the [NORM-18] `/.` guard, `excludeFragment`, present-vs-absent empty query/fragment)
 * and the RFC 3986 §5.3 `Uri` recomposition.
 */
internal class SerializerTest {
    @Test
    fun `serialize round-trips a parsed Url with userinfo port path query and fragment`() {
        val input = "https://u:p@h:8443/a/b?q#f"
        assertEquals(input, UrlSerializer.serialize(parseUrl(input)))
    }

    @Test
    fun `serialize elides the default port for a special scheme`() {
        assertEquals("https://h/", UrlSerializer.serialize(parseUrl("https://h:443/")))
    }

    @Test
    fun `serialize emits an opaque non-special path verbatim`() {
        assertEquals("mailto:a@b", UrlSerializer.serialize(parseUrl("mailto:a@b")))
    }

    @Test
    fun `serialize renders an empty-host file authority`() {
        assertEquals("file:///x", UrlSerializer.serialize(parseUrl("file:///x")))
    }

    @Test
    fun `serialize prepends the slash-dot guard for a no-authority path that opens with two slashes`() {
        val components = ParsedComponents(scheme = "web+demo", path = ComponentPath.Segments(listOf("", "x")))
        assertEquals("web+demo:/.//x", UrlSerializer.serialize(components))
    }

    @Test
    fun `serialize omits the fragment when excludeFragment is set`() {
        assertEquals("https://h/p?q", UrlSerializer.serialize(parseUrl("https://h/p?q#f"), true))
    }

    @Test
    fun `serialize distinguishes a present-empty query and fragment from their absence`() {
        val base =
            ParsedComponents(
                scheme = "https",
                host = Host.RegName("h"),
                path = ComponentPath.Segments(listOf("")),
            )
        assertEquals("https://h/", UrlSerializer.serialize(base))
        assertEquals("https://h/?", UrlSerializer.serialize(base.copy(query = "")))
        assertEquals("https://h/#", UrlSerializer.serialize(base.copy(fragment = "")))
    }

    @Test
    fun `serialize recomposes a generic URI per RFC 3986 section 5_3`() {
        val input = "foo://example.com/over/there?name=ferret#nose"
        assertEquals(input, UriSerializer.serialize(parseUri(input)))
    }

    @Test
    fun `serialize keeps a rootless relative reference path without a leading slash`() {
        assertEquals("a/b/c", UriSerializer.serialize(parseUri("a/b/c")))
    }

    @Test
    fun `serialize keeps a scheme-rootless mailto path without a leading slash`() {
        assertEquals("mailto:a@b", UriSerializer.serialize(parseUri("mailto:a@b")))
    }

    @Test
    fun `serialize keeps a scheme-rootless urn path without a leading slash`() {
        assertEquals("urn:example:x", UriSerializer.serialize(parseUri("urn:example:x")))
    }

    @Test
    fun `serialize keeps an absolute-path relative reference rooted`() {
        assertEquals("/a/b", UriSerializer.serialize(parseUri("/a/b")))
    }

    @Test
    fun `serialize keeps a trailing slash on a rootless path`() {
        // The trailing "" segment and the missing leading slash must both survive the rootless join.
        assertEquals("a/b/", UriSerializer.serialize(parseUri("a/b/")))
    }

    @Test
    fun `serialize rejects a Url profile value that carries no scheme`() {
        // The WHATWG serializer asserts the scheme is present; a Url always has one.
        val components = ParsedComponents(host = Host.RegName("h"), path = ComponentPath.Segments(listOf("")))
        assertFailsWith<IllegalArgumentException> { UrlSerializer.serialize(components) }
    }

    @Test
    fun `serializeAuthority rejects components with no host`() {
        // Authority serialization requires a non-null host; callers only reach it behind that guard.
        assertFailsWith<IllegalArgumentException> { serializeAuthority(ParsedComponents(host = null)) }
    }

    private fun parseUrl(input: String): ParsedComponents =
        requireNotNull(UrlParser.parse(input).getOrNull()) { "expected a parseable Url: $input" }

    private fun parseUri(input: String): ParsedComponents =
        requireNotNull(UriParser.parse(input).getOrNull()) { "expected a parseable Uri: $input" }
}
