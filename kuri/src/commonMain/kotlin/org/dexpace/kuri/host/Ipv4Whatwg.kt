/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.host

import org.dexpace.kuri.error.HostError
import org.dexpace.kuri.error.ParseResult
import org.dexpace.kuri.text.hexDigitToInt

/** Maximum dotted parts the `Url` IPv4 number parser accepts (§7.3.1 [HOST-21]). */
private const val MAX_PARTS: Int = 4

/** Bit width of the whole IPv4 address. */
private const val ADDRESS_BITS: Int = 32

/** Decimal radix for plain `dec-octet` parts and zero-padded-free numbers. */
private const val RADIX_DECIMAL: Int = 10

/** Octal radix selected by a leading `0` with at least one further digit. */
private const val RADIX_OCTAL: Int = 8

/** Hexadecimal radix selected by a `0x`/`0X` part prefix. */
private const val RADIX_HEX: Int = 16

/** Length of the `0x`/`0X` prefix stripped from a hexadecimal part before its digits ([HOST-21] step 3). */
private const val HEX_PREFIX_LENGTH: Int = 2

/** Length of the single leading `0` stripped from an octal part before its digits ([HOST-21] step 3). */
private const val OCTAL_PREFIX_LENGTH: Int = 1

/** Exclusive upper bound of a 32-bit address (`2^32`); also the per-part overflow cap. */
private const val UINT32_LIMIT: Long = 1L shl 32

/**
 * The `Url`-profile IPv4 parser: the WHATWG number parser of §7.3.1 ([HOST-21],
 * [HOST-22]) — 1–4 dotted parts, per-part radix detection, width-aware overflow.
 *
 * Draws the dotted-decimal split point, octet width, and failure builder from [Ipv4];
 * never imports [Ipv4Rfc3986].
 */
internal object Ipv4Whatwg {
    /**
     * More than [MAX_PARTS] parts is an over-wide address that cannot fit 32 bits, so it
     * is classified as [HostError.Ipv4Overflow] ([HOST-21] step 2); an empty or
     * non-numeric part instead fails as [HostError.Ipv4NonNumeric].
     */
    internal fun parse(input: String): ParseResult<Host.Ipv4> {
        val parts = Ipv4.stripSingleTrailingDot(input).split(Ipv4.DOT)
        if (parts.size > MAX_PARTS) return Ipv4.fail(input, HostError.Ipv4Overflow)
        val numbers = parseNumericParts(parts)
        return if (numbers == null) Ipv4.fail(input, HostError.Ipv4NonNumeric) else assemble(input, numbers)
    }

    /**
     * Parses each part in its detected radix once the part count is known valid;
     * `null` signals an empty part or a digit outside its radix ([HOST-21] step 3).
     */
    private fun parseNumericParts(parts: List<String>): List<Long>? {
        val numbers = if (parts.none { it.isEmpty() }) parts.map { parsePart(it) } else null
        return numbers?.takeIf { values -> values.none { it == Ipv4.INVALID_PART } }
    }

    /** Parses one `Url`-profile part, detecting radix per [HOST-21] step 3. */
    private fun parsePart(part: String): Long {
        require(part.isNotEmpty()) { "empty part reached parsePart" }
        val hex = Ipv4.isHexPrefixed(part)
        val octal = !hex && part.length > 1 && part[0] == '0'
        // One decision selects both the radix and the prefix length to strip; a decimal part carries
        // offset 0 so it reuses `part` verbatim, materializing no substring on the common path.
        val radix: Int
        val prefixLength: Int
        when {
            hex -> {
                radix = RADIX_HEX
                prefixLength = HEX_PREFIX_LENGTH
            }
            octal -> {
                radix = RADIX_OCTAL
                prefixLength = OCTAL_PREFIX_LENGTH
            }
            else -> {
                radix = RADIX_DECIMAL
                prefixLength = 0
            }
        }
        val digits = if (prefixLength == 0) part else part.substring(prefixLength)
        return parseInRadix(digits, radix)
    }

    /**
     * Accumulates [digits] in [radix], capping at [UINT32_LIMIT] so an overflowing
     * part still fails the later width check instead of wrapping; returns
     * [Ipv4.INVALID_PART] on a digit outside the radix. Empty [digits] (the `0x` case)
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
            acc = if (d in 0 until radix) minOf(acc * radix + d, UINT32_LIMIT) else Ipv4.INVALID_PART
            if (acc == Ipv4.INVALID_PART) break
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
        val finalMax = 1L shl (ADDRESS_BITS - Ipv4.BITS_PER_OCTET * (n - 1))
        val headOk = numbers.dropLast(1).all { it <= Ipv4.MAX_OCTET }
        val tailOk = numbers.last() < finalMax
        return if (headOk && tailOk) {
            ParseResult.Ok(Host.Ipv4(combine(numbers).toInt()))
        } else {
            Ipv4.fail(input, HostError.Ipv4Overflow)
        }
    }

    /** Folds the validated [numbers] into a single 32-bit value (final part absorbs the low bits). */
    private fun combine(numbers: List<Long>): Long {
        require(numbers.isNotEmpty()) { "no octets to combine" }
        return numbers.dropLast(1).foldIndexed(numbers.last()) { i, acc, value ->
            acc + (value shl (Ipv4.BITS_PER_OCTET * (Ipv4.HIGH_OCTET_INDEX - i)))
        }
    }
}
