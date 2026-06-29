/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.host

import org.dexpace.kuri.error.ParseResult
import org.dexpace.kuri.error.UriParseError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for [OpaqueHost.parse] against SPEC §7.5 ([HOST-33]/[HOST-34], [HOST-40]) and the
 * forbidden-host table of §7.6 [HOST-36]; cross-checked against §13.3 cat G opaque/non-special.
 */
class OpaqueHostTest {
    @Test
    fun `parse yields Empty when input is empty`() {
        val result = OpaqueHost.parse("")

        assertEquals(ParseResult.Ok(Host.Empty), result)
    }

    @Test
    fun `parse passes a plain opaque host through unchanged`() {
        val result = OpaqueHost.parse("example")

        assertEquals(ParseResult.Ok(Host.Opaque("example")), result)
    }

    @Test
    fun `parse preserves a dotted opaque host without IPv4 interpretation`() {
        // Per [SCH-22] a non-special dotted authority stays an opaque host, not Ipv4.
        val result = OpaqueHost.parse("foo.bar")

        assertEquals(ParseResult.Ok(Host.Opaque("foo.bar")), result)
    }

    @Test
    fun `parse rejects each forbidden host code point`() {
        // Every [HOST-36] entry reachable inside a flat host substring (excluding '%').
        val forbidden = listOf(' ', '#', '/', ':', '?', '@', '[', '\\', ']', '^', '|', '<', '>')

        forbidden.forEach { cp ->
            val result = OpaqueHost.parse("a${cp}b")
            val err = assertIs<ParseResult.Err>(result, "expected Err for U+${cp.code}")
            val cause = assertIs<UriParseError.ForbiddenHostCodePoint>(err.error)
            assertEquals(cp.code, cause.codePoint, "wrong code point for U+${cp.code}")
            assertEquals(1, cause.at, "wrong offset for U+${cp.code}")
        }
    }

    @Test
    fun `parse allows and preserves a literal percent`() {
        // '%' is not a forbidden host code point ([HOST-33]); existing triplets pass through.
        val result = OpaqueHost.parse("a%62c")

        assertEquals(ParseResult.Ok(Host.Opaque("a%62c")), result)
    }

    @Test
    fun `parse C0-control percent-encodes a control code point in the value`() {
        val result = OpaqueHost.parse("a" + Char(0x01) + "b")

        assertEquals(ParseResult.Ok(Host.Opaque("a%01b")), result)
    }

    @Test
    fun `parse C0-control percent-encodes DELETE which is not forbidden as a host`() {
        // U+007F is not in the forbidden-host table, so it is encoded rather than rejected.
        val result = OpaqueHost.parse("a" + Char(0x7F) + "b")

        assertEquals(ParseResult.Ok(Host.Opaque("a%7Fb")), result)
    }

    @Test
    fun `parse percent-encodes non-ASCII as UTF-8 octets`() {
        // U+00E9 is allowed for an opaque host and C0-set-encoded as its UTF-8 bytes.
        val result = OpaqueHost.parse("caf" + Char(0x00E9))
        val ok = assertIs<ParseResult.Ok<Host>>(result)
        val host = assertIs<Host.Opaque>(ok.value)

        assertEquals("caf%C3%A9", host.value)
    }

    @Test
    fun `parse reports the first forbidden code point in input order`() {
        val result = OpaqueHost.parse("ok then bad/here")
        val err = assertIs<ParseResult.Err>(result)
        val cause = assertIs<UriParseError.ForbiddenHostCodePoint>(err.error)

        assertEquals(' '.code, cause.codePoint)
        assertEquals(2, cause.at)
    }

    @Test
    fun `parse keeps a sole literal percent as a valid opaque host`() {
        val result = OpaqueHost.parse("%")

        assertTrue(result is ParseResult.Ok && result.value == Host.Opaque("%"))
    }
}
