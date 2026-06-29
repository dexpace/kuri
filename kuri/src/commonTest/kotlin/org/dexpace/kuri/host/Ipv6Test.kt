/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.host

import org.dexpace.kuri.error.ParseResult
import kotlin.test.Test
import kotlin.test.assertEquals
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
                "fe80::1%eth0",
            )
        invalid.forEach { text ->
            assertTrue(Ipv6.parse(text) is ParseResult.Err, "expected Err for [$text]")
        }
    }
}
