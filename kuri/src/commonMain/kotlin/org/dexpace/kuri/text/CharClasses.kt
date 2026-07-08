/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */

// The shared ASCII grammar / percent-triplet predicates the parser, host, percent, scheme, and
// serialize engines all key off; grouping them here keeps a single definition per rule (hence the
// per-file function count legitimately exceeds detekt's heuristic).
@file:Suppress("TooManyFunctions")

package org.dexpace.kuri.text

/** Hexadecimal alphabet used to emit percent-encoded triplets; uppercase per [PCT-19]. */
private const val HEX_DIGITS: String = "0123456789ABCDEF"

/** Decimal value of a hexadecimal letter `a`/`A` once its base is subtracted (`A` == 10). */
private const val HEX_LETTER_OFFSET: Int = 10

/** Largest nibble value, i.e. the result of decoding a single hex digit (`F` == 15). */
private const val MAX_HEX_VALUE: Int = 15

/** Largest octet value an [Int.toPercentEncodedByte] input may carry (`0xFF`). */
private const val MAX_BYTE_VALUE: Int = 0xFF

/** Bit width of one hex nibble; shifting an octet right by this isolates its high nibble. */
private const val HEX_SHIFT: Int = 4

/** Mask selecting the low four bits of an octet (`0xF`). */
private const val LOW_NIBBLE_MASK: Int = 0xF

/** Inclusive upper bound of the C0 control range, `U+001F` (SPEC §2.1). */
private const val C0_CONTROL_MAX: Char = '\u001F'

/** ASCII distance from an upper-case letter to its lower-case counterpart (`'a' - 'A'`, 32). */
private const val ASCII_CASE_OFFSET: Int = 'a' - 'A'

/** RFC 3986 `unreserved` symbol characters beyond ALPHA/DIGIT (`ALPHA / DIGIT / "-._~"`). */
private const val UNRESERVED_SYMBOLS: String = "-._~"

/** First non-ASCII code point (`U+0080`); a code unit below this is ASCII (SPEC §2.1). */
internal const val NON_ASCII_MIN: Int = 0x80

/**
 * True when this char is an ASCII alpha, `A`–`Z` or `a`–`z` (SPEC §2.1, [TERM-1]).
 *
 * Classification is by exact numeric range, independent of locale or Unicode
 * case-folding, as the spec requires.
 */
internal fun Char.isAsciiAlpha(): Boolean = this in 'A'..'Z' || this in 'a'..'z'

/** True when this char is an ASCII digit, `0`–`9` (SPEC §2.1, [TERM-1]). */
internal fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

/**
 * True when this char is an ASCII hex digit, `0`–`9` / `A`–`F` / `a`–`f` (SPEC §2.1).
 *
 * Both letter cases are accepted: percent-encoding hexadecimal is case-insensitive on
 * input in both profiles ([GRAM-6]).
 */
internal fun Char.isAsciiHexDigit(): Boolean = isAsciiDigit() || this in 'A'..'F' || this in 'a'..'f'

/** True when this char is an ASCII alphanumeric: an alpha or a digit (SPEC §2.1). */
internal fun Char.isAsciiAlphanumeric(): Boolean = isAsciiAlpha() || isAsciiDigit()

/** True when this char is an RFC 3986 `unreserved` code point: an ASCII alphanumeric or `-` `.` `_` `~`. */
internal fun Char.isUnreserved(): Boolean = isAsciiAlphanumeric() || this in UNRESERVED_SYMBOLS

/**
 * Lower-cases a single ASCII letter, leaving every other code point untouched (SPEC §6.3).
 *
 * The mapping is by exact numeric range, so it is locale-invariant: no Unicode case folding and no
 * Turkish dotless-`i` mapping can occur. Callers relying on this to match a spec's ASCII-lowercase
 * step must ensure the input is ASCII where the two would otherwise diverge.
 */
internal fun Char.asciiLowercased(): Char = if (this in 'A'..'Z') this + ASCII_CASE_OFFSET else this

/** True when every UTF-16 code unit of this sequence is ASCII (`< U+0080`). */
internal fun CharSequence.isAllAscii(): Boolean = all { it.code < NON_ASCII_MIN }

/** True when this char is a C0 control, `U+0000`–`U+001F` (SPEC §2.1 / §4.2.1). */
internal fun Char.isC0Control(): Boolean = this <= C0_CONTROL_MAX

/**
 * True when this char is a C0 control or space, i.e. `<= U+0020` (SPEC §4.2.1, [GRAM-9]).
 *
 * Governs the leading/trailing trim performed only in the `Url` profile; the caller is
 * responsible for not applying it under the `Uri` profile.
 */
internal fun Char.isC0ControlOrSpace(): Boolean = this <= ' '

/**
 * Decodes a single hex digit to its value `0..15`, or returns `-1` when [c] is not a hex
 * digit (SPEC §2.1; case-insensitive per [GRAM-6]).
 *
 * Returning a sentinel rather than throwing lets percent-decoders treat a malformed
 * triplet as an ordinary value at the call site instead of via exceptions.
 *
 * @param c the candidate hex digit.
 * @return the decoded value in `0..15`, or `-1` if [c] is not `0`–`9`/`A`–`F`/`a`–`f`.
 */
internal fun hexDigitToInt(c: Char): Int {
    val value =
        when (c) {
            in '0'..'9' -> c - '0'
            in 'A'..'F' -> c - 'A' + HEX_LETTER_OFFSET
            in 'a'..'f' -> c - 'a' + HEX_LETTER_OFFSET
            else -> -1
        }
    check(value in -1..MAX_HEX_VALUE) { "decoded hex value out of range: $value" }
    return value
}

/**
 * True when a `pct-encoded` triplet's two hex digits sit in bounds at [index]+1 and [index]+2 of
 * [text] (SPEC §2.1; [GRAM-6]).
 *
 * Tests only the two trailing HEXDIG and their bounds; the `%` at [index] itself is the caller's
 * precondition, so this composes with a `text[index] == '%'` guard at the call site.
 *
 * @param text the sequence being scanned.
 * @param index the index of the `%` introducing the candidate triplet.
 * @return `true` iff [index]+2 is in bounds and both following code units are ASCII hex digits.
 */
internal fun hasPercentHexPairAt(
    text: CharSequence,
    index: Int,
): Boolean = index + 2 < text.length && text[index + 1].isAsciiHexDigit() && text[index + 2].isAsciiHexDigit()

/**
 * Decodes the two hex digits following the `%` at [index] into one octet `0..255` (SPEC §5.3).
 *
 * The caller MUST have verified a valid triplet (e.g. via [hasPercentHexPairAt]). This re-checks
 * that precondition on the two decoded nibbles — free, since [hexDigitToInt] runs regardless — so a
 * misuse fails loudly instead of silently yielding a garbage octet from a `-1` nibble.
 *
 * @param text the sequence holding the triplet.
 * @param index the index of the `%`; the hex digits are read at [index]+1 and [index]+2.
 * @return the decoded octet in `0..255`.
 * @throws IllegalArgumentException if the code unit at [index]+1 or [index]+2 is not an ASCII hex digit.
 */
internal fun percentByteAt(
    text: CharSequence,
    index: Int,
): Int {
    val high = hexDigitToInt(text[index + 1])
    val low = hexDigitToInt(text[index + 2])
    require(high >= 0 && low >= 0) { "percentByteAt requires a verified hex pair at index $index" }
    return (high shl HEX_SHIFT) or low
}

/**
 * Encodes a single octet `0..255` as an uppercase percent-encoded triplet `"%XX"` (SPEC
 * §5; uppercase output mandated by [PCT-19]).
 *
 * @receiver the octet to encode; MUST be in `0..255`.
 * @return the three-character triplet, e.g. `255` becomes `"%FF"`.
 */
internal fun Int.toPercentEncodedByte(): String {
    require(this in 0..MAX_BYTE_VALUE) { "octet out of range: $this" }
    val high = HEX_DIGITS[this ushr HEX_SHIFT]
    val low = HEX_DIGITS[this and LOW_NIBBLE_MASK]
    return "%$high$low"
}
