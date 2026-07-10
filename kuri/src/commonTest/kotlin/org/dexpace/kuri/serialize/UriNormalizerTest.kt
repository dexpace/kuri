/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.serialize

import org.dexpace.kuri.host.Host
import org.dexpace.kuri.parser.ComponentPath
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

    @Test
    fun `normalize leaves an Ipv4 host untouched`() {
        val host = Host.Ipv4(0x7F000001)
        assertEquals(host, normalizedHost(host))
    }

    @Test
    fun `normalize leaves an Ipv6 host untouched`() {
        val host = Host.Ipv6(listOf(0, 0, 0, 0, 0, 0, 0, 1))
        assertEquals(host, normalizedHost(host))
    }

    @Test
    fun `normalize leaves an IpFuture host untouched`() {
        val host = Host.IpFuture("v9.ABC")
        assertEquals(host, normalizedHost(host))
    }

    @Test
    fun `normalize leaves an Opaque host untouched and does not lowercase it`() {
        // Only reg-name letters are folded ([NORM-5]); an opaque host keeps its uppercase verbatim.
        val host = Host.Opaque("EXAMPLE")
        assertEquals(host, normalizedHost(host))
    }

    @Test
    fun `normalize leaves an Empty host untouched`() {
        assertEquals(Host.Empty, normalizedHost(Host.Empty))
    }

    @Test
    fun `normalize copies a reg-name percent triplet verbatim while lowercasing the letters`() {
        // The triplet "%2f" is copied verbatim by the host-char scanner ([NORM-5] host case),
        // then uppercased to "%2F" ([NORM-6]); "2F" is reserved so it is not decoded.
        assertEquals(Host.RegName("a%2Fb"), normalizedHost(Host.RegName("A%2fb")))
    }

    @Test
    fun `normalize lowercases a lone percent that is not a valid reg-name triplet`() {
        // A bare "%" not followed by two hex digits fails the triplet guard, so it is copied
        // through the single-char lowercasing branch untouched alongside the folded letter.
        assertEquals(Host.RegName("a%zz"), normalizedHost(Host.RegName("A%zz")))
    }

    @Test
    fun `normalize keeps a non-default explicit port`() {
        assertEquals("http://h:8080/", normalizedUri("http://h:8080/"))
    }

    @Test
    fun `normalize collapses a bare dot relative reference to the empty path`() {
        // "." has no authority and remove_dot_segments empties it, so the rendered path stays
        // empty rather than being rooted with a slash.
        assertEquals("", normalizedUri("."))
    }

    @Test
    fun `normalize decodes unreserved triplets in the query and fragment`() {
        // The query and fragment let-branches run normalizeText, uppercasing "%7e" to "%7E"
        // ([NORM-6]) and decoding it to the unreserved "~" ([NORM-8]) in both components.
        assertEquals("http://h/?~#~", normalizedUri("http://h/?%7e#%7e"))
    }

    @Test
    fun `normalize leaves an opaque path untouched`() {
        // §6.2.2.3 / [NORM-9]: an opaque path is returned verbatim and is never dot-collapsed.
        val opaque = ComponentPath.Opaque("a@b")
        val components = ParsedComponents(scheme = "mailto", path = opaque)
        assertEquals(opaque, UriNormalizer.normalize(components).path)
    }

    @Test
    fun `normalize elides an https port equal to the scheme default`() {
        assertEquals("https://h/", normalizedUri("https://h:443/"))
    }

    private fun normalizedHost(host: Host): Host? =
        UriNormalizer.normalize(ParsedComponents(scheme = "http", host = host)).host

    private fun normalizedUri(input: String): String {
        val parsed = requireNotNull(UriParser.parse(input).getOrNull()) { "expected a parseable Uri: $input" }
        val normalized: ParsedComponents = UriNormalizer.normalize(parsed)
        return UriSerializer.serialize(normalized)
    }
}
