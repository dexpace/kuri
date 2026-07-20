/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri

import org.dexpace.kuri.error.HostError
import org.dexpace.kuri.error.ParseResult
import org.dexpace.kuri.error.ResourceLimit
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

class UriTest {
    private fun parseOk(input: String): Uri = Uri.parse(input).getOrNull() ?: fail("expected $input to parse")

    private val zoneOptions: ParseOptions = ParseOptions.Builder().allowIpv6ZoneId(true).build()

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
    fun `decodedFragment mirrors fragment when there is nothing to decode`() {
        val uri = parseOk("foo://h/p#plain")

        assertEquals("plain", uri.fragment)
        assertEquals("plain", uri.decodedFragment)
    }

    @Test
    fun `decodedFragment percent-decodes a triplet`() {
        val uri = parseOk("foo://h/p#a%20b")

        assertEquals("a%20b", uri.fragment)
        assertEquals("a b", uri.decodedFragment)
    }

    @Test
    fun `decodedFragment is null when there is no fragment`() {
        val uri = parseOk("foo://h/p")

        assertNull(uri.fragment)
        assertNull(uri.decodedFragment)
    }

    @Test
    fun `decodedFragment decodes an already-escaped literal percent sign back to the original text`() {
        val uri = parseOk("foo://h/p#50%25off")

        assertEquals("50%25off", uri.fragment)
        assertEquals("50%off", uri.decodedFragment)
    }

    @Test
    fun `decodedFragment is empty when the fragment is present but empty`() {
        val uri = parseOk("foo://h/p#")

        assertEquals("", uri.fragment)
        assertEquals("", uri.decodedFragment)
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
        assertEquals("a/b", uri.path)
        assertEquals("a/b", uri.uriString)
        assertNull(uri.userInfo)
        assertNull(uri.authority)
    }

    @Test
    fun `a rootless relative reference round-trips without a synthesized leading slash`() {
        val uri = parseOk("a/b/c")

        assertEquals("a/b/c", uri.toString())
    }

    @Test
    fun `a scheme-rootless mailto preserves its rootless path`() {
        val uri = parseOk("mailto:a@b")

        assertEquals("a@b", uri.path)
        assertEquals("mailto:a@b", uri.uriString)
    }

    @Test
    fun `a scheme-rootless urn preserves its rootless path`() {
        val uri = parseOk("urn:example:animal:ferret")

        assertEquals("urn:example:animal:ferret", uri.uriString)
    }

    @Test
    fun `a single-segment rootless path round-trips without a leading slash`() {
        // Guards the join boundary: a one-element segment list must not gain a synthesized "/".
        val uri = parseOk("a")

        assertEquals(listOf("a"), uri.pathSegments)
        assertEquals("a", uri.path)
        assertEquals("a", uri.uriString)
    }

    @Test
    fun `a rootless path with a colon in a later segment is not a scheme`() {
        // The first ':' follows a '/', so it is path data, not a scheme delimiter; the rootless
        // round-trip must not re-introduce a leading slash that would change how it re-parses.
        val uri = parseOk("a/b:c/d")

        assertNull(uri.scheme)
        assertEquals("a/b:c/d", uri.uriString)
    }

    @Test
    fun `the explicit port is preserved without default-port elision`() {
        val uri = parseOk("http://h:80/")

        assertEquals(80, uri.port)
        assertEquals("http://h:80/", uri.uriString)
    }

    @Test
    fun `a leading zero in the port is not preserved on reserialization`() {
        val uri = parseOk("http://h:007/")

        assertEquals(7, uri.port)
        assertEquals("http://h:7/", uri.uriString)
    }

    @Test
    fun `dot segments in the path are preserved verbatim`() {
        val uri = parseOk("http://h/a/../b")

        assertEquals("/a/../b", uri.path)
        assertEquals(listOf("a", "..", "b"), uri.pathSegments)
    }

    @Test
    fun `a raw space in the path is preserved verbatim`() {
        val uri = parseOk("http://h/a b")

        assertEquals("/a b", uri.path)
        assertEquals("http://h/a b", uri.uriString)
    }

    @Test
    fun `a raw angle bracket in the path is preserved verbatim`() {
        val uri = parseOk("http://h/a<b")

        assertEquals("/a<b", uri.path)
        assertEquals("http://h/a<b", uri.uriString)
    }

    @Test
    fun `raw curly braces in the path are preserved verbatim`() {
        val uri = parseOk("http://h/a{b}")

        assertEquals("/a{b}", uri.path)
        assertEquals("http://h/a{b}", uri.uriString)
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
    fun `normalized round-trips a path within a raised inputLength but beyond the default expandedLength`() {
        // A path admitted under a raised inputLength must not be re-checked against the unrelated,
        // smaller default expandedLength on normalize; normalize works on the already-bounded parsed
        // path and its value round-trips (no dot-segments, already-lowercase scheme/host).
        val options = ParseOptions.Builder().inputLength(200_000).build()
        val parsed = Uri.parse("http://h/" + "a".repeat(100_000), options)

        val uri = parsed.getOrNull() ?: fail("expected the raised-inputLength parse to succeed")
        val normalized = uri.normalized()

        assertEquals(uri.uriString, normalized.uriString)
    }

    @Test
    fun `a value parsed under raised limits round-trips through newBuilder and resolve and relativize`() {
        // ParseOptions is stored on the Uri, so every follow-on operation reuses the raised limits
        // rather than silently reverting to the default 64 KiB and rejecting the value it produced.
        val options =
            ParseOptions
                .Builder()
                .inputLength(200_000)
                .expandedLength(200_000)
                .build()
        val base = Uri.parse("http://h/" + "a".repeat(100_000) + "/x", options).getOrThrow()

        // newBuilder rebuild reproduces the value -- would fail under the default inputLength.
        assertEquals(base, base.newBuilder().build())
        // re-parsing the serialization under the stored options round-trips.
        assertEquals(base, Uri.parse(base.uriString, options).getOrThrow())

        // resolve honours the stored expandedLength when merging onto the long base directory.
        val target = base.resolve("sub").getOrNull() ?: fail("resolve should honour the raised limits")
        assertTrue(target.uriString.endsWith("/sub"))

        // relativize is not spuriously null: it resolves the candidate back under the stored limits.
        val relative = base.relativize(target) ?: fail("relativize should find a relative form")
        assertEquals(target, base.resolve(relative.uriString).getOrThrow())
    }

    @Test
    fun `resolve returns an error instead of throwing for an empty reference against an over-long base path`() {
        // Public-API mirror of the Resolver empty-path guard: a fragment-only reference keeps the
        // base path, which under a lowered expandedLength exceeds the bound. Uri.resolve must report
        // ParseResult.Err(InputTooLong), never let an IllegalStateException escape.
        val opts = ParseOptions.Builder().expandedLength(1_000).build()
        val base = Uri.parse("https://example.com/" + "a".repeat(2_000), opts).getOrThrow()

        val result = base.resolve("#section", opts)

        val err = assertIs<ParseResult.Err>(result)
        assertIs<UriParseError.InputTooLong>(err.error)
    }

    @Test
    fun `resolve applies the pathSegments limit to the resolved result`() {
        // Each input is within the limit on its own -- the base path is 3 segments and the reference
        // is 2 -- but the merged, resolved path is 4 segments, so the limit must be enforced on the
        // resolve RESULT, not only on the parsed inputs.
        val options = ParseOptions.Builder().pathSegments(3).build()
        val base = Uri.parse("http://h/a/b/c", options).getOrThrow()

        val result = base.resolve("d/e")

        val err = assertIs<ParseResult.Err>(result)
        val limit = assertIs<UriParseError.LimitExceeded>(err.error)
        assertEquals(ResourceLimit.PathSegments, limit.limit)
        assertEquals(3, limit.max)
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
    fun `an empty-but-present userinfo round-trips the at-sign and compares unequal to no userinfo`() {
        // Regression for #104: username/password used to collapse "absent" and "present-but-empty"
        // into the same "" value before serialization, so the "@" was silently dropped on re-serialize
        // and the two forms compared equal — violating the PRESERVE-profile equality contract.
        val withEmptyUserinfo = parseOk("http://@h/")
        val withoutUserinfo = parseOk("http://h/")

        assertEquals("http://@h/", withEmptyUserinfo.uriString)
        assertEquals("http://h/", withoutUserinfo.uriString)
        assertEquals("", withEmptyUserinfo.userInfo)
        assertNull(withoutUserinfo.userInfo)
        assertFalse(withEmptyUserinfo == withoutUserinfo)
    }

    @Test
    fun `an empty-but-present password round-trips the trailing colon and compares unequal to no password`() {
        // Regression for #104: the second reported repro case — a trailing ':' with an empty
        // password used to collapse to the same value as no password at all.
        val withEmptyPassword = parseOk("http://u:@h/")
        val withoutPassword = parseOk("http://u@h/")

        assertEquals("http://u:@h/", withEmptyPassword.uriString)
        assertEquals("http://u@h/", withoutPassword.uriString)
        assertEquals("u:", withEmptyPassword.userInfo)
        assertEquals("u", withoutPassword.userInfo)
        assertFalse(withEmptyPassword == withoutPassword)
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

    @Test
    fun `parseOrThrow returns the value on success`() {
        val uri = Uri.parseOrThrow("foo://h/p")

        assertEquals("foo://h/p", uri.uriString)
    }

    @Test
    fun `parseOrThrow throws UriSyntaxException carrying the structured error on a malformed input`() {
        val exception = assertFailsWith<UriSyntaxException> { Uri.parseOrThrow("http://h/p?a=%2") }

        assertIs<UriParseError.InvalidPercentEncoding>(exception.error)
        assertTrue(exception.message?.isNotBlank() == true, "the exception carries a human-readable message")
        assertEquals(exception.error.message, exception.message)
    }

    // --- RFC 6874 zone identifiers ([HOST-18]) ---------------------------------------

    @Test
    fun `opted-in parse accepts an ipv6 zone id`() {
        val uri = Uri.parse("//[fe80::1%25eth0]", zoneOptions).getOrNull() ?: fail("expected zoned parse")

        assertEquals(Host.Ipv6(listOf(0xFE80, 0, 0, 0, 0, 0, 0, 1), zoneId = "eth0"), uri.host)
        assertEquals("//[fe80::1%25eth0]", uri.uriString)
    }

    @Test
    fun `default parse rejects an ipv6 zone id`() {
        val err = assertIs<ParseResult.Err>(Uri.parse("//[fe80::1%25eth0]"))
        val cause = assertIs<UriParseError.InvalidHost>(err.error)
        assertEquals(HostError.ZoneIdRejected, cause.reason)
    }

    @Test
    fun `a zoned base resolves a reference under the same options`() {
        val base = Uri.parse("foo://[fe80::1%25eth0]/a/b", zoneOptions).getOrThrow()

        val resolved = base.resolve("x", zoneOptions).getOrThrow()

        assertEquals(Host.Ipv6(listOf(0xFE80, 0, 0, 0, 0, 0, 0, 1), zoneId = "eth0"), resolved.host)
        assertEquals("foo://[fe80::1%25eth0]/a/x", resolved.uriString)
    }

    @Test
    fun `resolve without explicit options still resolves a zoned base`() {
        val base = Uri.parse("foo://[fe80::1%25eth0]/a/b", zoneOptions).getOrThrow()

        val resolved = base.resolve("x").getOrThrow()

        assertEquals(Host.Ipv6(listOf(0xFE80, 0, 0, 0, 0, 0, 0, 1), zoneId = "eth0"), resolved.host)
        assertEquals("foo://[fe80::1%25eth0]/a/x", resolved.uriString)
    }

    @Test
    fun `equals is false against a non-uri value`() {
        val notAUri: Any = "http://h/p"

        assertFalse(parseOk("http://h/p").equals(notAUri))
    }

    @Test
    fun `a credential-less authority reports a null userinfo and omits it from the authority`() {
        // Reconstructs the authority with no userinfo but an explicit port: userInfo folds an
        // empty username and password to null, and the authority carries only host:port.
        val uri = parseOk("http://h:80/")

        assertNull(uri.userInfo)
        assertEquals("h:80", uri.authority)
    }

    @Test
    fun `the trailing dot of a reg-name host is preserved`() {
        // A non-IPv4 reg-name keeps its FQDN trailing dot verbatim ('.' is unreserved); a dotted-quad
        // IPv4 host would strip it instead.
        val uri = parseOk("foo://google.com./")

        assertEquals(Host.RegName("google.com."), uri.host)
        assertEquals("google.com.", uri.hostName)
    }

    @Test
    fun `an authority with two at-signs splits userinfo at the last at-sign`() {
        // RFC 3986 §3.2 splits userinfo from host at the LAST '@'; the earlier '@' stays in the userinfo.
        val uri = parseOk("foo://a@b@c/p")

        assertEquals("a@b", uri.userInfo)
        assertEquals(Host.RegName("c"), uri.host)
        assertEquals("c", uri.hostName)
        assertEquals("a@b@c", uri.authority)
    }

    @Test
    fun `a question mark after the hash is fragment content not a query`() {
        // The fragment is split off at the first '#' before any '?' scan runs so a later '?' is fragment data.
        val uri = parseOk("foo:bar#?bob")

        assertNull(uri.query)
        assertEquals("?bob", uri.fragment)
        assertEquals("bar", uri.path)
    }
}
