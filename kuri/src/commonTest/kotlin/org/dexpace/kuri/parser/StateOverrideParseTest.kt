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
        assertEquals(listOf("new", "path"), (c.path as ComponentPath.Segments).segments)
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
    fun `hostname override with an embedded colon is a total no-op`() {
        // WHATWG host state, `:` branch: "If state override is given and state override is
        // hostname state, then return" -- BEFORE the buffer is parsed as a host at all, so neither
        // the host nor the port changes (WPT setters_tests.json exercises this exact shape: e.g.
        // hostname "example.com:8080" on "http://example.net/path" leaves host "example.net").
        val result = UrlParser.parseWithOverride("example.com:81", seed("http://h:90/"), StateOverride.HOSTNAME)
        val c = (result as ParseResult.Ok).value
        assertEquals("h", c.host?.asText())
        assertEquals(90, c.port)
    }

    @Test
    fun `port override replaces the port`() {
        val c = (UrlParser.parseWithOverride("8080", seed("http://h/"), StateOverride.PORT) as ParseResult.Ok).value
        assertEquals(8080, c.port)
    }

    @Test
    fun `protocol override commits a same-class scheme`() {
        val result = UrlParser.parseWithOverride("https:", seed("http://h/"), StateOverride.PROTOCOL)
        assertEquals("https", (result as ParseResult.Ok).value.scheme)
    }

    @Test
    fun `protocol override refuses a special to non-special change`() {
        val result = UrlParser.parseWithOverride("mailto:", seed("http://h/"), StateOverride.PROTOCOL)
        assertEquals("http", (result as ParseResult.Ok).value.scheme)
    }

    @Test
    fun `protocol override refuses a file scheme when credentials are present`() {
        val result = UrlParser.parseWithOverride("file:", seed("http://u:p@h/"), StateOverride.PROTOCOL)
        assertEquals("http", (result as ParseResult.Ok).value.scheme)
    }

    @Test
    fun `protocol override elides a new default port`() {
        val result = UrlParser.parseWithOverride("http:", seed("https://h:80/"), StateOverride.PROTOCOL)
        val c = (result as ParseResult.Ok).value
        assertEquals("http", c.scheme)
        assertEquals(null, c.port)
    }

    @Test
    fun `hostname override with an empty value is a no-op when userinfo is present`() {
        val result = UrlParser.parseWithOverride("", seed("sc://u@h/"), StateOverride.HOSTNAME)
        assertEquals("h", (result as ParseResult.Ok).value.host?.asText())
    }
}
