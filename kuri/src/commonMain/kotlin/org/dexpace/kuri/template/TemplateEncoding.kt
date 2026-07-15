/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.template

import org.dexpace.kuri.text.NON_ASCII_MIN
import org.dexpace.kuri.text.isUnreserved
import org.dexpace.kuri.text.toPercentEncodedByte

/*
 * RFC 6570 §3.2.1 percent-encoding, split out from TemplateExpansion.kt purely to stay under the
 * file's function-count limit. Reuses the shared ASCII/percent-triplet primitives from
 * org.dexpace.kuri.text (the same ones the host/percent/query engines key off) rather than
 * redefining hex-nibble encoding here.
 */

/** Length of a percent-encoded triplet, `%XX`. */
private const val PERCENT_TRIPLET_LENGTH: Int = 3

/**
 * Percent-encodes [value] to the RFC 6570 allow-set: unreserved characters always pass; when
 * [allowReserved] (the `+`/`#` operators and literals), reserved characters and existing `%HH` triplets
 * also pass; everything else is UTF-8 percent-encoded.
 */
internal fun pctEncode(
    value: String,
    allowReserved: Boolean,
): String {
    val sb = StringBuilder(value.length)
    var i = 0
    while (i < value.length) {
        val c = value[i]
        i = if (c.code < NON_ASCII_MIN) appendAscii(sb, value, i, allowReserved) else appendNonAscii(sb, value, i)
    }
    return sb.toString()
}

/** Appends one ASCII character, encoding it unless allowed; returns the next index. */
private fun appendAscii(
    sb: StringBuilder,
    value: String,
    i: Int,
    allowReserved: Boolean,
): Int {
    val c = value[i]
    return when {
        c.isUnreserved() || (allowReserved && isReserved(c)) -> {
            sb.append(c)
            i + 1
        }
        allowReserved && c == '%' && isPercentTriplet(value, i) -> {
            sb.append(c).append(value[i + 1]).append(value[i + 2])
            i + PERCENT_TRIPLET_LENGTH
        }
        else -> {
            sb.append(c.code.toPercentEncodedByte())
            i + 1
        }
    }
}

/** True when a literal `%HH` triplet (two hex digits) already sits at [i] in [value]. */
private fun isPercentTriplet(
    value: String,
    i: Int,
): Boolean = i + 2 < value.length && isHex(value[i + 1]) && isHex(value[i + 2])

/** Appends one non-ASCII code point (1–2 UTF-16 chars) as its UTF-8 percent-encoding. */
private fun appendNonAscii(
    sb: StringBuilder,
    value: String,
    i: Int,
): Int {
    val c = value[i]
    val end = if (c.isHighSurrogate() && i + 1 < value.length && value[i + 1].isLowSurrogate()) i + 2 else i + 1
    val bytes = value.substring(i, end).encodeToByteArray()
    for (byte in bytes) sb.append((byte.toInt() and BYTE_MASK).toPercentEncodedByte())
    return end
}

/** Mask isolating the low eight bits of an `Int`, turning a signed `Byte` into an octet `0..255`. */
private const val BYTE_MASK: Int = 0xFF

private fun isReserved(c: Char): Boolean = c in ":/?#[]@!$&'()*+,;="

/** Takes the first [n] Unicode code points, never splitting a surrogate pair. */
internal fun String.takeCodePoints(n: Int): String {
    if (n <= 0) return ""
    var count = 0
    var i = 0
    while (i < length && count < n) {
        val c = this[i]
        i += if (c.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) 2 else 1
        count++
    }
    return substring(0, i)
}
