/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.host

import org.dexpace.kuri.error.HostError
import org.dexpace.kuri.error.ParseResult
import org.dexpace.kuri.error.UriParseError
import org.dexpace.kuri.text.hexDigitToInt
import org.dexpace.kuri.text.isAsciiDigit
import org.dexpace.kuri.text.isAsciiHexDigit

/** Number of 16-bit pieces in an IPv6 address (§7.2). */
private const val PIECE_COUNT: Int = 8

/** Index of the last 16-bit piece, where a trailing zero run compresses to `::`. */
private const val LAST_PIECE_INDEX: Int = 7

/** Highest piece index from which an embedded-IPv4 trailer still fits ([HOST-12]). */
private const val MAX_EMBEDDED_PIECE_INDEX: Int = 6

/** A hex piece is at most four hex digits wide ([HOST-10]). */
private const val MAX_HEX_DIGITS: Int = 4

/** Radix for a hex piece digit (`0x10`). */
private const val HEX_RADIX: Int = 16

/** Radix for an embedded-IPv4 decimal octet. */
private const val DECIMAL_RADIX: Int = 10

/** Multiplier that packs a second octet beside the first within one 16-bit piece. */
private const val OCTET_RADIX: Int = 256

/** Largest value an embedded-IPv4 octet may hold ([HOST-13]). */
private const val MAX_OCTET: Int = 255

/** Largest value a 16-bit piece may hold (`0xFFFF`). */
private const val MAX_PIECE_VALUE: Int = 65535

/** Exact number of dotted-decimal octets an embedded-IPv4 trailer must carry. */
private const val IPV4_OCTET_COUNT: Int = 4

/** Sentinel for "no compression marker set" ([HOST-6]); `0` is a valid marker. */
private const val UNSET_COMPRESS: Int = -1

/** Sentinel for "no octet/piece value accumulated yet". */
private const val NO_VALUE: Int = -1

/**
 * The IPv6 literal parser and RFC 5952 serializer (SPEC §7.2).
 *
 * Both operate on the *bracket-free* address: the parser consumes the contents of
 * an `[...]` literal (the brackets and any zone id are handled by the host pipeline,
 * §7.2.2), and the serializer emits the canonical address without brackets. Zone
 * ids are not handled here — a stray `%` is rejected as a malformed literal, which
 * realises the default-off [HOST-17] behaviour.
 */
internal object Ipv6 {
    /**
     * Parses the bracket-free contents of an IPv6 literal into eight 16-bit pieces
     * ([HOST-6]..[HOST-14]). Honours a single `::` compression and an optional
     * embedded-IPv4 trailer in the low 32 bits; rejects every other deviation.
     *
     * @param input the literal contents with the surrounding `[`/`]` already removed.
     * @return [ParseResult.Ok] holding a fully expanded eight-piece address, or
     *   [ParseResult.Err] carrying [HostError.Ipv6Malformed] on any violation.
     */
    fun parse(input: String): ParseResult<Host.Ipv6> = Scanner(input).run()

    /**
     * Serializes eight 16-bit pieces to their RFC 5952 canonical form ([HOST-15],
     * [HOST-16]): lowercase hex, suppressed leading zeros, and the single longest
     * run of two or more zero pieces (leftmost on ties) collapsed to `::`.
     *
     * @param pieces exactly eight values, each in `0..0xFFFF`.
     * @return the canonical bracket-free address text.
     */
    fun serialize(pieces: List<Int>): String {
        require(pieces.size == PIECE_COUNT) { "ipv6 needs $PIECE_COUNT pieces, got ${pieces.size}" }
        require(pieces.all { it in 0..MAX_PIECE_VALUE }) { "ipv6 piece out of range: $pieces" }
        return assemble(pieces, compressStartIndex(pieces))
    }

    /**
     * Returns the start index of the single longest zero run of length ≥ 2, leftmost
     * on ties, or [UNSET_COMPRESS] when no such run exists ([HOST-15]). A run of one
     * zero never qualifies, so a lone zero is rendered literally.
     */
    private fun compressStartIndex(pieces: List<Int>): Int {
        var bestStart = UNSET_COMPRESS
        var bestLength = 1
        var index = 0
        while (index < PIECE_COUNT) {
            val runLength = zeroRunLength(pieces, index)
            if (runLength > bestLength) {
                bestLength = runLength
                bestStart = index
            }
            index += if (runLength > 0) runLength else 1
        }
        return bestStart
    }

    /** Counts consecutive zero pieces starting at [start], or `0` when that piece is non-zero. */
    private fun zeroRunLength(
        pieces: List<Int>,
        start: Int,
    ): Int {
        var end = start
        while (end < PIECE_COUNT && pieces[end] == 0) {
            end++
        }
        return end - start
    }

    /** Joins the pieces, substituting `::` for the zero run beginning at [compress] ([HOST-16]). */
    private fun assemble(
        pieces: List<Int>,
        compress: Int,
    ): String {
        val output = StringBuilder()
        var skipZeros = false
        var index = 0
        while (index < PIECE_COUNT) {
            skipZeros = appendPiece(output, pieces, index, compress, skipZeros)
            index++
        }
        return output.toString()
    }

    /**
     * Emits piece [index] (or the `::` separator at the compression start) and returns
     * whether subsequent zero pieces should be elided. Mirrors the WHATWG serializer's
     * `ignore0` flag so the compressed run prints exactly once.
     */
    private fun appendPiece(
        out: StringBuilder,
        pieces: List<Int>,
        index: Int,
        compress: Int,
        skipZeros: Boolean,
    ): Boolean =
        when {
            skipZeros && pieces[index] == 0 -> true
            index == compress -> {
                out.append(if (index == 0) "::" else ":")
                true
            }
            else -> {
                out.append(pieces[index].toString(HEX_RADIX))
                if (index != LAST_PIECE_INDEX) out.append(':')
                false
            }
        }

    /**
     * Single-pass mutable cursor over the literal. State is local to one [run]; failures
     * are recorded in [failure] rather than thrown so each step stays branch-light and
     * the public surface returns a value ([ERR-1]).
     *
     * The WHATWG IPv6 grammar ([HOST-6]..[HOST-14]) is one ~30-step procedure; it is
     * decomposed here into single-purpose steps (each well under the line/return/
     * complexity budgets), which is the intent of the "small functions" rule and
     * legitimately exceeds the per-class function count.
     */
    @Suppress("TooManyFunctions")
    private class Scanner(
        private val input: String,
    ) {
        private val pieces: IntArray = IntArray(PIECE_COUNT)
        private var cursor: Int = 0
        private var pieceIndex: Int = 0
        private var compress: Int = UNSET_COMPRESS
        private var failure: UriParseError? = null

        fun run(): ParseResult<Host.Ipv6> {
            scan()
            finish()
            val error = failure
            return if (error == null) ParseResult.Ok(Host.Ipv6(pieces.toList())) else ParseResult.Err(error)
        }

        /** Drives the leading-compression check then the per-group scan ([HOST-7]..[HOST-10]). */
        private fun scan() {
            if (input.isEmpty()) {
                fail()
                return
            }
            consumeLeadingCompression()
            while (cursor < input.length && failure == null) {
                scanGroup()
            }
        }

        /** A leading `:` MUST be the first colon of a `::`; a lone leading `:` fails ([HOST-8]). */
        private fun consumeLeadingCompression() {
            check(input.isNotEmpty()) { "leading scan on empty input" }
            if (input[cursor] == ':') {
                if (cursor + 1 < input.length && input[cursor + 1] == ':') {
                    cursor += 2
                    compress = pieceIndex
                } else {
                    fail()
                }
            }
        }

        /** Dispatches one group: too-many guard, inner compression, or a hex piece ([HOST-6]/[HOST-9]). */
        private fun scanGroup() {
            check(pieceIndex <= PIECE_COUNT) { "piece index overran: $pieceIndex" }
            when {
                pieceIndex == PIECE_COUNT -> fail()
                input[cursor] == ':' -> scanInnerCompression()
                else -> scanHexGroup()
            }
        }

        /** An interior `:` sets the compression marker once; a second `::` is fatal ([HOST-9]). */
        private fun scanInnerCompression() {
            if (compress == UNSET_COMPRESS) {
                compress = pieceIndex
                cursor++
            } else {
                fail()
            }
        }

        /** Reads up to four hex digits, then branches to embedded-IPv4 or piece finalisation. */
        private fun scanHexGroup() {
            var value = 0
            var length = 0
            while (length < MAX_HEX_DIGITS && cursor < input.length && input[cursor].isAsciiHexDigit()) {
                value = value * HEX_RADIX + hexDigitToInt(input[cursor])
                cursor++
                length++
            }
            when {
                cursor < input.length && input[cursor] == '.' -> scanEmbeddedIpv4(length)
                else -> finishHexGroup(value, length)
            }
        }

        /** Stores a completed piece, requiring a `:` separator (with a successor) or end-of-input ([HOST-10]). */
        private fun finishHexGroup(
            value: Int,
            length: Int,
        ) {
            when {
                length == 0 -> fail()
                cursor >= input.length -> storePiece(value)
                input[cursor] == ':' -> consumeSeparator(value)
                else -> fail()
            }
        }

        /** Stores the piece, consumes the separating `:`, and rejects a trailing colon ([HOST-10]). */
        private fun consumeSeparator(value: Int) {
            storePiece(value)
            cursor++
            if (cursor >= input.length) fail()
        }

        /** Writes [value] at the current index and advances it ([HOST-10]). */
        private fun storePiece(value: Int) {
            check(pieceIndex < PIECE_COUNT) { "store past last piece: $pieceIndex" }
            check(value in 0..MAX_PIECE_VALUE) { "piece value out of range: $value" }
            pieces[pieceIndex] = value
            pieceIndex++
        }

        /** Validates the switch into embedded-IPv4 mode, then reads the dotted quad ([HOST-12]). */
        private fun scanEmbeddedIpv4(hexLength: Int) {
            when {
                hexLength == 0 -> fail()
                pieceIndex > MAX_EMBEDDED_PIECE_INDEX -> fail()
                else -> readEmbeddedOctets(hexLength)
            }
        }

        /** Rewinds over the partial hex piece and folds exactly four octets into two pieces ([HOST-12]/[HOST-13]). */
        private fun readEmbeddedOctets(hexLength: Int) {
            check(cursor >= hexLength) { "cannot rewind $hexLength from $cursor" }
            cursor -= hexLength
            var numbersSeen = 0
            while (cursor < input.length && failure == null) {
                numbersSeen = readOctet(numbersSeen)
            }
            if (failure == null && numbersSeen != IPV4_OCTET_COUNT) fail()
        }

        /** Consumes one octet (with its leading `.` for all but the first) and packs it ([HOST-13]). */
        private fun readOctet(numbersSeen: Int): Int {
            consumeOctetSeparator(numbersSeen)
            val octet = if (failure == null) readOctetValue() else NO_VALUE
            return if (failure == null) packOctet(octet, numbersSeen) else numbersSeen
        }

        /** Requires a single `.` before every octet after the first, rejecting `..` and excess octets. */
        private fun consumeOctetSeparator(numbersSeen: Int) {
            if (numbersSeen > 0) {
                if (input[cursor] == '.' && numbersSeen < IPV4_OCTET_COUNT) cursor++ else fail()
            }
        }

        /** Reads a non-empty decimal octet, rejecting a non-digit start ([HOST-13]). */
        private fun readOctetValue(): Int {
            var octet = NO_VALUE
            if (cursor < input.length && input[cursor].isAsciiDigit()) {
                octet = accumulateOctet()
            } else {
                fail()
            }
            return octet
        }

        /** Accumulates octet digits, rejecting a forbidden leading zero or a value above 255 ([HOST-13]). */
        private fun accumulateOctet(): Int {
            var octet = NO_VALUE
            while (cursor < input.length && input[cursor].isAsciiDigit() && failure == null) {
                octet = foldOctetDigit(octet, input[cursor] - '0')
                if (failure == null) cursor++
            }
            return octet
        }

        /** Folds one digit into [current], failing on a zero-padded (`01`) or out-of-range octet. */
        private fun foldOctetDigit(
            current: Int,
            digit: Int,
        ): Int {
            check(digit in 0..DECIMAL_RADIX - 1) { "non-decimal digit: $digit" }
            val next =
                when {
                    current == NO_VALUE -> digit
                    current == 0 -> {
                        fail()
                        NO_VALUE
                    }
                    else -> current * DECIMAL_RADIX + digit
                }
            if (next > MAX_OCTET) fail()
            return next
        }

        /** Packs [octet] into the current piece and advances the index after the 2nd and 4th ([HOST-12]). */
        private fun packOctet(
            octet: Int,
            numbersSeen: Int,
        ): Int {
            check(octet in 0..MAX_OCTET) { "octet out of range: $octet" }
            check(pieceIndex < PIECE_COUNT) { "embedded octet past last piece: $pieceIndex" }
            pieces[pieceIndex] = pieces[pieceIndex] * OCTET_RADIX + octet
            val seen = numbersSeen + 1
            if (seen == 2 || seen == IPV4_OCTET_COUNT) pieceIndex++
            return seen
        }

        /** Expands compression or enforces the exact-eight rule once scanning ends ([HOST-14]). */
        private fun finish() {
            when {
                failure != null -> Unit
                compress != UNSET_COMPRESS -> relocate()
                pieceIndex != PIECE_COUNT -> fail()
                else -> Unit
            }
        }

        /** Slides the written pieces to the high end and zero-fills the compressed gap ([HOST-14]). */
        private fun relocate() {
            check(compress in 0..PIECE_COUNT) { "bad compress marker: $compress" }
            var swaps = pieceIndex - compress
            check(swaps >= 0) { "negative swap count: $swaps" }
            var index = LAST_PIECE_INDEX
            while (index != 0 && swaps > 0) {
                val temp = pieces[index]
                pieces[index] = pieces[compress + swaps - 1]
                pieces[compress + swaps - 1] = temp
                index--
                swaps--
            }
        }

        /** Records the first malformed-literal failure; later failures keep the earliest cause ([ERR-2]). */
        private fun fail() {
            if (failure == null) failure = UriParseError.InvalidHost(input, HostError.Ipv6Malformed)
        }
    }
}
