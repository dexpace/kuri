/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.host

import org.dexpace.kuri.ParseProfile
import org.dexpace.kuri.error.HostError
import org.dexpace.kuri.error.ParseResult
import org.dexpace.kuri.error.UriParseError
import org.dexpace.kuri.text.hexDigitToInt
import org.dexpace.kuri.text.isAsciiDigit
import org.dexpace.kuri.text.isAsciiHexDigit

/** Label separator and serialization joiner for dotted-quad addresses. */
private const val DOT: Char = '.'

/** String form of [DOT] used to join the four serialized octets. */
private const val SEPARATOR: String = "."

/** Maximum dotted parts the `Url` IPv4 number parser accepts (§7.3.1 [HOST-21]). */
private const val MAX_PARTS: Int = 4

/** The fixed octet count of an IPv4 address: four 8-bit groups. */
private const val IPV4_OCTET_COUNT: Int = 4

/** Largest value a single octet may hold, also the low-byte mask (`0xFF`). */
private const val MAX_OCTET: Int = 0xFF

/** Most digits a decimal `dec-octet` may have (`255` is three; §7.3.2 [HOST-24]). */
private const val MAX_OCTET_DIGITS: Int = 3

/** Bit width of one octet; the shift between adjacent octets in the packed value. */
private const val BITS_PER_OCTET: Int = 8

/** Bit width of the whole IPv4 address. */
private const val ADDRESS_BITS: Int = 32

/** Index of the most-significant octet, i.e. `IPV4_OCTET_COUNT - 1`. */
private const val HIGH_OCTET_INDEX: Int = 3

/** Decimal radix for plain `dec-octet` parts and zero-padded-free numbers. */
private const val RADIX_DECIMAL: Int = 10

/** Octal radix selected by a leading `0` with at least one further digit. */
private const val RADIX_OCTAL: Int = 8

/** Hexadecimal radix selected by a `0x`/`0X` part prefix. */
private const val RADIX_HEX: Int = 16

/** Sentinel returned by [parsePart]/[decOctetOrNull] for an unparsable part. */
private const val INVALID_PART: Long = -1L

/** Exclusive upper bound of a 32-bit address (`2^32`); also the per-part overflow cap. */
private const val UINT32_LIMIT: Long = 1L shl 32

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
        IntArray(IPV4_OCTET_COUNT) { index ->
            (value ushr (BITS_PER_OCTET * (HIGH_OCTET_INDEX - index))) and MAX_OCTET
        }
    check(octets.size == IPV4_OCTET_COUNT) { "expected $IPV4_OCTET_COUNT octets, got ${octets.size}" }
    check(octets.all { it in 0..MAX_OCTET }) { "octet out of range in ${octets.toList()}" }
    return octets
}

/**
 * Parser and serializer for the IPv4 host form (SPEC §7.3).
 *
 * Two parse postures share one type: the `Url` profile runs the WHATWG number
 * parser (1–4 parts, per-part radix detection, width-aware overflow) per §7.3.1,
 * while the `Uri` profile accepts only the exact RFC 3986 dotted-decimal
 * `IPv4address` per §7.3.2. Both yield a [Host.Ipv4] holding the address as 32
 * unsigned bits packed into a signed [Int]; the original radix is intentionally
 * discarded and never round-tripped — [serialize] always emits canonical
 * dotted-decimal (§7.3.3 [HOST-25]).
 *
 * The WHATWG number parser, the RFC 3986 grammar, and the canonical serializer are each
 * multi-step procedures ([HOST-19]..[HOST-25]); they are decomposed here into
 * single-purpose helpers (each well under the line/return/complexity budgets), which is
 * the intent of the "small functions" rule and legitimately exceeds the per-object
 * function count.
 */
@Suppress("TooManyFunctions")
internal object Ipv4 {
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
     * Parses [input] as an IPv4 address under the given [profile] (§7.3).
     *
     * In the `Url` profile this is the WHATWG number parser; in the `Uri` profile
     * it is the exact RFC 3986 `IPv4address` grammar. A rejected input is returned
     * as [ParseResult.Err] with a [HostError] cause rather than thrown ([ERR-1]).
     *
     * @param input the host text (no surrounding brackets); the caller decides,
     *   via [endsInANumber] in the `Url` profile, that this is IPv4-shaped.
     * @param profile selects the WHATWG vs RFC 3986 acceptance rules.
     * @return the parsed [Host.Ipv4], or an [HostError]-tagged failure.
     */
    internal fun parse(
        input: String,
        profile: ParseProfile,
    ): ParseResult<Host.Ipv4> = if (profile.isWhatwg) parseWhatwg(input) else parseRfc3986(input)

    /**
     * Serializes a 32-bit address into canonical dotted-decimal form (§7.3.3
     * [HOST-25]): the four octets, most-significant first, base-10, joined by `.`.
     *
     * @param value the address as 32 unsigned bits packed into a signed [Int].
     * @return the canonical `a.b.c.d` text, e.g. `192.168.0.1`.
     */
    internal fun serialize(value: Int): String = ipv4Octets(value).joinToString(SEPARATOR)

    /**
     * The WHATWG IPv4 number parser of §7.3.1 ([HOST-21], [HOST-22]).
     *
     * More than [MAX_PARTS] parts is an over-wide address that cannot fit 32 bits, so it
     * is classified as [HostError.Ipv4Overflow] ([HOST-21] step 2); an empty or
     * non-numeric part instead fails as [HostError.Ipv4NonNumeric].
     */
    private fun parseWhatwg(input: String): ParseResult<Host.Ipv4> {
        val parts = stripSingleTrailingDot(input).split(DOT)
        if (parts.size > MAX_PARTS) return fail(input, HostError.Ipv4Overflow)
        val numbers = parseNumericParts(parts)
        return if (numbers == null) fail(input, HostError.Ipv4NonNumeric) else assemble(input, numbers)
    }

    /**
     * Parses each part in its detected radix once the part count is known valid;
     * `null` signals an empty part or a digit outside its radix ([HOST-21] step 3).
     */
    private fun parseNumericParts(parts: List<String>): List<Long>? {
        val numbers = if (parts.none { it.isEmpty() }) parts.map { parsePart(it) } else null
        return numbers?.takeIf { values -> values.none { it == INVALID_PART } }
    }

    /** Parses one `Url`-profile part, detecting radix per [HOST-21] step 3. */
    private fun parsePart(part: String): Long {
        require(part.isNotEmpty()) { "empty part reached parsePart" }
        val hex = isHexPrefixed(part)
        val octal = !hex && part.length > 1 && part[0] == '0'
        val radix =
            if (hex) {
                RADIX_HEX
            } else if (octal) {
                RADIX_OCTAL
            } else {
                RADIX_DECIMAL
            }
        val digits =
            if (hex) {
                part.substring(2)
            } else if (octal) {
                part.substring(1)
            } else {
                part
            }
        return parseInRadix(digits, radix)
    }

    /**
     * Accumulates [digits] in [radix], capping at [UINT32_LIMIT] so an overflowing
     * part still fails the later width check instead of wrapping; returns
     * [INVALID_PART] on a digit outside the radix. Empty [digits] (the `0x` case)
     * denotes zero.
     */
    private fun parseInRadix(
        digits: String,
        radix: Int,
    ): Long {
        require(radix == RADIX_DECIMAL || radix == RADIX_OCTAL || radix == RADIX_HEX) { "bad radix $radix" }
        var acc = 0L
        for (ch in digits) {
            val d = hexDigitToInt(ch)
            acc = if (d in 0 until radix) minOf(acc * radix + d, UINT32_LIMIT) else INVALID_PART
            if (acc == INVALID_PART) break
        }
        return acc
    }

    /** Applies width-aware overflow ([HOST-22]) and packs the parts into 32 bits. */
    private fun assemble(
        input: String,
        numbers: List<Long>,
    ): ParseResult<Host.Ipv4> {
        val n = numbers.size
        require(n in 1..MAX_PARTS) { "part count out of range: $n" }
        val finalMax = 1L shl (ADDRESS_BITS - BITS_PER_OCTET * (n - 1))
        val headOk = numbers.dropLast(1).all { it <= MAX_OCTET }
        val tailOk = numbers.last() < finalMax
        return if (headOk && tailOk) {
            ParseResult.Ok(Host.Ipv4(combine(numbers).toInt()))
        } else {
            fail(input, HostError.Ipv4Overflow)
        }
    }

    /** Folds the validated [numbers] into a single 32-bit value (final part absorbs the low bits). */
    private fun combine(numbers: List<Long>): Long {
        require(numbers.isNotEmpty()) { "no octets to combine" }
        return numbers.dropLast(1).foldIndexed(numbers.last()) { i, acc, value ->
            acc + (value shl (BITS_PER_OCTET * (HIGH_OCTET_INDEX - i)))
        }
    }

    /** The exact RFC 3986 `IPv4address` grammar of §7.3.2 ([HOST-24]). */
    private fun parseRfc3986(input: String): ParseResult<Host.Ipv4> {
        val parts = input.split(DOT)
        val octets = if (parts.size == IPV4_OCTET_COUNT) parts.map { decOctetOrNull(it) } else null
        return when {
            octets == null || octets.any { it == INVALID_PART } -> fail(input, HostError.Ipv4NonNumeric)
            else -> ParseResult.Ok(Host.Ipv4(packOctets(octets.map { it.toInt() })))
        }
    }

    /**
     * Parses one RFC 3986 `dec-octet`: 1–3 ASCII digits, value `0..255`, with no
     * leading zero beyond a lone `0`. Returns [INVALID_PART] when any rule fails.
     */
    private fun decOctetOrNull(part: String): Long {
        val digitsOk = part.length in 1..MAX_OCTET_DIGITS && part.all { it.isAsciiDigit() }
        val noLeadingZero = part.length == 1 || part.firstOrNull() != '0'
        val value = if (digitsOk && noLeadingZero) part.toInt() else MAX_OCTET + 1
        return if (value <= MAX_OCTET) value.toLong() else INVALID_PART
    }

    /** Packs exactly four octets (high to low) into a single 32-bit [Int]. */
    private fun packOctets(octets: List<Int>): Int {
        require(octets.size == IPV4_OCTET_COUNT) { "expected four octets, got ${octets.size}" }
        return octets.fold(0) { acc, octet -> (acc shl BITS_PER_OCTET) or octet }
    }

    /** Removes a single trailing `.` ([HOST-19]/[HOST-21] step 1); other dots are kept. */
    private fun stripSingleTrailingDot(host: String): String = if (host.endsWith(DOT)) host.dropLast(1) else host

    /** True when [label] is a non-empty run of ASCII decimal digits. */
    private fun isDecimalLabel(label: String): Boolean = label.all { it.isAsciiDigit() }

    /** True when [label] begins with the two-character `0x`/`0X` hex prefix. */
    private fun isHexPrefixed(label: String): Boolean =
        label.length >= 2 && label[0] == '0' && (label[1] == 'x' || label[1] == 'X')

    /** True when [label] is `0x`/`0X` followed by zero or more ASCII hex digits. */
    private fun isHexLabel(label: String): Boolean = isHexPrefixed(label) && label.drop(2).all { it.isAsciiHexDigit() }

    /** Builds the fatal-host failure carrying the offending [host] text and [reason]. */
    private fun fail(
        host: String,
        reason: HostError,
    ): ParseResult<Host.Ipv4> = ParseResult.Err(UriParseError.InvalidHost(host, reason))
}
