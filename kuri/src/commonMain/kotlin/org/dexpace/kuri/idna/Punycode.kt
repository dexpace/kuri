/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.idna

import org.dexpace.kuri.text.HIGH_SURROGATE_START
import org.dexpace.kuri.text.MAX_CODE_POINT
import org.dexpace.kuri.text.appendCodePoint
import org.dexpace.kuri.text.codePointsOf
import org.dexpace.kuri.text.isAsciiAlphanumeric

/** Radix of the Bootstring generalized variable-length integers (RFC 3492 §5). */
private const val BASE: Int = 36

/** Lower clamp on a Bootstring digit threshold `t` (RFC 3492 §5). */
private const val TMIN: Int = 1

/** Upper clamp on a Bootstring digit threshold `t` (RFC 3492 §5). */
private const val TMAX: Int = 26

/** Bias-adaptation skew constant (RFC 3492 §6.1). */
private const val SKEW: Int = 38

/** Bias-adaptation damping applied on the first delta only (RFC 3492 §6.1). */
private const val DAMP: Int = 700

/** Initial bias used by both the encoder and decoder (RFC 3492 §5). */
private const val INITIAL_BIAS: Int = 72

/** Initial code point `n`; all values below it are "basic" ASCII (RFC 3492 §5). */
private const val INITIAL_N: Int = 0x80

/** Number of letter digits `a`..`z` before the digit run `0`..`9` begins. */
private const val LETTER_DIGIT_COUNT: Int = 26

/** Delimiter separating the basic-code-point prefix from the encoded suffix. */
private const val DELIMITER: Char = '-'

/** Last UTF-16 surrogate code unit (`U+DFFF`); the surrogate block is `U+D800..U+DFFF`. */
private const val LAST_SURROGATE: Int = 0xDFFF

/** Fixed bound on the digits of one generalized integer (RFC 3492 has no natural cap). */
private const val MAX_DIGIT_ITERATIONS: Int = 64

/** Largest weight `k` the bounded digit loops may reach before declaring overflow. */
private const val MAX_K: Int = BASE * MAX_DIGIT_ITERATIONS

/** Bias-adaptation rescaling threshold, `((BASE - TMIN) * TMAX) / 2` (RFC 3492 §6.1). */
private const val ADAPT_THRESHOLD: Int = ((BASE - TMIN) * TMAX) / 2

/**
 * RFC 3492 Punycode (Bootstring) codec for single IDNA labels (SPEC §7.4, [HOST-26]).
 *
 * Both directions operate on a single label *without* the `xn--` ACE prefix; the caller
 * adds or strips that prefix. [encode] returns ASCII-only labels unchanged (they need no
 * ACE form, matching UTS-46 ToASCII), and only emits an encoded suffix for labels that
 * carry a non-basic code point. [decode] implements the `IgnoreInvalidPunycode = false`
 * behaviour: any malformed digit, non-ASCII basic code point, or arithmetic overflow
 * yields `null` rather than a best-effort result.
 *
 * Encoding and decoding are pure transforms over Unicode code points (surrogate pairs are
 * combined on the way in and re-split on the way out); no shared mutable state is held.
 */
@Suppress("TooManyFunctions") // RFC 3492 is one cohesive algorithm; the helpers keep each
// step small (adapt, threshold, digit<->code-point, surrogate split) per the 60-line cap.
internal object Punycode {
    /**
     * Encodes [input] (a single label's content) to its Punycode form without the `xn--`
     * prefix, or returns `null` if the label is so oversized that encoding would overflow
     * a 32-bit accumulator (RFC 3492 §6.4).
     *
     * An input with no non-basic code point is returned unchanged: an all-ASCII label has
     * no ACE form, and RFC 3492's raw encoding would otherwise append a spurious trailing
     * delimiter.
     */
    fun encode(input: String): String? {
        val codePoints = codePointsOf(input)
        if (codePoints.none { it >= INITIAL_N }) {
            return input
        }
        val output = StringBuilder()
        var basicCount = 0
        for (codePoint in codePoints) {
            if (codePoint < INITIAL_N) {
                output.append(codePoint.toChar())
                basicCount++
            }
        }
        if (basicCount > 0) {
            output.append(DELIMITER)
        }
        return encodeSuffix(codePoints, output, basicCount)
    }

    /**
     * Decodes a Punycode label [input] (without the `xn--` prefix) back to its Unicode
     * code points, or returns `null` on any malformed input (bad digit, non-ASCII basic
     * code point, truncated integer, or overflow) per `IgnoreInvalidPunycode = false`.
     */
    fun decode(input: String): String? {
        val basics = decodeBasics(input) ?: return null
        val codePoints = basics.codePoints.toMutableList()
        var pos = basics.nextPos
        var n = INITIAL_N
        var i = 0
        var bias = INITIAL_BIAS
        var ok = true
        while (pos < input.length && ok) {
            val oldI = i
            val step = decodeInteger(input, pos, bias, i)
            if (step == null) {
                ok = false
            } else {
                i = step.value
                pos = step.nextPos
                val outLen = codePoints.size + 1
                bias = adapt(i - oldI, outLen, oldI == 0)
                val next = n.toLong() + i / outLen
                // Reject out-of-range and lone-surrogate scalars: an unpaired surrogate is not a
                // valid Unicode code point, so admitting it would yield malformed UTF-16 output
                // (`IgnoreInvalidPunycode = false`, matching the okhttp reference decoder).
                ok = next <= MAX_CODE_POINT && next.toInt() !in HIGH_SURROGATE_START..LAST_SURROGATE
                if (ok) {
                    codePoints.add(i % outLen, next.toInt())
                    n = next.toInt()
                    i = i % outLen + 1
                }
            }
        }
        return if (ok) buildString { codePoints.forEach { appendCodePoint(this, it) } } else null
    }

    /**
     * Runs the RFC 3492 §6.3 main loop, appending the encoded suffix to [output] for each
     * non-basic code point in ascending value order. Returns the finished string, or
     * `null` if `delta` would overflow a 32-bit accumulator.
     */
    private fun encodeSuffix(
        codePoints: List<Int>,
        output: StringBuilder,
        basicCount: Int,
    ): String? {
        require(basicCount in 0..codePoints.size) { "basicCount out of range: $basicCount" }
        var n = INITIAL_N
        var delta = 0L
        var bias = INITIAL_BIAS
        var handled = basicCount
        var ok = true
        while (handled < codePoints.size && ok) {
            // Invariant: exactly `handled` code points are < n, so one >= n must remain.
            val m = requireNotNull(codePoints.filter { it >= n }.minOrNull()) { "no code point >= $n" }
            delta += (m - n).toLong() * (handled + 1)
            ok = delta <= Int.MAX_VALUE
            if (ok) {
                n = m
                val seed = EncodeState(delta.toInt(), bias, handled, basicCount, ok = true)
                val state = encodeForN(codePoints, output, n, seed)
                delta = state.delta.toLong() + 1
                bias = state.bias
                handled = state.handled
                ok = state.ok
                n++
            }
        }
        return if (ok) output.toString() else null
    }

    /**
     * Processes one `n`-pass over [codePoints]: increments `delta` for each code point
     * below `n`, and emits a generalized integer for each equal to `n`, updating the bias.
     */
    private fun encodeForN(
        codePoints: List<Int>,
        output: StringBuilder,
        n: Int,
        stateIn: EncodeState,
    ): EncodeState {
        var delta = stateIn.delta
        var bias = stateIn.bias
        var handled = stateIn.handled
        var ok = true
        for (codePoint in codePoints) {
            when {
                !ok -> Unit
                codePoint < n -> if (delta == Int.MAX_VALUE) ok = false else delta++
                codePoint == n -> {
                    encodeInteger(output, delta, bias)
                    bias = adapt(delta, handled + 1, handled == stateIn.basicCount)
                    delta = 0
                    handled++
                }
            }
        }
        return EncodeState(delta, bias, handled, stateIn.basicCount, ok)
    }

    /** Emits [deltaQ] as a Bootstring generalized variable-length integer (RFC 3492 §6.3). */
    private fun encodeInteger(
        output: StringBuilder,
        deltaQ: Int,
        bias: Int,
    ) {
        require(deltaQ >= 0) { "delta must be non-negative: $deltaQ" }
        var q = deltaQ
        var terminated = false
        for (k in BASE..MAX_K step BASE) {
            val t = threshold(k, bias)
            if (q < t) {
                terminated = true
                break
            }
            val remainder = (q - t) % (BASE - t)
            output.append(digitToCodePoint(t + remainder))
            q = (q - t) / (BASE - t)
        }
        check(terminated) { "generalized integer did not terminate" }
        output.append(digitToCodePoint(q))
    }

    /**
     * Consumes the basic-code-point prefix of [input] up to its last delimiter, validating
     * every prefix character is ASCII. Returns the basics and the suffix start index, or
     * `null` if a non-ASCII code point appears before the delimiter.
     */
    private fun decodeBasics(input: String): Basics? {
        val lastDelimiter = input.lastIndexOf(DELIMITER)
        if (lastDelimiter < 0) {
            return Basics(emptyList(), 0)
        }
        val basics = ArrayList<Int>(lastDelimiter)
        var ok = true
        for (index in 0 until lastDelimiter) {
            val c = input[index]
            if (c.code >= INITIAL_N) {
                ok = false
                break
            }
            basics.add(c.code)
        }
        return if (ok) Basics(basics, lastDelimiter + 1) else null
    }

    /**
     * Decodes one generalized variable-length integer from [input] starting at [start],
     * folding it into [acc]. Returns the new accumulator and cursor, or `null` on a bad
     * digit, a truncated integer, or arithmetic overflow (RFC 3492 §6.2).
     */
    private fun decodeInteger(
        input: String,
        start: Int,
        bias: Int,
        acc: Int,
    ): Decoded? {
        var i = acc
        var w = 1
        var pos = start
        var k = BASE
        var ok = true
        var terminated = false
        // Termination and every failure are expressed through the loop guard, so the body
        // carries no break/continue (detekt LoopWithTooManyJumpStatements allows one jump).
        while (k <= MAX_K && ok && !terminated) {
            val digit = if (pos < input.length) codePointToDigit(input[pos]) else -1
            val t = threshold(k, bias)
            val next = if (digit >= 0) i.toLong() + digit.toLong() * w else Long.MAX_VALUE
            // The first arm rejects overflow, so the `next.toInt()` casts below are safe.
            when {
                digit < 0 || next > Int.MAX_VALUE -> ok = false
                digit < t -> {
                    i = next.toInt()
                    pos++
                    terminated = true
                }
                w.toLong() * (BASE - t) > Int.MAX_VALUE -> ok = false
                else -> {
                    i = next.toInt()
                    pos++
                    w *= (BASE - t)
                    k += BASE
                }
            }
        }
        return if (ok && terminated) Decoded(i, pos) else null
    }

    /** Recomputes the bias from the most recent delta (RFC 3492 §6.1). */
    private fun adapt(
        delta: Int,
        numPoints: Int,
        firstTime: Boolean,
    ): Int {
        require(numPoints > 0) { "numPoints must be positive: $numPoints" }
        var scaled = if (firstTime) delta / DAMP else delta / 2
        scaled += scaled / numPoints
        var k = 0
        while (scaled > ADAPT_THRESHOLD && k < MAX_K) {
            scaled /= (BASE - TMIN)
            k += BASE
        }
        val numerator = (BASE - TMIN + 1) * scaled
        val term = numerator / (scaled + SKEW)
        return k + term
    }

    /** Clamps the digit threshold `t` for weight [k] under [bias] to `TMIN..TMAX`. */
    private fun threshold(
        k: Int,
        bias: Int,
    ): Int {
        val t =
            when {
                k <= bias -> TMIN
                k >= bias + TMAX -> TMAX
                else -> k - bias
            }
        check(t in TMIN..TMAX) { "threshold out of range: $t" }
        return t
    }

    /** Maps a Bootstring digit `0..35` to its ASCII code point (`a`..`z`, then `0`..`9`). */
    private fun digitToCodePoint(digit: Int): Char {
        require(digit in 0 until BASE) { "digit out of range: $digit" }
        val c = if (digit < LETTER_DIGIT_COUNT) 'a' + digit else '0' + (digit - LETTER_DIGIT_COUNT)
        check(c.isAsciiAlphanumeric()) { "non-alphanumeric punycode digit: $c" }
        return c
    }

    /** Maps an ASCII code point to its Bootstring digit `0..35`, or `-1` if it is not one. */
    private fun codePointToDigit(c: Char): Int =
        when (c) {
            in 'a'..'z' -> c - 'a'
            in 'A'..'Z' -> c - 'A'
            in '0'..'9' -> c - '0' + LETTER_DIGIT_COUNT
            else -> -1
        }

    /**
     * Immutable carrier for the encoder's per-`n`-pass state (RFC 3492 §6.3). [basicCount]
     * is threaded through so the `first` flag of [adapt] (`handled == basicCount`) survives
     * across passes; [ok] is `false` once a `delta` increment would overflow.
     */
    private class EncodeState(
        val delta: Int,
        val bias: Int,
        val handled: Int,
        val basicCount: Int,
        val ok: Boolean,
    )

    /** Result of decoding one generalized integer: the accumulator and the new cursor. */
    private class Decoded(
        val value: Int,
        val nextPos: Int,
    )

    /** Decoded basic-code-point prefix and the index where the encoded suffix begins. */
    private class Basics(
        val codePoints: List<Int>,
        val nextPos: Int,
    )
}
