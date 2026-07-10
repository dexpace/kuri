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
    fun `rejects a nested brace inside a hole name`() {
        // The inner '{' is swallowed into the hole body (indexOf stops at the first '}'), so the
        // parsed hole name "a{b" carries a stray brace and must be rejected.
        assertFailsWith<KuriBindException> { PathTemplate.parse("/{a{b}") }
    }
}
