/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.parser

import org.dexpace.kuri.ParseProfile
import org.dexpace.kuri.error.ParseResult
import org.dexpace.kuri.serialize.Serializer
import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun `structured resolve preserves a rootless scheme reference`() {
        // The structured overload reads the path back through pathString; a rooted reference scheme
        // path (mailto:x@y) must stay rootless rather than gaining a leading slash ("mailto:/x@y").
        val base = UriParser.parse("http://a/b/c/d").getOrThrow()
        val reference = UriParser.parse("mailto:x@y").getOrThrow()

        val resolved = Resolver.resolve(base, reference)

        assertEquals("mailto:x@y", Serializer.serialize(resolved, ParseProfile.URI))
    }

    @Test
    fun `structured resolve merges a rootless relative reference against the base path`() {
        // Pins the partsOf/pathString rootless branch on the reference: "g/x" merges onto "/a/b".
        val base = UriParser.parse("http://h/a/b").getOrThrow()
        val reference = UriParser.parse("g/x").getOrThrow()

        val resolved = Resolver.resolve(base, reference)

        assertEquals("http://h/a/g/x", Serializer.serialize(resolved, ParseProfile.URI))
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
    }
}
