/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.serialize

import org.dexpace.kuri.ParseProfile
import org.dexpace.kuri.error.getOrNull
import org.dexpace.kuri.host.Host
import org.dexpace.kuri.parser.ParsedComponents
import org.dexpace.kuri.parser.UriParser
import org.dexpace.kuri.parser.UrlParser
import org.dexpace.kuri.parser.UrlPath
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * §11.2 serializer tests: WHATWG URL recomposition (round-trip, default-port elision, opaque and
 * `file` paths, the [NORM-18] `/.` guard, `excludeFragment`, present-vs-absent empty query/fragment)
 * and the RFC 3986 §5.3 `Uri` recomposition.
 */
internal class SerializerTest {
    @Test
    fun `serialize round-trips a parsed Url with userinfo port path query and fragment`() {
        val input = "https://u:p@h:8443/a/b?q#f"
        assertEquals(input, Serializer.serialize(parseUrl(input), ParseProfile.URL))
    }

    @Test
    fun `serialize elides the default port for a special scheme`() {
        assertEquals("https://h/", Serializer.serialize(parseUrl("https://h:443/"), ParseProfile.URL))
    }

    @Test
    fun `serialize emits an opaque non-special path verbatim`() {
        assertEquals("mailto:a@b", Serializer.serialize(parseUrl("mailto:a@b"), ParseProfile.URL))
    }

    @Test
    fun `serialize renders an empty-host file authority`() {
        assertEquals("file:///x", Serializer.serialize(parseUrl("file:///x"), ParseProfile.URL))
    }

    @Test
    fun `serialize prepends the slash-dot guard for a no-authority path that opens with two slashes`() {
        val components = ParsedComponents(scheme = "web+demo", path = UrlPath.Segments(listOf("", "x")))
        assertEquals("web+demo:/.//x", Serializer.serialize(components, ParseProfile.URL))
    }

    @Test
    fun `serialize omits the fragment when excludeFragment is set`() {
        assertEquals("https://h/p?q", Serializer.serialize(parseUrl("https://h/p?q#f"), ParseProfile.URL, true))
    }

    @Test
    fun `serialize distinguishes a present-empty query and fragment from their absence`() {
        val base = ParsedComponents(scheme = "https", host = Host.RegName("h"), path = UrlPath.Segments(listOf("")))
        assertEquals("https://h/", Serializer.serialize(base, ParseProfile.URL))
        assertEquals("https://h/?", Serializer.serialize(base.copy(query = ""), ParseProfile.URL))
        assertEquals("https://h/#", Serializer.serialize(base.copy(fragment = ""), ParseProfile.URL))
    }

    @Test
    fun `serialize recomposes a generic URI per RFC 3986 section 5_3`() {
        val input = "foo://example.com/over/there?name=ferret#nose"
        assertEquals(input, Serializer.serialize(parseUri(input), ParseProfile.URI))
    }

    private fun parseUrl(input: String): ParsedComponents =
        requireNotNull(UrlParser.parse(input).getOrNull()) { "expected a parseable Url: $input" }

    private fun parseUri(input: String): ParsedComponents =
        requireNotNull(UriParser.parse(input).getOrNull()) { "expected a parseable Uri: $input" }
}
