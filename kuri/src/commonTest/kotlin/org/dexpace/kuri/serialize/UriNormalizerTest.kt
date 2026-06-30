/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.serialize

import org.dexpace.kuri.ParseProfile
import org.dexpace.kuri.error.getOrNull
import org.dexpace.kuri.parser.ParsedComponents
import org.dexpace.kuri.parser.UriParser
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * §11.1 `Uri` normalization tests covering the RFC 3986 §6.2 examples: scheme + reg-name case
 * folding with unreserved decoding, percent-triplet uppercasing, dot-segment removal, default-port
 * elision, and empty-authority-path synthesis. Each case asserts on the normalized serialization.
 */
internal class UriNormalizerTest {
    @Test
    fun `normalize lowercases scheme and host and decodes an unreserved triplet`() {
        assertEquals("http://www.example.com/~user", normalizedUri("HTTP://www.EXAMPLE.com/%7euser"))
    }

    @Test
    fun `normalize uppercases the hex digits of a reserved percent triplet`() {
        assertEquals("http://h/%2A", normalizedUri("http://h/%2a"))
    }

    @Test
    fun `normalize removes dot-segments from the path`() {
        assertEquals("http://h/b", normalizedUri("http://h/a/../b"))
    }

    @Test
    fun `normalize elides a port equal to the scheme default`() {
        assertEquals("http://h/", normalizedUri("http://h:80/"))
    }

    @Test
    fun `normalize renders an empty authority path as a single slash`() {
        assertEquals("http://h/", normalizedUri("http://h"))
    }

    @Test
    fun `normalize keeps a rootless path rootless`() {
        assertEquals("a/b/c", normalizedUri("a/b/c"))
    }

    @Test
    fun `normalize removes dot-segments without rooting a rootless path`() {
        assertEquals("a/b", normalizedUri("a/./b"))
    }

    @Test
    fun `normalize re-roots a rootless path when dot-segments pop the leading segment`() {
        // RFC 3986 §5.2.4 remove_dot_segments emits a leading "/" once "a" is popped by "..";
        // this is the literal algorithm outcome, not a rootless-preservation guarantee.
        assertEquals("/b", normalizedUri("a/../b"))
    }

    private fun normalizedUri(input: String): String {
        val parsed = requireNotNull(UriParser.parse(input).getOrNull()) { "expected a parseable Uri: $input" }
        val normalized: ParsedComponents = UriNormalizer.normalize(parsed)
        return Serializer.serialize(normalized, ParseProfile.URI)
    }
}
