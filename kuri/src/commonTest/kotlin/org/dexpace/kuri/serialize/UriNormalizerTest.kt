/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.serialize

import org.dexpace.kuri.ParseProfile
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
    fun `normalize decodes a percent-encoded dot-segment before removing it`() {
        // In the Uri profile "%2E" decodes to "." under unreserved-octet decoding ([NORM-8],
        // RFC 3986 §6.2.2.2, since "." is an unreserved char), and that decode runs BEFORE
        // dot-segment removal ([NORM-9], §6.2.2.3). So "a/%2E%2E/b" first becomes "a/../b", which
        // remove_dot_segments then collapses to "/b". This is NOT the Url-profile deviation of
        // treating %2E as a dot inside remove_dot_segments; that does not apply here.
        assertEquals("http://h/b", normalizedUri("http://h/a/%2E%2E/b"))
        // Lowercase "%2e" is uppercased to "%2E" first ([NORM-6], §6.2.2.1), then decoded ([NORM-8]).
        assertEquals("http://h/b", normalizedUri("http://h/a/%2e%2e/b"))
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
