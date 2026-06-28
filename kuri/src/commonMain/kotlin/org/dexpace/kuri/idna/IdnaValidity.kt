/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.idna

/** Record separator inside a decoded range blob (never appears inside a record). */
private const val RECORD_SEPARATOR: Char = '\n'

/** Separator between a record's hex start and hex end. */
private const val RANGE_SEPARATOR: Char = '-'

/** Separator between a joining record's `START-END` range and its type letter. */
private const val TYPE_SEPARATOR: Char = ';'

/** Radix used to decode each record's hex bounds. */
private const val RADIX_HEX: Int = 16

/** U+200C ZERO WIDTH NON-JOINER; its ContextJ rule is RFC 5892 A.1. */
private const val ZWNJ: Int = 0x200C

/** U+200D ZERO WIDTH JOINER; its ContextJ rule is RFC 5892 A.2. */
private const val ZWJ: Int = 0x200D

/** Joining_Type Left_Joining. */
private const val JOIN_LEFT: Char = 'L'

/** Joining_Type Dual_Joining (counts as both a left- and a right-side joiner). */
private const val JOIN_DUAL: Char = 'D'

/** Joining_Type Right_Joining. */
private const val JOIN_RIGHT: Char = 'R'

/** Joining_Type Transparent; skipped when scanning for the bracketing joiners. */
private const val JOIN_TRANSPARENT: Char = 'T'

/** Sentinel returned for any code point with no tabled Joining_Type (Non_Joining / Join_Causing). */
private const val JOIN_OTHER: Char = 'U'

/** First UTF-16 high-surrogate code unit (`U+D800`). */
private const val HIGH_SURROGATE_START: Int = 0xD800

/** First UTF-16 low-surrogate code unit (`U+DC00`). */
private const val LOW_SURROGATE_START: Int = 0xDC00

/** Bit shift separating the high- and low-surrogate halves of a code point. */
private const val SURROGATE_SHIFT: Int = 10

/** First code point of the supplementary planes (needs a surrogate pair). */
private const val SUPPLEMENTARY_BASE: Int = 0x10000

/**
 * Two deferred UTS-46 label-validity rules (SPEC §7.4, [HOST-28] `CheckJoiners = true`):
 *
 *  - **leading combining mark** ([startsWithCombiningMark]) — a label whose first code point is a
 *    General_Category Mark (Mn/Mc/Me) is invalid (UTS-46 validity criterion 7).
 *  - **ContextJ join controls** ([checkJoiners]) — each U+200C / U+200D must satisfy its RFC 5892
 *    A.1 / A.2 context, otherwise the label is invalid.
 *
 * Backing range tables come from [IdnaValidityData] (Unicode 16.0.0). Predicates are pure: a
 * string in, a boolean out, with no shared mutable state.
 */
@Suppress("TooManyFunctions") // One cohesive validity check decomposed into small helpers (decode,
// binary search, code-point split, the two RFC 5892 scans) to honour the 60-line cap; mirrors Idna.
internal object IdnaValidity {
    /** A sorted set of inclusive code-point ranges with O(log n) membership. */
    private class RangeSet(
        val starts: IntArray,
        val ends: IntArray,
    ) {
        /** True when [codePoint] falls inside one of the ranges. */
        fun contains(codePoint: Int): Boolean {
            val index = floorIndex(starts, codePoint)
            return index >= 0 && codePoint <= ends[index]
        }
    }

    /** A sorted Joining_Type table: range `i` carries [types]`[i]` over `starts[i]..ends[i]`. */
    private class JoiningTable(
        val starts: IntArray,
        val ends: IntArray,
        val types: CharArray,
    ) {
        /** The Joining_Type of [codePoint], or [JOIN_OTHER] when no range covers it. */
        fun typeOf(codePoint: Int): Char {
            val index = floorIndex(starts, codePoint)
            return if (index >= 0 && codePoint <= ends[index]) types[index] else JOIN_OTHER
        }
    }

    private val markRanges: RangeSet by lazy { decodeRanges(MARK_RANGE_CHUNKS) }
    private val viramaRanges: RangeSet by lazy { decodeRanges(VIRAMA_RANGE_CHUNKS) }
    private val joiningTypes: JoiningTable by lazy { decodeJoining(JOINING_TYPE_CHUNKS) }

    /**
     * True when the first code point of [label] is a General_Category Mark (Mn/Mc/Me); such a label
     * is invalid under UTS-46 (SPEC §7.4 validity criterion 7). An empty label has no leading mark.
     *
     * @param label a single, already-mapped domain label.
     * @return `true` if the label must be rejected for starting with a combining mark.
     */
    internal fun startsWithCombiningMark(label: String): Boolean {
        if (label.isEmpty()) {
            return false
        }
        return markRanges.contains(firstCodePoint(label))
    }

    /**
     * Validates every RFC 5892 ContextJ join control in [label] (SPEC §7.4, [HOST-28]).
     *
     * Each U+200C ZWNJ is valid iff the preceding code point is a Virama, or it sits between an
     * L/D-joining char (only Transparent chars in between) and an R/D-joining char (idem). Each
     * U+200D ZWJ is valid iff the preceding code point is a Virama. All other code points pass.
     *
     * @param label a single, already-mapped domain label.
     * @return `true` when no join control violates its context (an empty label trivially passes).
     */
    internal fun checkJoiners(label: String): Boolean {
        val codePoints = codePoints(label)
        var index = 0
        var valid = true
        while (index < codePoints.size && valid) {
            valid = isJoinerContextValid(codePoints, index)
            index += 1
        }
        return valid
    }

    /** Dispatches the per-position ContextJ check; non-join-control code points are always valid. */
    private fun isJoinerContextValid(
        codePoints: IntArray,
        index: Int,
    ): Boolean =
        when (codePoints[index]) {
            ZWNJ -> isZeroWidthNonJoinerValid(codePoints, index)
            ZWJ -> hasViramaBefore(codePoints, index)
            else -> true
        }

    /** RFC 5892 A.1: a ZWNJ is valid after a Virama, or within an L/D … R/D joining context. */
    private fun isZeroWidthNonJoinerValid(
        codePoints: IntArray,
        index: Int,
    ): Boolean =
        hasViramaBefore(codePoints, index) ||
            (precedingJoinerMatches(codePoints, index) && followingJoinerMatches(codePoints, index))

    /** RFC 5892 A.1/A.2 Virama clause: true when the code point immediately before [index] is one. */
    private fun hasViramaBefore(
        codePoints: IntArray,
        index: Int,
    ): Boolean {
        require(index in codePoints.indices) { "index out of bounds: $index" }
        return index > 0 && viramaRanges.contains(codePoints[index - 1])
    }

    /** Scans left over Transparent chars; the first non-Transparent must be L- or D-joining. */
    private fun precedingJoinerMatches(
        codePoints: IntArray,
        index: Int,
    ): Boolean {
        require(index in codePoints.indices) { "index out of bounds: $index" }
        var cursor = index - 1
        while (cursor >= 0 && joiningTypes.typeOf(codePoints[cursor]) == JOIN_TRANSPARENT) {
            cursor -= 1
        }
        return cursor >= 0 && isLeftJoining(joiningTypes.typeOf(codePoints[cursor]))
    }

    /** Scans right over Transparent chars; the first non-Transparent must be R- or D-joining. */
    private fun followingJoinerMatches(
        codePoints: IntArray,
        index: Int,
    ): Boolean {
        require(index in codePoints.indices) { "index out of bounds: $index" }
        var cursor = index + 1
        while (cursor < codePoints.size && joiningTypes.typeOf(codePoints[cursor]) == JOIN_TRANSPARENT) {
            cursor += 1
        }
        return cursor < codePoints.size && isRightJoining(joiningTypes.typeOf(codePoints[cursor]))
    }

    /** True for the left side of the ZWNJ context: Left_Joining or Dual_Joining. */
    private fun isLeftJoining(type: Char): Boolean = type == JOIN_LEFT || type == JOIN_DUAL

    /** True for the right side of the ZWNJ context: Right_Joining or Dual_Joining. */
    private fun isRightJoining(type: Char): Boolean = type == JOIN_RIGHT || type == JOIN_DUAL

    /** Decodes a `<START>-<END>` range blob into a [RangeSet]. */
    private fun decodeRanges(chunks: List<String>): RangeSet {
        val records = chunks.joinToString(separator = "").split(RECORD_SEPARATOR)
        check(records.isNotEmpty()) { "range table data is empty" }
        val starts = IntArray(records.size)
        val ends = IntArray(records.size)
        records.forEachIndexed { index, record ->
            val dash = record.indexOf(RANGE_SEPARATOR)
            check(dash > 0) { "malformed range record: $record" }
            starts[index] = record.substring(0, dash).toInt(RADIX_HEX)
            ends[index] = record.substring(dash + 1).toInt(RADIX_HEX)
        }
        return RangeSet(starts, ends)
    }

    /** Decodes a `<START>-<END>;<TYPE>` blob into a [JoiningTable]. */
    private fun decodeJoining(chunks: List<String>): JoiningTable {
        val records = chunks.joinToString(separator = "").split(RECORD_SEPARATOR)
        check(records.isNotEmpty()) { "joining table data is empty" }
        val starts = IntArray(records.size)
        val ends = IntArray(records.size)
        val types = CharArray(records.size)
        records.forEachIndexed { index, record ->
            val dash = record.indexOf(RANGE_SEPARATOR)
            val semi = record.indexOf(TYPE_SEPARATOR)
            check(dash in 1 until semi) { "malformed joining record: $record" }
            starts[index] = record.substring(0, dash).toInt(RADIX_HEX)
            ends[index] = record.substring(dash + 1, semi).toInt(RADIX_HEX)
            types[index] = record[semi + 1]
        }
        return JoiningTable(starts, ends, types)
    }

    /** Greatest index whose `starts` value is `<= codePoint`, or `-1` when none is. */
    private fun floorIndex(
        starts: IntArray,
        codePoint: Int,
    ): Int {
        require(starts.isNotEmpty()) { "range table is empty" }
        var low = 0
        var high = starts.size - 1
        var result = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (starts[mid] <= codePoint) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }

    /** Splits [label] into Unicode code points, combining well-formed surrogate pairs. */
    private fun codePoints(label: String): IntArray {
        val result = ArrayList<Int>(label.length)
        var index = 0
        while (index < label.length) {
            val high = label[index]
            val low = if (index + 1 < label.length) label[index + 1] else null
            val paired = high.isHighSurrogate() && low != null && low.isLowSurrogate()
            if (paired) {
                result.add(toCodePoint(high, requireNotNull(low)))
                index += 2
            } else {
                result.add(high.code)
                index += 1
            }
        }
        return result.toIntArray()
    }

    /** The first code point of a non-empty [label], combining a leading surrogate pair. */
    private fun firstCodePoint(label: String): Int {
        require(label.isNotEmpty()) { "empty label has no first code point" }
        val high = label[0]
        val low = if (label.length > 1) label[1] else null
        val paired = high.isHighSurrogate() && low != null && low.isLowSurrogate()
        return if (paired) toCodePoint(high, requireNotNull(low)) else high.code
    }

    /** Combines a UTF-16 surrogate pair into a single supplementary-plane code point. */
    private fun toCodePoint(
        high: Char,
        low: Char,
    ): Int {
        require(high.isHighSurrogate()) { "expected high surrogate: $high" }
        require(low.isLowSurrogate()) { "expected low surrogate: $low" }
        val highBits = (high.code - HIGH_SURROGATE_START) shl SURROGATE_SHIFT
        return SUPPLEMENTARY_BASE + highBits + (low.code - LOW_SURROGATE_START)
    }
}
