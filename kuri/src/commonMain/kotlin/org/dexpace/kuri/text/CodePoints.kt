/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.text

// UTF-16 surrogate-pair primitives the Kotlin common stdlib omits (Character.toChars / codePointAt /
// charCount are JVM-only). Every helper works on Unicode scalar values so supplementary code points
// round-trip through UTF-16. Shared by the idna, percent, and query engines.

/** First code point of the supplementary planes (needs a surrogate pair). */
private const val SUPPLEMENTARY_BASE: Int = 0x10000

/** Largest code point that fits in a single UTF-16 code unit. */
private const val MAX_BMP_CODE_POINT: Int = 0xFFFF

/** Largest Unicode scalar value (U+10FFFF). */
internal const val MAX_CODE_POINT: Int = 0x10FFFF

/** First UTF-16 high-surrogate code unit (`U+D800`). */
internal const val HIGH_SURROGATE_START: Int = 0xD800

/** First UTF-16 low-surrogate code unit (`U+DC00`). */
private const val LOW_SURROGATE_START: Int = 0xDC00

/** Last UTF-16 low-surrogate code unit (`U+DFFF`), the end of the whole surrogate range. */
private const val SURROGATE_MAX: Int = 0xDFFF

/** Bit shift separating the high- and low-surrogate halves of a code point. */
private const val SURROGATE_SHIFT: Int = 10

/** Mask isolating the low-surrogate payload bits of a supplementary code point. */
private const val LOW_SURROGATE_MASK: Int = 0x3FF

/** ASCII URL code points beyond alphanumerics (SPEC Table 4.2.5-a, [GRAM-14]). */
private const val URL_CODE_POINT_ASCII_EXTRAS: String = "!$&'()*+,-./:;=?@_~"

/** Inclusive lower bound of the non-ASCII URL code point range, `U+00A0` ([GRAM-14]). */
private const val URL_CODE_POINT_NON_ASCII_MIN: Int = 0xA0

/** Inclusive upper bound of the URL code point range, `U+10FFFD` ([GRAM-14]). */
private const val URL_CODE_POINT_MAX: Int = 0x10FFFD

/** Inclusive bounds of the sole BMP noncharacter block, `U+FDD0`-`U+FDEF` ([GRAM-15]). */
private const val NONCHARACTER_BLOCK_MIN: Int = 0xFDD0
private const val NONCHARACTER_BLOCK_MAX: Int = 0xFDEF

/** Mask isolating a code point's low 16 bits; `0xFFFE`/`0xFFFF` there marks a noncharacter ([GRAM-15]). */
private const val NONCHARACTER_LOW_MASK: Int = 0xFFFE

/** Combines a UTF-16 surrogate pair into a single supplementary-plane code point. */
internal fun toCodePoint(
    high: Char,
    low: Char,
): Int {
    require(high.isHighSurrogate()) { "expected high surrogate: $high" }
    require(low.isLowSurrogate()) { "expected low surrogate: $low" }
    val highBits = (high.code - HIGH_SURROGATE_START) shl SURROGATE_SHIFT
    return SUPPLEMENTARY_BASE + highBits + (low.code - LOW_SURROGATE_START)
}

/** UTF-16 unit width of [codePoint]: `2` for supplementary, `1` otherwise. */
internal fun charCount(codePoint: Int): Int = if (codePoint >= SUPPLEMENTARY_BASE) 2 else 1

/** True when a high surrogate at [index] is immediately followed by a low surrogate in [text]. */
internal fun isSurrogatePairAt(
    text: String,
    index: Int,
): Boolean {
    val next = index + 1
    return text[index].isHighSurrogate() && next < text.length && text[next].isLowSurrogate()
}

/** The code point at [index], recomposing a high+low surrogate pair into one value. */
internal fun codePointAt(
    text: String,
    index: Int,
): Int {
    val high = text[index]
    val next = index + 1
    val paired = high.isHighSurrogate() && next < text.length && text[next].isLowSurrogate()
    return if (paired) toCodePoint(high, text[next]) else high.code
}

/** Splits [text] into Unicode code points, combining well-formed surrogate pairs. */
internal fun codePointsOf(text: String): List<Int> {
    val result = ArrayList<Int>(text.length)
    var index = 0
    while (index < text.length) {
        val high = text[index]
        val low = if (index + 1 < text.length) text[index + 1] else null
        if (high.isHighSurrogate() && low != null && low.isLowSurrogate()) {
            result.add(toCodePoint(high, low))
            index += 2
        } else {
            result.add(high.code)
            index++
        }
    }
    return result
}

/** Appends [codePoint] to [out], emitting a surrogate pair for supplementary values. */
internal fun appendCodePoint(
    out: StringBuilder,
    codePoint: Int,
) {
    require(codePoint in 0..MAX_CODE_POINT) { "code point out of range: $codePoint" }
    if (codePoint <= MAX_BMP_CODE_POINT) {
        out.append(codePoint.toChar())
    } else {
        val offset = codePoint - SUPPLEMENTARY_BASE
        out.append((HIGH_SURROGATE_START + (offset ushr SURROGATE_SHIFT)).toChar())
        out.append((LOW_SURROGATE_START + (offset and LOW_SURROGATE_MASK)).toChar())
    }
}

/**
 * True when [codePoint] is a WHATWG **noncharacter** (SPEC [GRAM-15]): `U+FDD0`-`U+FDEF`, or any
 * code point whose low 16 bits are `0xFFFE` or `0xFFFF`.
 */
internal fun isNoncharacter(codePoint: Int): Boolean {
    require(codePoint in 0..MAX_CODE_POINT) { "code point out of range: $codePoint" }
    return codePoint in NONCHARACTER_BLOCK_MIN..NONCHARACTER_BLOCK_MAX ||
        (codePoint and NONCHARACTER_LOW_MASK) == NONCHARACTER_LOW_MASK
}

/**
 * True when [codePoint] is a **URL code point** (SPEC §4.2.5, [GRAM-14]): an ASCII alphanumeric,
 * one of the Table 4.2.5-a punctuation code points, or any non-surrogate, non-noncharacter code
 * point in `U+00A0..U+10FFFD`.
 *
 * This class is advisory, not a parse gate ([GRAM-16]): a component code point outside it (and
 * not a `%` introducing a percent-escape) is a non-fatal *invalid-URL-unit* validation error, not
 * a rejection — see [org.dexpace.kuri.error.ValidationErrorKind.INVALID_URL_UNIT].
 */
internal fun isUrlCodePoint(codePoint: Int): Boolean {
    require(codePoint in 0..MAX_CODE_POINT) { "code point out of range: $codePoint" }
    return when {
        codePoint < NON_ASCII_MIN -> isAsciiUrlCodePoint(codePoint.toChar())
        codePoint < URL_CODE_POINT_NON_ASCII_MIN -> false // C1 controls, U+0080..U+009F
        codePoint in HIGH_SURROGATE_START..SURROGATE_MAX -> false
        codePoint > URL_CODE_POINT_MAX -> false
        else -> !isNoncharacter(codePoint)
    }
}

/** True when [c] is one of the ASCII URL code points (SPEC Table 4.2.5-a, [GRAM-14]). */
private fun isAsciiUrlCodePoint(c: Char): Boolean = c.isAsciiAlphanumeric() || c in URL_CODE_POINT_ASCII_EXTRAS
