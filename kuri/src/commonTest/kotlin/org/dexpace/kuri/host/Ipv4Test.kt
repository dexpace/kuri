/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.host

import org.dexpace.kuri.ParseProfile
import org.dexpace.kuri.error.HostError
import org.dexpace.kuri.error.ParseResult
import org.dexpace.kuri.error.UriParseError
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for the IPv4 host parser/serializer against SPEC §7.3 and §13.3 category E
 * ([CONF-37]..[CONF-41]).
 */
class Ipv4Test {
    private fun parseUrl(input: String): ParseResult<Host.Ipv4> = Ipv4.parse(input, ParseProfile.URL)

    private fun parseUri(input: String): ParseResult<Host.Ipv4> = Ipv4.parse(input, ParseProfile.URI)

    /** Parses under the `Url` profile and re-serializes, the canonical round trip. */
    private fun canonicalUrl(input: String): String {
        val result = parseUrl(input)
        assertIs<ParseResult.Ok<Host.Ipv4>>(result, "expected Ok for '$input', got $result")
        return Ipv4.serialize(result.value.value)
    }

    private fun assertOverflow(input: String) {
        val result = parseUrl(input)
        assertIs<ParseResult.Err>(result, "expected Err for '$input'")
        assertEquals(HostError.Ipv4Overflow, (result.error as UriParseError.InvalidHost).reason)
    }

    @Test
    fun `dotted-decimal host parses to Ipv4`() {
        assertEquals("192.0.2.16", canonicalUrl("192.0.2.16"))
    }

    @Test
    fun `octet overflow is rejected in the Url profile`() {
        assertOverflow("192.168.0.257")
    }

    @Test
    fun `a single trailing dot is stripped before parsing`() {
        assertEquals("192.0.2.16", canonicalUrl("192.0.2.16."))
    }

    @Test
    fun `WHATWG hex octal and shorthand forms re-serialize canonically`() {
        val cases =
            listOf(
                "0xc0.0x00.0x02.0xeb" to "192.0.2.235",
                "0xffffffff" to "255.255.255.255",
                "1.1.1.1" to "1.1.1.1",
                "0300.0250.0.01" to "192.168.0.1",
                "0xC0A80001" to "192.168.0.1",
                "192.0xA80001" to "192.168.0.1",
                "192.168.1" to "192.168.0.1",
                "0x" to "0.0.0.0",
                "0x.0x.0x.0x" to "0.0.0.0",
                "127.0.0.1" to "127.0.0.1",
            )
        cases.forEach { (input, expected) ->
            assertEquals(expected, canonicalUrl(input), "input '$input'")
        }
    }

    @Test
    fun `width-aware overflow and bad shape are rejected`() {
        // 5 parts, 4-part octet overflow, 1-/2-part width overflow, non-numeric part, empty parts.
        listOf("1.2.3.4.5", "256.1.1.1", "4294967296", "0x100000000", "192.0x1000000").forEach {
            assertOverflow(it)
        }
        listOf("1.2.x.4" to HostError.Ipv4NonNumeric, "1..2.3" to HostError.Ipv4NonNumeric).forEach { (input, reason) ->
            val result = parseUrl(input)
            assertIs<ParseResult.Err>(result, "expected Err for '$input'")
            assertEquals(reason, (result.error as UriParseError.InvalidHost).reason, "input '$input'")
        }
    }

    @Test
    fun `Uri profile accepts only exact dotted-decimal`() {
        val ok =
            listOf(
                "192.0.2.16" to "192.0.2.16",
                "0.0.0.0" to "0.0.0.0",
                "255.255.255.255" to "255.255.255.255",
                "9.8.7.6" to "9.8.7.6",
            )
        ok.forEach { (input, expected) ->
            val result = parseUri(input)
            assertIs<ParseResult.Ok<Host.Ipv4>>(result, "expected Ok for '$input'")
            assertEquals(expected, Ipv4.serialize(result.value.value), "input '$input'")
        }
    }

    @Test
    fun `Uri profile rejects hex octal shorthand and malformed octets`() {
        listOf(
            "0xc0.0x00.0x02.0xeb",
            "0300.0250.0.01",
            "192.168.1",
            "01.2.3.4",
            "1.2.3.256",
            "192.0.2.16.",
            "1.2.3",
            "256.0.0.1",
        ).forEach { input ->
            assertIs<ParseResult.Err>(parseUri(input), "expected Err for '$input'")
        }
    }

    @Test
    fun `Uri profile rejects octets that break the dec-octet grammar`() {
        // A non-digit part, an over-long four-digit part, and an empty part each fail the exact
        // RFC 3986 dec-octet rule (§7.3.2 [HOST-24]) with Ipv4NonNumeric.
        listOf("1a.2.3.4", "1234.2.3.4", "1..2.3").forEach { input ->
            val result = parseUri(input)
            assertIs<ParseResult.Err>(result, "expected Err for '$input'")
            assertEquals(
                HostError.Ipv4NonNumeric,
                (result.error as UriParseError.InvalidHost).reason,
                "input '$input'",
            )
        }
    }

    @Test
    fun `serialize emits high-to-low octets for boundary values`() {
        assertEquals("0.0.0.0", Ipv4.serialize(0))
        assertEquals("255.255.255.255", Ipv4.serialize(-1))
        // 0x7F000001 == 127.0.0.1 fits a positive Int and exercises the high octet.
        assertEquals("127.0.0.1", Ipv4.serialize(0x7F000001))
    }

    @Test
    fun `parse then serialize round-trips for non-decimal radices`() {
        assertEquals("192.168.0.1", canonicalUrl("0xC0A80001"))
        assertEquals(canonicalUrl("3232235521"), canonicalUrl("0xC0A80001"))
    }

    @Test
    fun `endsInANumber is true for numeric last labels`() {
        listOf("192.0.2.16", "0xc0", "1", "0x", "10.0x1f", "1.", "0Xff", "255", "1.2.3.4").forEach {
            assertTrue(Ipv4.endsInANumber(it), "expected ends-in-number: '$it'")
        }
    }

    @Test
    fun `endsInANumber is false for non-numeric last labels`() {
        listOf("example.com", "1.2.3.foo", "", "host", ".", "1a", "1.2.foo", "0xg").forEach {
            assertFalse(Ipv4.endsInANumber(it), "expected not ends-in-number: '$it'")
        }
    }

    @Test
    fun `asText renders an Ipv4 host as canonical dotted-decimal`() {
        // 1.2.3.4 packs to 0x01020304; -1 is the all-ones 255.255.255.255 that exercises the high bit.
        assertEquals("1.2.3.4", Host.Ipv4(0x01020304).asText())
        assertEquals("255.255.255.255", Host.Ipv4(-1).asText())
    }

    @Test
    fun `octets unpacks the packed value high-order octet first`() {
        assertContentEquals(intArrayOf(1, 2, 3, 4), Host.Ipv4(0x01020304).octets())
        assertContentEquals(intArrayOf(255, 255, 255, 255), Host.Ipv4(-1).octets())
    }

    @Test
    fun `octets and asText expose the same packed layout`() {
        // 0 and -1 are the all-zeroes/all-ones extremes; 0x7F000001 exercises the high octet.
        listOf(0, -1, 0x7F000001, 0x01020304).forEach { value ->
            val host = Host.Ipv4(value)
            assertEquals(host.asText(), host.octets().joinToString("."), "value ${value.toUInt()}")
        }
    }

    @Test
    fun `RegName asText returns the stored value verbatim`() {
        assertEquals("example.com", Host.RegName("example.com").asText())
    }
}
