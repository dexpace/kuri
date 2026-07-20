/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri

import org.dexpace.kuri.error.HostError
import org.dexpace.kuri.error.ParseResult
import org.dexpace.kuri.error.UriParseError
import org.dexpace.kuri.error.UriSyntaxException
import org.dexpace.kuri.host.Host
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class UrlTest {
    private fun parseOk(input: String): Url = Url.parse(input).getOrNull() ?: fail("expected $input to parse")

    @Test
    fun `exposes every component of a full url`() {
        val url = parseOk("https://user:pass@example.com:8443/path/to/page?q=1&lang=en#section")

        assertEquals("https", url.scheme)
        assertEquals("user", url.username)
        assertEquals("pass", url.password)
        assertEquals(Host.RegName("example.com"), url.host)
        assertEquals("example.com", url.hostName)
        assertEquals(8443, url.port)
        assertEquals(8443, url.effectivePort)
        assertEquals(listOf("path", "to", "page"), url.pathSegments)
        assertEquals("/path/to/page", url.encodedPath)
        assertEquals("q=1&lang=en", url.query)
        assertEquals("section", url.fragment)
        assertEquals("section", url.encodedFragment)
        assertEquals("user:pass@example.com:8443", url.authority)
        assertEquals("https://example.com:8443", url.origin)
    }

    @Test
    fun `href and toString are the canonical serialization`() {
        val href = "https://user:pass@example.com:8443/path/to/page?q=1&lang=en#section"
        val url = parseOk(href)

        assertEquals(href, url.href)
        assertEquals(href, url.toString())
    }

    @Test
    fun `queryParameters expose decoded pairs`() {
        val url = parseOk("https://h/?q=hello%20world&lang=en&lang=fr")

        assertEquals("hello world", url.queryParameters.get("q"))
        assertEquals(listOf<String?>("en", "fr"), url.queryParameters.getAll("lang"))
        assertEquals(setOf("q", "lang"), url.queryParameters.names())
    }

    @Test
    fun `decodedFragment mirrors fragment when there is nothing to decode`() {
        val url = parseOk("https://h/p#plain")

        assertEquals("plain", url.fragment)
        assertEquals("plain", url.decodedFragment)
    }

    @Test
    fun `decodedFragment percent-decodes a triplet`() {
        val url = parseOk("https://h/p#a%20b")

        assertEquals("a%20b", url.fragment)
        assertEquals("a b", url.decodedFragment)
    }

    @Test
    fun `decodedFragment is null when there is no fragment`() {
        val url = parseOk("https://h/p")

        assertNull(url.fragment)
        assertNull(url.decodedFragment)
    }

    @Test
    fun `decodedFragment decodes an already-escaped literal percent sign back to the original text`() {
        val url = parseOk("https://h/p#50%25off")

        assertEquals("50%25off", url.fragment)
        assertEquals("50%off", url.decodedFragment)
    }

    @Test
    fun `decodedFragment is empty when the fragment is present but empty`() {
        val url = parseOk("https://h/p#")

        assertEquals("", url.fragment)
        assertEquals("", url.decodedFragment)
    }

    @Test
    fun `decodedUsername mirrors username when there is nothing to decode`() {
        val url = parseOk("https://bob@h/p")

        assertEquals("bob", url.username)
        assertEquals("bob", url.decodedUsername)
    }

    @Test
    fun `decodedUsername percent-decodes a triplet`() {
        val url = parseOk("https://a%20b@h/p")

        assertEquals("a%20b", url.username)
        assertEquals("a b", url.decodedUsername)
    }

    @Test
    fun `decodedUsername decodes an already-escaped literal percent sign back to the original text`() {
        val url = parseOk("https://50%25off@h/p")

        assertEquals("50%25off", url.username)
        assertEquals("50%off", url.decodedUsername)
    }

    @Test
    fun `decodedPassword mirrors password when there is nothing to decode`() {
        val url = parseOk("https://bob:secret@h/p")

        assertEquals("secret", url.password)
        assertEquals("secret", url.decodedPassword)
    }

    @Test
    fun `decodedPassword percent-decodes a triplet`() {
        val url = parseOk("https://bob:a%20b@h/p")

        assertEquals("a%20b", url.password)
        assertEquals("a b", url.decodedPassword)
    }

    @Test
    fun `decodedPassword decodes an already-escaped literal percent sign back to the original text`() {
        val url = parseOk("https://bob:50%25off@h/p")

        assertEquals("50%25off", url.password)
        assertEquals("50%off", url.decodedPassword)
    }

    @Test
    fun `decodedUsername and decodedPassword are empty when there is no userinfo`() {
        val url = parseOk("https://h/p")

        assertEquals("", url.username)
        assertEquals("", url.decodedUsername)
        assertEquals("", url.password)
        assertEquals("", url.decodedPassword)
    }

    @Test
    fun `parseOrNull returns null on failure`() {
        assertNull(Url.parseOrNull("http://["))
    }

    @Test
    fun `canParse reflects parse success`() {
        assertTrue(Url.canParse("https://example.com/"))
        assertFalse(Url.canParse("http://["))
    }

    @Test
    fun `parseOrNull with a base resolves a relative reference`() {
        val base = parseOk("http://a/b/c/d")

        assertEquals("http://a/b/g", Url.parseOrNull("../g", base)?.href)
    }

    @Test
    fun `parseOrNull with a base returns null when the reference does not resolve`() {
        val base = parseOk("http://a/b/c/d")

        assertNull(Url.parseOrNull("http://[", base))
    }

    @Test
    fun `canParse with a base reflects whether the reference resolves`() {
        val base = parseOk("http://a/b/c/d")

        assertTrue(Url.canParse("../g", base))
        assertFalse(Url.canParse("http://[", base))
    }

    @Test
    fun `parseOrThrow returns the value on success`() {
        assertEquals("https://example.com/", Url.parseOrThrow("https://example.com/").href)
    }

    @Test
    fun `parseOrThrow resolves a relative reference against a base`() {
        val base = parseOk("http://a/b/c/d")

        assertEquals("http://a/b/g", Url.parseOrThrow("../g", base).href)
    }

    @Test
    fun `parseOrThrow throws UriSyntaxException carrying the structured error on a malformed input`() {
        val exception = assertFailsWith<UriSyntaxException> { Url.parseOrThrow("http://[") }

        assertTrue(exception.message?.isNotBlank() == true, "the exception carries a human-readable message")
        assertEquals(exception.error.message, exception.message)
    }

    @Test
    fun `default port is elided from the canonical href`() {
        assertEquals("https://example.com/", parseOk("https://example.com:443/").href)
        assertEquals("http://example.com/", parseOk("http://example.com:80/").href)
        assertNull(parseOk("https://example.com:443/").port)
    }

    @Test
    fun `effectivePort recovers the scheme default when the port is elided`() {
        val url = parseOk("https://example.com/")

        assertNull(url.port)
        assertEquals(443, url.effectivePort)
    }

    @Test
    fun `effectivePort is null rather than a sentinel when the scheme has no default port`() {
        // Neither a non-special scheme (foo) nor file (special, but portless) registers a default
        // port ([SCH-18]), so effectivePort must report "no port" as null rather than the java.net -1
        // sentinel SPEC.md's MODEL-23 forbids.
        assertNull(parseOk("foo://host/").effectivePort)
        assertNull(parseOk("file://host/").effectivePort)
    }

    @Test
    fun `effectivePort returns the literal zero port rather than falling back to the scheme default`() {
        // 0 is a real, distinct port value — SPEC.md's MODEL-23 forbids treating it as a sentinel for
        // "unspecified", so effectivePort must not fall through to http's default of 80.
        assertEquals(0, parseOk("http://host:0/").effectivePort)
    }

    @Test
    fun `origin omits the port when it equals the scheme default`() {
        assertEquals("https://example.com", parseOk("https://example.com:443/").origin)
    }

    @Test
    fun `origin is opaque for a non-special scheme`() {
        assertEquals("null", parseOk("git://github.com/foo/bar.git").origin)
    }

    @Test
    fun `origin of a blob url adopts the inner https origin`() {
        assertEquals("https://example.com", parseOk("blob:https://example.com:443/").origin)
        assertEquals("http://example.org:88", parseOk("blob:http://example.org:88/").origin)
    }

    @Test
    fun `origin is opaque for a file url`() {
        assertEquals("null", parseOk("file:///etc/hosts").origin)
    }

    @Test
    fun `origin is opaque when a blob inner scheme is not unwrappable`() {
        assertEquals("null", parseOk("blob:ftp://host/path").origin)
    }

    @Test
    fun `origin is opaque when a blob path is not a url`() {
        assertEquals("null", parseOk("blob:d3958f5c-0777-0845-9dcf-2cb28783acaf").origin)
    }

    @Test
    fun `equals and hashCode are canonical-href based and case-folding`() {
        val canonical = parseOk("http://h/")
        val mixedCase = parseOk("HTTP://H/")

        assertEquals(canonical, mixedCase)
        assertEquals(canonical.hashCode(), mixedCase.hashCode())
    }

    @Test
    fun `is usable as a hash set element`() {
        val set = hashSetOf(parseOk("HTTP://H/"))

        assertTrue(parseOk("http://h/") in set)
    }

    @Test
    fun `resolve applies dot-segment reference resolution against a base`() {
        val base = parseOk("http://a/b/c/d")

        val resolved = base.resolve("../g").getOrNull() ?: fail("resolve failed")
        assertEquals("http://a/b/g", resolved.href)
    }

    @Test
    fun `newBuilder then build reproduces an equal value`() {
        val original = parseOk("https://user:pass@example.com:8443/path?q=1#frag")

        val rebuilt = original.newBuilder().build()
        assertEquals(original, rebuilt)
        assertEquals(original.href, rebuilt.href)
    }

    @Test
    fun `static parse factory is reachable`() {
        val result = Url.parse("https://example.com/")
        assertEquals("https://example.com/", result.getOrNull()?.href)
    }

    // --- forbidden host code point offsets rebased to full input ([HOST-37]) ---------

    @Test
    fun `parse rebases an opaque-host forbidden code point to full-input coordinates`() {
        // Non-special scheme -> opaque host "h|ost" (no percent-decode before the forbidden scan);
        // OpaqueHost reports '|' at host-relative index 1, which must be rebased by the host start
        // (6, right after "foo://") to the full-input offset 7.
        val err = assertIs<ParseResult.Err>(Url.parse("foo://h|ost"))
        val cause = assertIs<UriParseError.ForbiddenHostCodePoint>(err.error)
        assertEquals('|'.code, cause.codePoint)
        assertEquals(7, cause.at)
    }

    @Test
    fun `parse rebases a special-domain forbidden control character to full-input coordinates`() {
        // Special scheme -> domain pipeline; U+0001 is forbidden and the host starts at 7 (right
        // after "http://"), so its host-relative index 1 must rebase to full-input offset 8.
        val err = assertIs<ParseResult.Err>(Url.parse("http://host/p"))
        val cause = assertIs<UriParseError.ForbiddenHostCodePoint>(err.error)
        assertEquals(1, cause.codePoint)
        assertEquals(8, cause.at)
    }

    @Test
    fun `parse rebases a special-domain forbidden code point past a Punycode-expanded label`() {
        // "ä" Punycode-expands, so '^' sits at a different index in the transformed ASCII domain
        // than in the original host; tracing back through IDNA gives host-relative index 3, which
        // rebases by the host start (7) to full-input offset 10.
        val err = assertIs<ParseResult.Err>(Url.parse("http://ä.a^b"))
        val cause = assertIs<UriParseError.ForbiddenHostCodePoint>(err.error)
        assertEquals('^'.code, cause.codePoint)
        assertEquals(10, cause.at)
    }

    @Test
    fun `parse rebases a forbidden code point through percent-decoding and IDNA`() {
        // The host "%C3%A4.a^b" is percent-decoded to "ä.a^b" before IDNA runs, so a naive
        // length-based rescale of the post-decode index (3) would land on the wrong raw offset
        // (a linear guess gives 10). The '^' must be tracked back through percent-decoding to its
        // real host-relative index 8, then rebased by the host start (7) to full-input offset 15.
        val err = assertIs<ParseResult.Err>(Url.parse("http://%C3%A4.a^b"))
        val cause = assertIs<UriParseError.ForbiddenHostCodePoint>(err.error)
        assertEquals('^'.code, cause.codePoint)
        assertEquals(15, cause.at)
    }

    // --- RFC 6874 zone identifiers ([HOST-17]) ---------------------------------------

    @Test
    fun `the Url profile rejects an IPv6 zone id because WHATWG has no zone-id production`() {
        // The Url profile is the WHATWG profile and accepts no ParseOptions: a `%` in an IPv6
        // literal is always rejected, with no opt-in to relax it ([HOST-17]).
        val err = assertIs<ParseResult.Err>(Url.parse("http://[fe80::1%25eth0]/"))
        val cause = assertIs<UriParseError.InvalidHost>(err.error)
        assertEquals(HostError.ZoneIdRejected, cause.reason)
    }

    @Test
    fun `authority is null for a hostless url`() {
        // A non-special scheme yields an opaque, authority-less URL, so the authority projection is null
        // rather than a serialized string.
        assertNull(parseOk("mailto:a@b.example").authority)
    }

    @Test
    fun `equals is false against a non-url value`() {
        val notAUrl: Any = "http://h/"

        assertFalse(parseOk("http://h/").equals(notAUrl))
    }

    @Test
    fun `an interior empty path segment is preserved rather than collapsed`() {
        // WHATWG keeps consecutive slashes as an empty segment; kuri does not fold them out.
        val url = parseOk("http://h/a//b")

        assertEquals(listOf("a", "", "b"), url.pathSegments)
        assertEquals("/a//b", url.encodedPath)
    }

    @Test
    fun `a trailing empty path segment from a trailing slash is preserved`() {
        val url = parseOk("http://h/a/b/")

        assertEquals(listOf("a", "b", ""), url.pathSegments)
        assertEquals("/a/b/", url.encodedPath)
    }

    @Test
    fun `the trailing dot of a reg-name host is preserved`() {
        // A non-IPv4 domain keeps its FQDN trailing dot; only a dotted-quad IPv4 host strips it.
        val url = parseOk("http://google.com./")

        assertEquals(Host.RegName("google.com."), url.host)
        assertEquals("google.com.", url.hostName)
    }
}
