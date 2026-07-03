/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.host

import org.dexpace.kuri.error.HostError
import org.dexpace.kuri.error.ParseResult
import org.dexpace.kuri.error.UriParseError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the IPv6 literal parser and RFC 5952 serializer (SPEC §7.2,
 * [HOST-6]..[HOST-16]; §13.3 category F). Parser input is the bracket-free
 * contents of an `[...]` literal; the serializer emits the bracket-free address.
 */
class Ipv6Test {
    private val expectedPieceCount: Int = 8

    private fun parsedPieces(input: String): List<Int> {
        val result = Ipv6.parse(input)
        assertTrue(result is ParseResult.Ok, "expected Ok for [$input], got $result")
        return result.value.pieces
    }

    private fun roundTrip(input: String): String = Ipv6.serialize(parsedPieces(input))

    @Test
    fun `parse yields exactly eight pieces each in range`() {
        val pieces = parsedPieces("2001:db8::1")
        assertEquals(expectedPieceCount, pieces.size, "must hold eight pieces")
        assertTrue(pieces.all { it in 0..0xFFFF }, "each piece must be a 16-bit value")
    }

    @Test
    fun `serialize compresses the longest zero run leftmost on ties`() {
        assertEquals("2001:db8::1:0:0:1", roundTrip("2001:db8:0:0:1:0:0:1"))
    }

    @Test
    fun `serialize renders leading zero run as a leading double colon`() {
        assertEquals("::1", roundTrip("::0001"))
        assertEquals("::1", roundTrip("0000::0001"))
    }

    @Test
    fun `serialize renders trailing zero run as a trailing double colon`() {
        assertEquals("1::", roundTrip("0001::0000"))
    }

    @Test
    fun `parse folds an embedded ipv4 trailer into the low pieces`() {
        assertEquals("::1:ffff:ffff", roundTrip("::1:255.255.255.255"))
    }

    @Test
    fun `parse keeps a hex-only ipv4-mapped form unchanged`() {
        assertEquals("::ffff:c0a8:1fe", roundTrip("::ffff:c0a8:1fe"))
    }

    @Test
    fun `parse accepts the all-zero unspecified address`() {
        assertEquals("::", roundTrip("::"))
    }

    @Test
    fun `serialize never compresses a single zero piece`() {
        assertEquals("1:0:1:1:1:1:1:1", Ipv6.serialize(listOf(1, 0, 1, 1, 1, 1, 1, 1)))
    }

    @Test
    fun `serialize compresses the strictly longest of several zero runs`() {
        assertEquals("1::1:0:0:1", Ipv6.serialize(listOf(1, 0, 0, 0, 1, 0, 0, 1)))
    }

    @Test
    fun `serialize picks the leftmost run when several share the longest length`() {
        assertEquals("::1:0:0:1:0:0", Ipv6.serialize(listOf(0, 0, 1, 0, 0, 1, 0, 0)))
    }

    @Test
    fun `parse rejects invalid literals`() {
        val invalid =
            listOf(
                "",
                "::00001",
                ":1",
                ":::1",
                "1:::1",
                "1:::",
                "1:2:3:4:5:6:7:8:",
                "1:2:3:4:5:6:7:8:9",
                "1::2::3",
                "1:2:3:4:5:6:7",
                "::1:256.255.255.255",
                "::1:255.255.255",
                "::1:01.2.3.4",
                "::1:1.2.3.4.5",
                "::1:1.2..3",
                "1::g",
            )
        invalid.forEach { text ->
            assertTrue(Ipv6.parse(text) is ParseResult.Err, "expected Err for [$text]")
        }
    }

    // --- RFC 6874 zone identifiers ([HOST-17]/[HOST-18]) -----------------------------

    private fun zoneRejected(input: String) {
        val err = assertIs<ParseResult.Err>(Ipv6.parse(input), "expected Err for [$input]")
        val cause = assertIs<UriParseError.InvalidHost>(err.error)
        assertEquals(HostError.ZoneIdRejected, cause.reason, "default parse must reject a zone id")
    }

    private fun zoneMalformed(input: String) {
        val err = assertIs<ParseResult.Err>(Ipv6.parse(input, allowZoneId = true), "expected Err for [$input]")
        val cause = assertIs<UriParseError.InvalidHost>(err.error)
        assertEquals(HostError.Ipv6Malformed, cause.reason, "an opted-in bad zone must be Ipv6Malformed")
    }

    @Test
    fun `parse without a zone id leaves zoneId null`() {
        val result = assertIs<ParseResult.Ok<Host.Ipv6>>(Ipv6.parse("fe80::1", allowZoneId = true))
        assertNull(result.value.zoneId, "an address with no % carries no zone id")
    }

    @Test
    fun `opted-in parse accepts a named zone id and stores it raw`() {
        val result = assertIs<ParseResult.Ok<Host.Ipv6>>(Ipv6.parse("fe80::1%25eth0", allowZoneId = true))
        assertEquals("eth0", result.value.zoneId)
        assertEquals(listOf(0xFE80, 0, 0, 0, 0, 0, 0, 1), result.value.pieces)
    }

    @Test
    fun `opted-in parse accepts a numeric zone id`() {
        val result = assertIs<ParseResult.Ok<Host.Ipv6>>(Ipv6.parse("::1%2544", allowZoneId = true))
        assertEquals("44", result.value.zoneId)
        assertEquals(listOf(0, 0, 0, 0, 0, 0, 0, 1), result.value.pieces)
    }

    @Test
    fun `opted-in parse keeps a pct-encoded zone octet raw`() {
        val result = assertIs<ParseResult.Ok<Host.Ipv6>>(Ipv6.parse("fe80::1%25a%2Fb", allowZoneId = true))
        assertEquals("a%2Fb", result.value.zoneId, "the pct-encoded zone is stored verbatim, not decoded")
    }

    @Test
    fun `default parse rejects a numeric zone id as ZoneIdRejected`() {
        zoneRejected("::1%2544")
    }

    @Test
    fun `default parse rejects a named zone id as ZoneIdRejected`() {
        zoneRejected("fe80::1%25eth0")
    }

    @Test
    fun `default parse rejects a bare-percent zone as ZoneIdRejected`() {
        zoneRejected("fe80::1%eth0")
    }

    @Test
    fun `opted-in parse rejects a non-25 introducer as malformed`() {
        zoneMalformed("fe80::1%eth0")
    }

    @Test
    fun `opted-in parse rejects an empty zone id as malformed`() {
        zoneMalformed("fe80::1%25")
    }

    @Test
    fun `opted-in parse rejects an illegal zone code point as malformed`() {
        zoneMalformed("fe80::1%25e/f")
    }

    @Test
    fun `opted-in parse rejects an incomplete pct triplet in the zone as malformed`() {
        zoneMalformed("fe80::1%25e%f")
    }

    @Test
    fun `opted-in parse rejects a malformed address before the zone`() {
        zoneMalformed("1:2:3:4:5:6:7:8:9%25eth0")
    }

    @Test
    fun `a zoned host serializes with the raw zone after the introducer`() {
        val host = Host.Ipv6(listOf(0xFE80, 0, 0, 0, 0, 0, 0, 1), zoneId = "eth0")
        assertEquals("[fe80::1%25eth0]", host.serialize())
    }

    @Test
    fun `an unzoned host serializes with no zone suffix`() {
        val host = Host.Ipv6(listOf(0xFE80, 0, 0, 0, 0, 0, 0, 1))
        assertEquals("[fe80::1]", host.serialize())
    }

    @Test
    fun `asText matches the serialize extension for a bracketed ipv6 host`() {
        val host = Host.Ipv6(listOf(0x2001, 0xDB8, 0, 0, 0, 0, 0, 1))
        assertEquals(host.serialize(), host.asText(), "the member and extension must agree")
        assertEquals("[2001:db8::1]", host.asText())
    }
}
