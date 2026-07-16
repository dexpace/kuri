/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.percent

import org.dexpace.kuri.text.NON_ASCII_MIN
import org.dexpace.kuri.text.hasPercentHexPairAt
import org.dexpace.kuri.text.isSurrogatePairAt
import org.dexpace.kuri.text.percentByteAt
import org.dexpace.kuri.text.toPercentEncodedByte

/** Mask isolating the low eight bits of an `Int`, turning a signed `Byte` into an octet `0..255`. */
private const val BYTE_MASK: Int = 0xFF

/** Length of a percent-encoded triplet, `%XX` (SPEC §5). */
private const val TRIPLET_LENGTH: Int = 3

/** U+FFFD, substituted for a lone surrogate that cannot be UTF-8 encoded ([PCT-21]). */
private const val REPLACEMENT_CHARACTER: String = "\uFFFD"

/**
 * Percent-encoding ([encode], SPEC §5.2) and percent-decoding ([decode], SPEC §5.3).
 *
 * Stateless and total: encode never throws (every code point either passes through identity or is
 * UTF-8 percent-encoded, [PCT-21]) and decode never throws (malformed `%` sequences are left
 * literal, [PCT-23]). UTF-8 is the only charset; multi-octet code points become consecutive
 * triplets ([PCT-18]) and triplet runs are gathered before being interpreted as text ([PCT-25]).
 */
@Suppress("TooManyFunctions") // Encode, lenient decode, and the RFC 3987 §3.2 display decode form one
// cohesive codec; each helper stays small to honour the 60-line cap. Precedent: Idna, UriParser.
internal object PercentCodec {
    /**
     * Percent-encodes [input] under [set], emitting uppercase triplets ([PCT-17], [PCT-19]).
     *
     * Each code point selected by the set is UTF-8 encoded and every resulting octet is emitted as
     * a `%XX` triplet; everything else passes through unchanged ([PCT-3]). Provides the
     * zero-allocation fast path of [PCT-20]: when nothing requires encoding the input itself is
     * returned. A `%` is only encoded when the set lists it (the component set, [PCT-40]); existing
     * triplets in input governed by other sets pass through verbatim ([PCT-32]). Lone surrogates
     * that cannot be UTF-8 encoded become the encoding of U+FFFD ([PCT-21]).
     *
     * @param input arbitrary text, processed by Unicode code point (surrogate pairs handled whole).
     * @param set the percent-encode set governing which code points become triplets.
     * @param spaceAsPlus when `true`, U+0020 is emitted as `+` rather than `%20`; the form
     *   serializer's behaviour only ([PCT-14]) and never enabled for generic components.
     * @return the encoded string, or [input] unchanged when no code point needs encoding.
     */
    internal fun encode(
        input: String,
        set: PercentEncodeSet,
        spaceAsPlus: Boolean = false,
    ): String {
        val firstEncoded = firstEncodeIndex(input, set)
        if (firstEncoded < 0) return input
        check(firstEncoded < input.length) { "first encode index out of bounds: $firstEncoded" }
        val out = StringBuilder(input.length)
        out.appendRange(input, 0, firstEncoded)
        var i = firstEncoded
        while (i < input.length) {
            val pair = isSurrogatePairAt(input, i)
            val step = if (pair) 2 else 1
            // A surrogate pair is supplementary and always non-ASCII, so it always encodes.
            if (pair || set.shouldEncode(input[i].code)) {
                appendEncoded(out, input, i, step, spaceAsPlus)
            } else {
                out.appendRange(input, i, i + step)
            }
            i += step
        }
        return out.toString()
    }

    /**
     * Percent-decodes [input] leniently ([PCT-22]–[PCT-28]).
     *
     * A `%` followed by two hex digits decodes (case-insensitively, [PCT-24]) to one octet; any
     * other `%` is left literal ([PCT-23]). Consecutive triplets are gathered into one octet run
     * and decoded as UTF-8, with invalid sequences mapped to U+FFFD ([PCT-25], [PCT-26]); literal
     * text never splits a triplet run, so a partial multi-octet run yields the same replacement a
     * standalone decode would. Plain decode keeps `+` literal ([PCT-28]).
     *
     * @param input text that may contain percent-encoded triplets.
     * @param plusAsSpace when `true`, a literal `+` decodes to U+0020; the form dialect only.
     * @return the decoded string; never throws, even on malformed escapes.
     */
    internal fun decode(
        input: String,
        plusAsSpace: Boolean = false,
    ): String {
        val out = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            val c = input[i]
            i =
                when {
                    c == '%' && hasPercentHexPairAt(input, i) -> appendTripletRun(out, input, i)
                    plusAsSpace && c == '+' -> appendLiteral(out, ' ', i)
                    else -> appendLiteral(out, c, i)
                }
        }
        return out.toString()
    }

    /**
     * Decodes only the *non-ASCII* percent-encoded runs of [input], for the RFC 3987 §3.2 display
     * transform; every other run and code unit is preserved literally.
     *
     * A decode run is a maximal run of *consecutive* non-ASCII triplets (octet `>= 0x80`), decoded to
     * text ONLY when those octets form valid UTF-8 (re-encoding the decoded text reproduces the
     * original octets, so a genuinely-encoded U+FFFD survives); an invalid-UTF-8 run stays literal.
     * An ASCII triplet (`%2F`, `%20`, `%41`)
     * delimits runs and is preserved literally on its own, so reserved delimiters and structure
     * survive intact — `%20%C3%BC` yields `%20` followed by the decoded character, not a wholly
     * literal run. Total: it never throws and the result is never longer than [input].
     *
     * @param input text that may contain percent-encoded triplets.
     * @return [input] with its non-ASCII UTF-8 triplet runs decoded to text; other runs kept literal.
     */
    internal fun decodeNonAscii(input: String): String {
        val out = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            val c = input[i]
            i =
                when {
                    c == '%' && hasPercentHexPairAt(input, i) -> appendNonAsciiRun(out, input, i)
                    else -> appendLiteral(out, c, i)
                }
        }
        check(out.length <= input.length) { "display decode never grows the input" }
        return out.toString()
    }

    /**
     * Decodes the maximal run of *consecutive non-ASCII* triplets starting at [start] to text when
     * that run is valid UTF-8; otherwise appends the run's literal triplets `input[start, end)`
     * verbatim. An ASCII triplet at [start] ends no run: it is emitted literally on its own so it
     * delimits adjacent non-ASCII runs ([PCT-25] gathering, applied display-only). Returns the index
     * just past what was emitted.
     */
    private fun appendNonAsciiRun(
        out: StringBuilder,
        input: String,
        start: Int,
    ): Int {
        require(input[start] == '%') { "run must start at a percent sign" }
        var end = start
        while (end < input.length && isNonAsciiTripletAt(input, end)) {
            end += TRIPLET_LENGTH
        }
        if (end == start) {
            // The triplet at start is an ASCII octet; it delimits runs and is preserved on its own.
            out.appendRange(input, start, start + TRIPLET_LENGTH)
            return start + TRIPLET_LENGTH
        }
        val bytes = tripletRunBytes(input, start, end)
        val decoded = bytes.decodeToString()
        // A run decodes only when it is well-formed UTF-8: re-encoding the decoded text must
        // reproduce the exact octets. This preserves a genuinely-encoded U+FFFD (which round-trips)
        // instead of misreading it as the replacement char that decodeToString emits for bad input.
        if (decoded.encodeToByteArray().contentEquals(bytes)) {
            out.append(decoded)
        } else {
            out.appendRange(input, start, end)
        }
        return end
    }

    /** True when a non-ASCII triplet (`%HH` with octet `>= 0x80`) begins at [i]; [i] must be in bounds. */
    private fun isNonAsciiTripletAt(
        input: String,
        i: Int,
    ): Boolean = input[i] == '%' && hasPercentHexPairAt(input, i) && percentByteAt(input, i) >= NON_ASCII_MIN

    /**
     * Returns the index of the first code unit that requires encoding under [set], or `-1` when
     * none does (the fast-path signal of [PCT-20]).
     *
     * Surrogate halves have a code above U+007F, so each is reported as needing encoding; this is
     * correct because every supplementary or lone-surrogate code point always encodes.
     */
    private fun firstEncodeIndex(
        input: String,
        set: PercentEncodeSet,
    ): Int {
        var i = 0
        var found = -1
        while (i < input.length && found < 0) {
            if (set.shouldEncode(input[i].code)) found = i
            i++
        }
        return found
    }

    /** Appends the [step]-unit code point at [i] as `+` (form space) or as UTF-8 triplets. */
    private fun appendEncoded(
        out: StringBuilder,
        input: String,
        i: Int,
        step: Int,
        spaceAsPlus: Boolean,
    ) {
        require(step == 1 || step == 2) { "code-point step must be 1 or 2: $step" }
        if (spaceAsPlus && step == 1 && input[i] == ' ') {
            out.append('+')
        } else {
            // A lone surrogate (step == 1 yet a surrogate half) cannot be UTF-8 encoded; per
            // [PCT-21] it becomes U+FFFD rather than the platform default of '?'.
            val source =
                if (step == 1 && input[i].isSurrogate()) {
                    REPLACEMENT_CHARACTER
                } else {
                    input.substring(i, i + step)
                }
            val bytes = source.encodeToByteArray()
            check(bytes.isNotEmpty()) { "UTF-8 encoding of a code point cannot be empty" }
            for (b in bytes) {
                out.append((b.toInt() and BYTE_MASK).toPercentEncodedByte())
            }
        }
    }

    /** Appends a single literal code unit and returns the next scan position. */
    private fun appendLiteral(
        out: StringBuilder,
        c: Char,
        i: Int,
    ): Int {
        out.append(c)
        return i + 1
    }

    /**
     * Decodes the maximal run of triplets starting at [start], appends the UTF-8 text it denotes,
     * and returns the index just past the run ([PCT-25]).
     */
    private fun appendTripletRun(
        out: StringBuilder,
        input: String,
        start: Int,
    ): Int {
        require(input[start] == '%') { "triplet run must start at a percent sign" }
        var end = start
        while (end < input.length && input[end] == '%' && hasPercentHexPairAt(input, end)) {
            end += TRIPLET_LENGTH
        }
        out.append(tripletRunBytes(input, start, end).decodeToString())
        return end
    }

    /**
     * Materializes the triplet run `input[start, end)` into one decoded octet per `%HH` triplet
     * ([PCT-25] gathering); [start]..[end] MUST span a whole, non-empty number of triplets. Shared by
     * the lenient decode and RFC 3987 display-decode run appenders, whose surrounding logic differs.
     */
    private fun tripletRunBytes(
        input: String,
        start: Int,
        end: Int,
    ): ByteArray {
        val count = (end - start) / TRIPLET_LENGTH
        check(count >= 1) { "a triplet run must contain at least one triplet" }
        val bytes = ByteArray(count)
        var source = start
        var target = 0
        while (target < count) {
            bytes[target] = percentByteAt(input, source).toByte()
            source += TRIPLET_LENGTH
            target++
        }
        return bytes
    }

    /**
     * Escapes a literal `%` to `%25` before [input] reaches [encode] under a set that does not
     * itself reserve `%` — e.g. [PercentEncodeSets.USERINFO], used by the userinfo `username`/
     * `password` builder setters ([PercentEncodeSets.COMPONENT] is the wider set that does reserve
     * `%`, per SPEC §5.1.3). Left un-escaped, a literal `%` would pass through [encode] untouched
     * and could then be misread as (or collide with) a percent-encoded escape when the stored value
     * is later decoded — the same hazard `addPathSegment` avoids by encoding under the wider
     * [PercentEncodeSets.COMPONENT] set instead.
     *
     * This does not double-encode: [encode] only re-visits code points [PercentEncodeSet.shouldEncode]
     * selects, and neither [PercentEncodeSets.USERINFO] nor [PercentEncodeSets.FRAGMENT] selects `%`,
     * so the `%25` this function inserts passes through a subsequent [encode] call unchanged.
     *
     * @param input decoded text that may contain a literal `%`.
     * @return [input] with every literal `%` replaced by `%25`.
     */
    internal fun escapeLiteralPercent(input: String): String = input.replace("%", "%25")
}
