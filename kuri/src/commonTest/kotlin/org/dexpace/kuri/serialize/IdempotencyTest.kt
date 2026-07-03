/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.serialize

import org.dexpace.kuri.ParseProfile
import org.dexpace.kuri.parser.UriParser
import org.dexpace.kuri.parser.UrlParser
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * §11.4 parse∘serialize fixed-point tests ([NORM-24], [NORM-25]): for representative inputs,
 * `serialize(parse(serialize(parse(x))))` equals `serialize(parse(x))`, i.e. `f(f(x)) == f(x)` for
 * `f = serialize ∘ parse`, in both profiles.
 */
internal class IdempotencyTest {
    @Test
    fun `url serialization is a parse-serialize fixed point`() {
        val inputs = listOf("https://u:p@h:8443/a/b?q#f", "https://h/", "mailto:a@b", "file:///x")
        for (input in inputs) {
            val once = serializeUrl(input)
            assertEquals(once, serializeUrl(once), "url f(f(x)) must equal f(x) for \"$input\"")
        }
    }

    @Test
    fun `uri serialization is a parse-serialize fixed point`() {
        val inputs =
            listOf("foo://example.com/over/there?name=ferret#nose", "http://a/b/c", "https://example.com/a/b/")
        for (input in inputs) {
            val once = serializeUri(input)
            assertEquals(once, serializeUri(once), "uri f(f(x)) must equal f(x) for \"$input\"")
        }
    }

    private fun serializeUrl(input: String): String {
        val parsed = requireNotNull(UrlParser.parse(input).getOrNull()) { "expected a parseable Url: $input" }
        return Serializer.serialize(parsed, ParseProfile.URL)
    }

    private fun serializeUri(input: String): String {
        val parsed = requireNotNull(UriParser.parse(input).getOrNull()) { "expected a parseable Uri: $input" }
        return Serializer.serialize(parsed, ParseProfile.URI)
    }
}
