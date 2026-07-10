/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.serialize

import org.dexpace.kuri.parser.ParsedComponents
import org.dexpace.kuri.parser.UriParser
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * §5.3 serializer tests: RFC 3986 `Uri` recomposition (generic round-trip, rootless vs rooted paths,
 * scheme-rootless opaque paths, and trailing-slash preservation across the rootless join).
 */
internal class UriSerializerTest {
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

    private fun parseUri(input: String): ParsedComponents =
        requireNotNull(UriParser.parse(input).getOrNull()) { "expected a parseable Uri: $input" }
}
