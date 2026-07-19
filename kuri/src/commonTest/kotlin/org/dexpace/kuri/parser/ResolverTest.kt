/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.parser

import org.dexpace.kuri.ParseOptions
import org.dexpace.kuri.error.ParseResult
import org.dexpace.kuri.error.UriParseError
import org.dexpace.kuri.host.Host
import org.dexpace.kuri.serialize.UriSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * RFC 3986 §5 reference-resolution tests: the §5.2.4 remove_dot_segments unit cases and the full
 * §5.4.1 (normal) and §5.4.2 (abnormal, STRICT) example tables against the canonical base.
 */
internal class ResolverTest {
    @Test
    fun `removeDotSegments applies the RFC 3986 section 5_2_4 cases`() {
        val cases =
            listOf(
                "/a/b/c/./../../g" to "/a/g",
                "mid/content=5/../6" to "mid/6",
                "/./g" to "/g",
                "/../g" to "/g",
                "a/./b" to "a/b",
                "" to "",
                "/" to "/",
            )
        for ((input, expected) in cases) {
            assertEquals(expected, Resolver.removeDotSegments(input), "removeDotSegments(\"$input\")")
        }
    }

    @Test
    fun `resolve handles the RFC 3986 section 5_4_1 normal examples`() {
        val cases =
            listOf(
                "g:h" to "g:h",
                "g" to "http://a/b/c/g",
                "./g" to "http://a/b/c/g",
                "g/" to "http://a/b/c/g/",
                "/g" to "http://a/g",
                "//g" to "http://g",
                "?y" to "http://a/b/c/d;p?y",
                "g?y" to "http://a/b/c/g?y",
                "#s" to "http://a/b/c/d;p?q#s",
                "g#s" to "http://a/b/c/g#s",
                "g?y#s" to "http://a/b/c/g?y#s",
                ";x" to "http://a/b/c/;x",
                "g;x" to "http://a/b/c/g;x",
                "g;x?y#s" to "http://a/b/c/g;x?y#s",
                "" to "http://a/b/c/d;p?q",
                "." to "http://a/b/c/",
                "./" to "http://a/b/c/",
                ".." to "http://a/b/",
                "../" to "http://a/b/",
                "../g" to "http://a/b/g",
                "../.." to "http://a/",
                "../../" to "http://a/",
                "../../g" to "http://a/g",
            )
        assertResolves(cases)
    }

    @Test
    fun `resolve handles the RFC 3986 section 5_4_2 abnormal examples in strict mode`() {
        val cases =
            listOf(
                "../../../g" to "http://a/g",
                "../../../../g" to "http://a/g",
                "/./g" to "http://a/g",
                "/../g" to "http://a/g",
                "g." to "http://a/b/c/g.",
                ".g" to "http://a/b/c/.g",
                "g.." to "http://a/b/c/g..",
                "..g" to "http://a/b/c/..g",
                "./../g" to "http://a/b/g",
                "./g/." to "http://a/b/c/g/",
                "g/./h" to "http://a/b/c/g/h",
                "g/../h" to "http://a/b/c/h",
                "g;x=1/./y" to "http://a/b/c/g;x=1/y",
                "g;x=1/../y" to "http://a/b/c/y",
                "g?y/./x" to "http://a/b/c/g?y/./x",
                "g?y/../x" to "http://a/b/c/g?y/../x",
                "g#s/./x" to "http://a/b/c/g#s/./x",
                "g#s/../x" to "http://a/b/c/g#s/../x",
                "http:g" to "http:g",
            )
        assertResolves(cases)
    }

    @Test
    fun `removeDotSegments discards bare and leading relative dot segments`() {
        // Cases A/D of §5.2.4 that the resolve tables never feed removeDotSegments directly: a
        // leading "../" or "./" complete dot-segment is dropped, and a bare "." or ".." consumes all.
        val cases =
            listOf(
                "../g" to "g",
                "./g" to "g",
                "." to "",
                ".." to "",
            )
        for ((input, expected) in cases) {
            assertEquals(expected, Resolver.removeDotSegments(input), "removeDotSegments(\"$input\")")
        }
    }

    @Test
    fun `resolve forwards a fatal base parse error`() {
        val result = Resolver.resolve("http://h/%2", "g")

        assertIs<ParseResult.Err>(result)
    }

    @Test
    fun `resolve forwards a fatal reference parse error`() {
        val result = Resolver.resolve("http://h/p", "%2")

        assertIs<ParseResult.Err>(result)
    }

    @Test
    fun `resolve rejects a base with no scheme`() {
        val result = Resolver.resolve("//host/path", "g")

        val err = assertIs<ParseResult.Err>(result)
        assertEquals(UriParseError.MissingScheme, err.error)
    }

    @Test
    fun `resolve guards a dot-segment-collapsed authority-less two-slash path`() {
        // RFC 3986 §3.3: an authority-less path cannot begin with "//" (it would re-read as an
        // authority). "/.//g" resolved against a no-authority base collapses via §5.2.4 case B to
        // "//g", which recompose must guard with the leading "/." so the target re-parses with no
        // authority and path "//g", rather than fabricating host "g".
        val resolved = Resolver.resolve("a:/b/c", "/.//g").getOrThrow()

        assertEquals("a:/.//g", resolved)
    }

    @Test
    fun `resolve returns an error instead of throwing when the merge exceeds the length bound`() {
        // Each individual input parses fine (well under ParseOptions.DEFAULT.inputLength), but
        // base's directory prefix concatenated with the rootless reference exceeds the resolver's
        // ExpandedLength bound; this must surface as a ParseResult.Err, never an escaping
        // exception ("total, never throws").
        val base = "a:/" + "x".repeat(SEGMENT_LENGTH) + "/c"
        val longRef = "y".repeat(SEGMENT_LENGTH)

        val result = Resolver.resolve(base, longRef)

        val err = assertIs<ParseResult.Err>(result)
        assertIs<UriParseError.InputTooLong>(err.error)
    }

    @Test
    fun `resolve respects an overridden expandedLength and reports InputTooLong with the configured max`() {
        // A merge that fits under the default ExpandedLength must fail once the caller lowers it.
        val base = "a:/aaaa/c"
        val longRef = "y".repeat(20)
        val options = ParseOptions.Builder().expandedLength(10).build()

        val result = Resolver.resolve(base, longRef, options)

        val err = assertIs<ParseResult.Err>(result)
        val tooLong = assertIs<UriParseError.InputTooLong>(err.error)
        assertEquals(10, tooLong.max)
    }

    @Test
    fun `resolve merges a relative reference onto an empty-authority base path`() {
        // base authority present with an empty base path: §5.2.3 prepends a single "/" to the ref.
        val result = Resolver.resolve("http://a", "g").getOrThrow()

        assertEquals("http://a/g", result)
    }

    @Test
    fun `resolve merges a relative reference onto a rootless base path`() {
        // base has no authority and a rootless path with no "/": mergeOntoBase keeps an empty prefix.
        val result = Resolver.resolve("foo:bar", "g").getOrThrow()

        assertEquals("foo:g", result)
    }

    @Test
    fun `structured resolve enables zone parsing from a zoned reference`() {
        // Only the reference carries an RFC 6874 zone id, so structuredOptions opts in via its right
        // operand (the base host has none).
        val zoneOptions = ParseOptions.Builder().allowIpv6ZoneId(true).build()
        val base = UriParser.parse("http://a/b", zoneOptions).getOrThrow()
        val reference = UriParser.parse("foo://[fe80::1%25eth0]/x", zoneOptions).getOrThrow()

        val resolved = Resolver.resolve(base, reference).getOrThrow()

        assertEquals(Host.Ipv6(listOf(0xFE80, 0, 0, 0, 0, 0, 0, 1), zoneId = "eth0"), resolved.host)
    }

    @Test
    fun `structured resolve preserves a rootless scheme reference`() {
        // The structured overload reads the path back through pathString; a rooted reference scheme
        // path (mailto:x@y) must stay rootless rather than gaining a leading slash ("mailto:/x@y").
        val base = UriParser.parse("http://a/b/c/d").getOrThrow()
        val reference = UriParser.parse("mailto:x@y").getOrThrow()

        val resolved = Resolver.resolve(base, reference).getOrThrow()

        assertEquals("mailto:x@y", UriSerializer.serialize(resolved))
    }

    @Test
    fun `structured resolve merges a rootless relative reference against the base path`() {
        // Pins the partsOf/pathString rootless branch on the reference: "g/x" merges onto "/a/b".
        val base = UriParser.parse("http://h/a/b").getOrThrow()
        val reference = UriParser.parse("g/x").getOrThrow()

        val resolved = Resolver.resolve(base, reference).getOrThrow()

        assertEquals("http://h/a/g/x", UriSerializer.serialize(resolved))
    }

    @Test
    fun `structured resolve inherits a base username-only userinfo`() {
        // Pins the userinfoPrefix username-only branch: a base with a username but no password is
        // inherited by a relative reference that supplies neither scheme nor authority.
        val base = UriParser.parse("http://user@host/a/b").getOrThrow()
        val reference = UriParser.parse("x").getOrThrow()

        val resolved = Resolver.resolve(base, reference).getOrThrow()

        assertEquals("user", resolved.username)
        assertEquals("", resolved.password)
        assertEquals("http://user@host/a/x", UriSerializer.serialize(resolved))
    }

    @Test
    fun `structured resolve inherits a base username and password userinfo`() {
        // Pins the userinfoPrefix username-and-password branch.
        val base = UriParser.parse("http://user:pass@host/a/b").getOrThrow()
        val reference = UriParser.parse("x").getOrThrow()

        val resolved = Resolver.resolve(base, reference).getOrThrow()

        assertEquals("user", resolved.username)
        assertEquals("pass", resolved.password)
        assertEquals("http://user:pass@host/a/x", UriSerializer.serialize(resolved))
    }

    @Test
    fun `structured resolve preserves a zoned base`() {
        val zoneOptions = ParseOptions.Builder().allowIpv6ZoneId(true).build()
        val base = UriParser.parse("foo://[fe80::1%25eth0]/a/b", zoneOptions).getOrThrow()
        val reference = UriParser.parse("x", zoneOptions).getOrThrow()

        val resolved = Resolver.resolve(base, reference).getOrThrow()

        assertEquals(Host.Ipv6(listOf(0xFE80, 0, 0, 0, 0, 0, 0, 1), zoneId = "eth0"), resolved.host)
        assertEquals("foo://[fe80::1%25eth0]/a/x", UriSerializer.serialize(resolved))
    }

    @Test
    fun `structured resolve inherits a base port`() {
        // Pins the authorityOf port branch: a base authority carrying an explicit port is
        // re-serialized (":8080") when a relative reference inherits the base authority.
        val base = UriParser.parse("http://h:8080/a/b").getOrThrow()
        val reference = UriParser.parse("x").getOrThrow()

        val resolved = Resolver.resolve(base, reference).getOrThrow()

        assertEquals(8080, resolved.port)
        assertEquals("http://h:8080/a/x", UriSerializer.serialize(resolved))
    }

    @Test
    fun `structured resolve inherits an empty-username password userinfo`() {
        // Pins the userinfoPrefix branch where the username is empty but the password is present:
        // the first arm's second condition (password.isEmpty()) is false, so the prefix is ":pass@"
        // rather than the both-present "u:p@" form.
        val base = UriParser.parse("http://:pass@h/a/b").getOrThrow()
        val reference = UriParser.parse("x").getOrThrow()

        val resolved = Resolver.resolve(base, reference).getOrThrow()

        assertEquals("", resolved.username)
        assertEquals("pass", resolved.password)
        assertEquals("http://:pass@h/a/x", UriSerializer.serialize(resolved))
    }

    @Test
    fun `structured resolve returns an error instead of throwing when the merge exceeds the length bound`() {
        // Mirrors "resolve returns an error instead of throwing when the merge exceeds the length
        // bound" on the structured overload: both inputs parse fine individually, but base's
        // directory prefix concatenated with the rootless reference exceeds the resolver's default
        // ExpandedLength bound, which the structured resolve shares via transformReferences.
        val base = UriParser.parse("a:/" + "x".repeat(SEGMENT_LENGTH) + "/c").getOrThrow()
        val reference = UriParser.parse("y".repeat(SEGMENT_LENGTH)).getOrThrow()

        val result = Resolver.resolve(base, reference)

        val err = assertIs<ParseResult.Err>(result)
        assertIs<UriParseError.InputTooLong>(err.error)
    }

    private fun assertResolves(cases: List<Pair<String, String>>) {
        for ((reference, expected) in cases) {
            val resolved = Resolver.resolve(BASE, reference).getOrThrow()
            assertEquals(expected, resolved, "resolve(\"$BASE\", \"$reference\")")
        }
    }

    private fun <T> ParseResult<T>.getOrThrow(): T =
        when (this) {
            is ParseResult.Ok -> value
            is ParseResult.Err -> error("expected a resolved URI but got an error: $error")
        }

    private companion object {
        /** The canonical RFC 3986 §5.4 base URI every example table resolves against. */
        const val BASE: String = "http://a/b/c/d;p?q"

        /**
         * Half of each length-bound test case's segment length: each half stays under
         * `ParseOptions.DEFAULT.inputLength` (65,536) on its own, but the merged path exceeds
         * `ParseOptions.DEFAULT.expandedLength` (also 65,536 by default).
         */
        const val SEGMENT_LENGTH: Int = 40_000
    }
}
