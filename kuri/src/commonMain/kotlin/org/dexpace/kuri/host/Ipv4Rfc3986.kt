/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.host

import org.dexpace.kuri.error.HostError
import org.dexpace.kuri.error.ParseResult
import org.dexpace.kuri.text.isAsciiDigit

/** Most digits a decimal `dec-octet` may have (`255` is three; §7.3.2 [HOST-24]). */
private const val MAX_OCTET_DIGITS: Int = 3

/**
 * The `Uri`-profile IPv4 parser: the exact RFC 3986 `IPv4address` grammar of §7.3.2
 * ([HOST-24]).
 *
 * Draws the dotted-decimal split point, octet width, and failure builder from [Ipv4];
 * never imports [Ipv4Whatwg].
 */
internal object Ipv4Rfc3986 {
    /** The exact RFC 3986 `IPv4address` grammar of §7.3.2 ([HOST-24]). */
    internal fun parse(input: String): ParseResult<Host.Ipv4> {
        val parts = input.split(Ipv4.DOT)
        val octets = if (parts.size == Ipv4.IPV4_OCTET_COUNT) parts.map { decOctetOrNull(it) } else null
        return when {
            octets == null || octets.any { it == Ipv4.INVALID_PART } -> Ipv4.fail(input, HostError.Ipv4NonNumeric)
            else -> ParseResult.Ok(Host.Ipv4(packOctets(octets.map { it.toInt() })))
        }
    }

    /**
     * Parses one RFC 3986 `dec-octet`: 1–3 ASCII digits, value `0..255`, with no
     * leading zero beyond a lone `0`. Returns [Ipv4.INVALID_PART] when any rule fails.
     */
    private fun decOctetOrNull(part: String): Long {
        val digitsOk = part.length in 1..MAX_OCTET_DIGITS && part.all { it.isAsciiDigit() }
        val noLeadingZero = part.length == 1 || part.firstOrNull() != '0'
        val value = if (digitsOk && noLeadingZero) part.toInt() else Ipv4.MAX_OCTET + 1
        return if (value <= Ipv4.MAX_OCTET) value.toLong() else Ipv4.INVALID_PART
    }

    /** Packs exactly four octets (high to low) into a single 32-bit [Int]. */
    private fun packOctets(octets: List<Int>): Int {
        require(octets.size == Ipv4.IPV4_OCTET_COUNT) { "expected four octets, got ${octets.size}" }
        return octets.fold(0) { acc, octet -> (acc shl Ipv4.BITS_PER_OCTET) or octet }
    }
}
