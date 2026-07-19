/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.bind.internal

import org.dexpace.kuri.bind.KuriBindException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PathTemplateTest {
    @Test
    fun `parses literals holes and catch-all`() {
        val t = PathTemplate.parse("/users/{id}/files/{path...}")
        assertEquals(
            listOf(
                PathToken.Literal("/users/"),
                PathToken.Hole("id", catchAll = false),
                PathToken.Literal("/files/"),
                PathToken.Hole("path", catchAll = true),
            ),
            t.tokens,
        )
        assertEquals(listOf("id", "path"), t.holes.map { it.name })
    }

    @Test
    fun `rejects a non-final catch-all`() {
        assertFailsWith<KuriBindException> { PathTemplate.parse("/{a...}/{b}") }
    }

    @Test
    fun `rejects a trailing literal after a catch-all`() {
        assertFailsWith<KuriBindException> { PathTemplate.parse("/files/{p...}/download") }
    }

    @Test
    fun `rejects duplicate hole names`() {
        assertFailsWith<KuriBindException> { PathTemplate.parse("/{a}/{a}") }
    }

    @Test
    fun `rejects an empty hole name and unbalanced braces`() {
        assertFailsWith<KuriBindException> { PathTemplate.parse("/{}") }
        assertFailsWith<KuriBindException> { PathTemplate.parse("/{a") }
    }

    @Test
    fun `rejects an empty template`() {
        assertFailsWith<KuriBindException> { PathTemplate.parse("") }
    }

    @Test
    fun `rejects adjacent holes with no literal between them`() {
        // The composer emits one full segment per hole regardless of adjacency, so "/{a}{b}" would
        // compose identically to "/{a}/{b}" despite its spelling implying a single merged segment.
        assertFailsWith<KuriBindException> { PathTemplate.parse("/{a}{b}") }
    }

    @Test
    fun `rejects a lone closing brace`() {
        assertFailsWith<KuriBindException> { PathTemplate.parse("a}b") }
    }

    @Test
    fun `parses a literal-only template with no holes`() {
        val t = PathTemplate.parse("/static/path")
        assertEquals(0, t.holes.size)
        assertEquals(listOf(PathToken.Literal("/static/path")), t.tokens)
    }

    @Test
    fun `rejects a nested brace inside a hole name`() {
        // The inner '{' is swallowed into the hole body (indexOf stops at the first '}'), so the
        // parsed hole name "a{b" carries a stray brace and must be rejected.
        assertFailsWith<KuriBindException> { PathTemplate.parse("/{a{b}") }
    }

    @Test
    fun `rejects a hole followed by a literal suffix in the same segment`() {
        // Issue #82: "/reports/{id}.json" would otherwise re-segment to ["reports", "5", ".json"].
        assertFailsWith<KuriBindException> { PathTemplate.parse("/reports/{id}.json") }
    }

    @Test
    fun `rejects a literal prefix followed by a hole in the same segment`() {
        // Issue #82: "v{version}" would otherwise re-segment to ["v", "2"] instead of one "v2" segment.
        assertFailsWith<KuriBindException> { PathTemplate.parse("v{version}") }
    }

    @Test
    fun `rejects a catch-all hole sharing a segment with a literal prefix`() {
        assertFailsWith<KuriBindException> { PathTemplate.parse("/files{path...}") }
    }

    @Test
    fun `rejects a hole with an unbounded literal on both sides at once`() {
        // "/a{id}b/c" fails the check twice over: "a" doesn't end in '/' and "b" doesn't start with '/'.
        assertFailsWith<KuriBindException> { PathTemplate.parse("/a{id}b/c") }
    }

    @Test
    fun `parses a hole immediately followed by a slash-rooted literal`() {
        val t = PathTemplate.parse("/reports/{id}/detail")
        assertEquals(
            listOf(
                PathToken.Literal("/reports/"),
                PathToken.Hole("id", catchAll = false),
                PathToken.Literal("/detail"),
            ),
            t.tokens,
        )
    }
}
