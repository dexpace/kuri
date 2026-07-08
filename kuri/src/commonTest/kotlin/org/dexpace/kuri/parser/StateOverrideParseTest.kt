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
        assertEquals("f", c.fragment)
    }

    @Test
    fun `host override sets host and port from the value`() {
        val result = UrlParser.parseWithOverride("example.com:81", seed("http://h:80/"), StateOverride.HOST)
        val c = (result as ParseResult.Ok).value
        assertEquals("example.com", c.host?.asText())
        assertEquals(81, c.port)
    }

    @Test
    fun `hostname override ignores a colon and keeps the existing port`() {
        val result = UrlParser.parseWithOverride("example.com:81", seed("http://h:90/"), StateOverride.HOSTNAME)
        val c = (result as ParseResult.Ok).value
        assertEquals("example.com", c.host?.asText())
        assertEquals(90, c.port)
    }

    @Test
    fun `port override replaces the port`() {
        val c = (UrlParser.parseWithOverride("8080", seed("http://h/"), StateOverride.PORT) as ParseResult.Ok).value
        assertEquals(8080, c.port)
    }
}
