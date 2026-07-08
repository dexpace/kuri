/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.parser

import org.dexpace.kuri.error.ParseResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StateOverrideParseTest {
    private fun seed(input: String): ParsedComponents = (UrlParser.parse(input) as ParseResult.Ok).value

    @Test
    fun `pathname override replaces the path and preserves other components`() {
        val result = UrlParser.parseWithOverride("/new/path", seed("https://h/old?q#f"), StateOverride.PATHNAME)
        assertTrue(result is ParseResult.Ok)
        val c = result.value
        assertEquals("https", c.scheme)
        assertEquals(listOf("new", "path"), (c.path as UrlPath.Segments).segments)
        assertEquals("q", c.query)
    }
}
