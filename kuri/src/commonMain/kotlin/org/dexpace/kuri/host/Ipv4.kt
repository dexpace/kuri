/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.host

import org.dexpace.kuri.error.HostError
import org.dexpace.kuri.error.ParseResult
import org.dexpace.kuri.error.UriParseError
import org.dexpace.kuri.text.isAsciiDigit
import org.dexpace.kuri.text.isAsciiHexDigit

/** String form of [Ipv4.DOT] used to join the four serialized octets. */
private const val SEPARATOR: String = "."

/**
 * Unpacks a packed 32-bit IPv4 address into its four octets, most-significant first.
 *
 * The single owner of the packed-[Int] unpacking: both [Ipv4.serialize] (the textual
 * view) and [Host.Ipv4.octets] (the numeric view) delegate here, so the two views of
 * one address cannot drift apart. The postconditions are asserted once on behalf of both.
 *
 * @param value the address as 32 unsigned bits packed into a signed [Int].
 * @return a fresh four-element array of octets, high-order octet first, each `0..255`.
 */
internal fun ipv4Octets(value: Int): IntArray {
    val octets =
        IntArray(Ipv4.IPV4_OCTET_COUNT) { index ->
            (value ushr (Ipv4.BITS_PER_OCTET * (Ipv4.HIGH_OCTET_INDEX - index))) and Ipv4.MAX_OCTET
        }
    check(octets.size == Ipv4.IPV4_OCTET_COUNT) { "expected ${Ipv4.IPV4_OCTET_COUNT} octets, got ${octets.size}" }
    check(octets.all { it in 0..Ipv4.MAX_OCTET }) { "octet out of range in ${octets.toList()}" }
    return octets
}

/**
 * Shared IPv4 plumbing for SPEC §7.3: the WHATWG "ends in a number" domain classifier,
 * canonical dotted-decimal serialization, and the predicates/failure builder the two
 * parse postures both draw on.
 *
 * The actual parsing lives in two sibling objects — [Ipv4Rfc3986] for the exact RFC 3986
 * `IPv4address` grammar (§7.3.2) and [Ipv4Whatwg] for the WHATWG number parser (§7.3.1) —
 * each calling back into this object for the pieces they share, so neither half ever
 * imports the other. Both parsers yield a [Host.Ipv4] holding the address as 32 unsigned
 * bits packed into a signed [Int]; the original radix is intentionally discarded and never
 * round-tripped — [serialize] always emits canonical dotted-decimal (§7.3.3 [HOST-25]).
 */
internal object Ipv4 {
    /** Label separator and serialization joiner for dotted-quad addresses; shared by both parse halves. */
    internal const val DOT: Char = '.'

    /** The fixed octet count of an IPv4 address: four 8-bit groups; shared by [Ipv4Rfc3986]. */
    internal const val IPV4_OCTET_COUNT: Int = 4

    /** Largest value a single octet may hold, also the low-byte mask (`0xFF`); shared by both parse halves. */
    internal const val MAX_OCTET: Int = 0xFF

    /** Bit width of one octet; the shift between adjacent octets in the packed value; shared by both parse halves. */
    internal const val BITS_PER_OCTET: Int = 8

    /** Index of the most-significant octet, i.e. `IPV4_OCTET_COUNT - 1`; shared by [Ipv4Whatwg]. */
    internal const val HIGH_OCTET_INDEX: Int = 3

    /** Sentinel returned by a per-part parser for an unparsable part; shared by both parse halves. */
    internal const val INVALID_PART: Long = -1L

    /**
     * The WHATWG "ends in a number" decision that classifies a host as IPv4 vs a
     * registered name (§7.3.1 [HOST-19]).
     *
     * A single trailing `.` is dropped first; the **last label** (text after the
     * final `.`, or the whole string) is a number when it is a non-empty run of
     * ASCII decimal digits, or a `0x`/`0X` prefix optionally followed by hex
     * digits. Anything else (including an empty result) is not a number.
     *
     * @param host the host string, already lowercased in the `Url` pipeline.
     * @return `true` iff [host] should be parsed as an IPv4 address.
     */
    internal fun endsInANumber(host: String): Boolean {
        val lastLabel = stripSingleTrailingDot(host).substringAfterLast(DOT)
        return lastLabel.isNotEmpty() && (isDecimalLabel(lastLabel) || isHexLabel(lastLabel))
    }

    /**
     * Serializes a 32-bit address into canonical dotted-decimal form (§7.3.3
     * [HOST-25]): the four octets, most-significant first, base-10, joined by `.`.
     *
     * @param value the address as 32 unsigned bits packed into a signed [Int].
     * @return the canonical `a.b.c.d` text, e.g. `192.168.0.1`.
     */
    internal fun serialize(value: Int): String = ipv4Octets(value).joinToString(SEPARATOR)

    /**
     * Removes a single trailing `.` ([HOST-19]/[HOST-21] step 1); other dots are kept.
     *
     * Called both by [endsInANumber] and, directly, by [Ipv4Whatwg.parse] ([HOST-21] step 1).
     */
    internal fun stripSingleTrailingDot(host: String): String = if (host.endsWith(DOT)) host.dropLast(1) else host

    /** True when [label] is a non-empty run of ASCII decimal digits. */
    private fun isDecimalLabel(label: String): Boolean = label.all { it.isAsciiDigit() }

    /**
     * True when [label] begins with the two-character `0x`/`0X` hex prefix.
     *
     * Called both by [isHexLabel] and, directly, by [Ipv4Whatwg]'s per-part radix detection.
     */
    internal fun isHexPrefixed(label: String): Boolean =
        label.length >= 2 && label[0] == '0' && (label[1] == 'x' || label[1] == 'X')

    /** True when [label] is `0x`/`0X` followed by zero or more ASCII hex digits. */
    private fun isHexLabel(label: String): Boolean = isHexPrefixed(label) && label.drop(2).all { it.isAsciiHexDigit() }

    /**
     * Builds the fatal-host failure carrying the offending [host] text and [reason].
     *
     * The single failure constructor for both [Ipv4Rfc3986.parse] and [Ipv4Whatwg.parse].
     */
    internal fun fail(
        host: String,
        reason: HostError,
    ): ParseResult<Host.Ipv4> = ParseResult.Err(UriParseError.InvalidHost(host, reason))
}
