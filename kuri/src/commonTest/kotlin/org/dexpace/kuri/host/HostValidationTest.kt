/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.host

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Tests for the inline `init { require(...) }` invariants on the [Host] variants (SPEC §7.2,
 * §7.3). These guard direct construction and, because `copy` re-runs the primary constructor,
 * `copy` as well; the checks throw [IllegalArgumentException]. Also pins the permissive
 * (non-validating) contract of [Host.RegName] / [Host.Opaque] and the total, never-throwing
 * [Host.Ipv4] constructor.
 */
class HostValidationTest {
    /** A canonical eight-piece address (`2001:db8::1`) reused as a valid base for `copy` cases. */
    private val validPieces: List<Int> = listOf(0x2001, 0xDB8, 0, 0, 0, 0, 0, 1)

    @Test
    fun `Ipv6 with fewer than eight pieces is rejected`() {
        assertFailsWith<IllegalArgumentException>("a two-piece address must not construct") {
            Host.Ipv6(listOf(1, 2))
        }
    }

    @Test
    fun `Ipv6 with more than eight pieces is rejected`() {
        assertFailsWith<IllegalArgumentException>("a nine-piece address must not construct") {
            Host.Ipv6(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9))
        }
    }

    @Test
    fun `Ipv6 with a piece above the 16-bit range is rejected`() {
        assertFailsWith<IllegalArgumentException>("65536 exceeds 0xFFFF") {
            Host.Ipv6(listOf(0, 0, 0, 0, 0, 0, 0, 0x10000))
        }
    }

    @Test
    fun `Ipv6 with a negative piece is rejected`() {
        assertFailsWith<IllegalArgumentException>("a negative piece is out of range") {
            Host.Ipv6(listOf(-1, 0, 0, 0, 0, 0, 0, 0))
        }
    }

    @Test
    fun `Ipv6 with an empty zoneId is rejected`() {
        assertFailsWith<IllegalArgumentException>("an empty zoneId must not construct") {
            Host.Ipv6(validPieces, zoneId = "")
        }
    }

    @Test
    fun `a valid eight-piece Ipv6 round-trips through asText`() {
        assertEquals("[2001:db8::1]", Host.Ipv6(validPieces).asText())
    }

    @Test
    fun `a valid zoned Ipv6 round-trips through asText`() {
        val host = Host.Ipv6(listOf(0xFE80, 0, 0, 0, 0, 0, 0, 1), zoneId = "eth0")
        assertEquals("[fe80::1%25eth0]", host.asText())
    }

    @Test
    fun `copy with a bad piece count is rejected so the invariant guards copy`() {
        val valid = Host.Ipv6(validPieces)
        assertFailsWith<IllegalArgumentException>("copy must re-run the invariant") {
            valid.copy(pieces = listOf(1))
        }
    }

    @Test
    fun `copy with an empty zoneId is rejected`() {
        val valid = Host.Ipv6(validPieces)
        assertFailsWith<IllegalArgumentException>("copy must reject an empty zoneId") {
            valid.copy(zoneId = "")
        }
    }

    @Test
    fun `copy to a valid zoneId succeeds and re-serializes`() {
        val zoned = Host.Ipv6(validPieces).copy(zoneId = "eth0")
        assertEquals("[2001:db8::1%25eth0]", zoned.asText())
    }

    @Test
    fun `IpFuture rejects an empty value`() {
        assertFailsWith<IllegalArgumentException>("an empty IPvFuture literal must not construct") {
            Host.IpFuture("")
        }
    }

    @Test
    fun `a non-empty IpFuture round-trips through asText`() {
        assertEquals("[v1.fe]", Host.IpFuture("v1.fe").asText())
    }

    @Test
    fun `Ipv4 accepts any 32-bit int value and renders canonically`() {
        // -1 is the all-ones address; the Int extremes exercise the sign bit end to end and must not throw.
        assertEquals("255.255.255.255", Host.Ipv4(-1).asText())
        assertContentEquals(intArrayOf(255, 255, 255, 255), Host.Ipv4(-1).octets())
        listOf(0, -1, Int.MIN_VALUE, Int.MAX_VALUE, 0x7F000001).forEach { value ->
            val host = Host.Ipv4(value)
            assertEquals(host.octets().joinToString("."), host.asText(), "value ${value.toUInt()}")
        }
    }

    @Test
    fun `RegName and Opaque stay permissive and never reject their value`() {
        // Canonical form is a caller contract, not validated inline, so even an empty value constructs.
        assertEquals("", Host.RegName("").asText())
        assertEquals("", Host.Opaque("").asText())
    }
}
