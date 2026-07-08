/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.idna

import org.dexpace.kuri.text.toCodePoint

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

// RFC 5893 Bidi_Class record codes: each multi-letter class the rule consults is folded to one
// char by the generator (see codegen's bidiClassCode) and decoded back here. The codes are
// L=Left, R=Right, A=Arabic_Letter (AL), N=Arabic_Number (AN), E=European_Number (EN),
// S=European_Separator (ES), T=European_Terminator (ET), C=Common_Separator (CS),
// M=Nonspacing_Mark (NSM), B=Boundary_Neutral (BN), O=Other_Neutral (ON).
private const val BIDI_L: Char = 'L'
private const val BIDI_R: Char = 'R'
private const val BIDI_AL: Char = 'A'
private const val BIDI_AN: Char = 'N'
private const val BIDI_EN: Char = 'E'
private const val BIDI_ES: Char = 'S'
private const val BIDI_ET: Char = 'T'
private const val BIDI_CS: Char = 'C'
private const val BIDI_NSM: Char = 'M'
private const val BIDI_BN: Char = 'B'
private const val BIDI_ON: Char = 'O'

/** Sentinel for a code point with no Bidi_Class the rule consults; it satisfies no condition. */
private const val BIDI_NONE: Char = '\u0000'

/**
 * UTS-46 label-validity rules beyond the mapping table (SPEC §7.4, [HOST-28] `CheckJoiners = true`,
 * `CheckBidi = true`):
 *
 *  - **leading combining mark** ([startsWithCombiningMark]) — a label whose first code point is a
 *    General_Category Mark (Mn/Mc/Me) is invalid (UTS-46 validity criterion 7).
 *  - **ContextJ join controls** ([checkJoiners]) — each U+200C / U+200D must satisfy its RFC 5892
 *    A.1 / A.2 context, otherwise the label is invalid.
 *  - **Bidi rule** ([checkBidi]) — a label carrying a right-to-left code point must satisfy the six
 *    RFC 5893 §2 conditions (UTS-46 validity criterion 6).
 *
 * Backing range tables come from [IdnaValidityData] (Unicode 17.0.0). Predicates are pure: a
 * string in, a boolean out, with no shared mutable state.
 */
@Suppress("TooManyFunctions") // One cohesive validity check decomposed into small helpers (decode,
// binary search, code-point split, the RFC 5892 ContextJ scans, the RFC 5893 Bidi conditions) to
// honour the 60-line cap; mirrors Idna.
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

    /**
     * A sorted table mapping each code point to a one-char record code over `starts[i]..ends[i]`,
     * returning [absent] for any code point no range covers. Backs both the Joining_Type and
     * Bidi_Class lookups (their data shares the `<START>-<END>;<CODE>` record layout).
     */
    private class TypedRangeTable(
        val starts: IntArray,
        val ends: IntArray,
        val codes: CharArray,
        val absent: Char,
    ) {
        /** The record code covering [codePoint], or [absent] when no range covers it. */
        fun codeAt(codePoint: Int): Char {
            val index = floorIndex(starts, codePoint)
            return if (index >= 0 && codePoint <= ends[index]) codes[index] else absent
        }
    }

    private val markRanges: RangeSet by lazy { decodeRanges(MARK_RANGE_CHUNKS) }
    private val viramaRanges: RangeSet by lazy { decodeRanges(VIRAMA_RANGE_CHUNKS) }
    private val joiningTypes: TypedRangeTable by lazy { decodeTyped(JOINING_TYPE_CHUNKS, JOIN_OTHER) }
    private val bidiClasses: TypedRangeTable by lazy { decodeTyped(BIDI_CLASS_CHUNKS, BIDI_NONE) }

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
        while (cursor >= 0 && joiningTypes.codeAt(codePoints[cursor]) == JOIN_TRANSPARENT) {
            cursor -= 1
        }
        return cursor >= 0 && isLeftJoining(joiningTypes.codeAt(codePoints[cursor]))
    }

    /** Scans right over Transparent chars; the first non-Transparent must be R- or D-joining. */
    private fun followingJoinerMatches(
        codePoints: IntArray,
        index: Int,
    ): Boolean {
        require(index in codePoints.indices) { "index out of bounds: $index" }
        var cursor = index + 1
        while (cursor < codePoints.size && joiningTypes.codeAt(codePoints[cursor]) == JOIN_TRANSPARENT) {
            cursor += 1
        }
        return cursor < codePoints.size && isRightJoining(joiningTypes.codeAt(codePoints[cursor]))
    }

    /** True for the left side of the ZWNJ context: Left_Joining or Dual_Joining. */
    private fun isLeftJoining(type: Char): Boolean = type == JOIN_LEFT || type == JOIN_DUAL

    /** True for the right side of the ZWNJ context: Right_Joining or Dual_Joining. */
    private fun isRightJoining(type: Char): Boolean = type == JOIN_RIGHT || type == JOIN_DUAL

    /**
     * Applies the RFC 5893 Bidi rule to [label] (SPEC §7.4, [HOST-28] `CheckBidi = true`).
     *
     * A label with no R/AL/AN code point is exempt and passes; otherwise it must satisfy the six
     * RFC 5893 §2 conditions, evaluated as an LTR or RTL label by the Bidi_Class of its first code
     * point. The trigger is per-label, matching ada and the WHATWG-URL conformance corpus: a label
     * carrying no R/AL/AN is not re-checked even when a sibling label makes the whole domain a Bidi
     * domain. This is intentionally more lenient than the strict whole-domain reading of RFC 5893
     * §1.4 — e.g. an EN-only label such as `"1"` beside an RTL label is accepted here, though its
     * leading EN would fail condition 1 in the whole-domain formulation.
     *
     * @param label a single, already-mapped label (for a decoded A-label, also NFC-normalized).
     * @return `true` when the label satisfies the Bidi rule (an empty or non-Bidi label trivially does).
     */
    internal fun checkBidi(label: String): Boolean {
        val codePoints = codePoints(label)
        if (!isRtlLabel(codePoints)) {
            return true
        }
        // An RTL label has an R/AL/AN code point, which is never NSM, so lastNonNsm is always >= 0
        // here; the guard is defensive. (An all-NSM label is non-RTL, and is anyway rejected upstream
        // by startsWithCombiningMark before validation reaches this rule.)
        val lastNonNsm = lastNonNsmIndex(codePoints)
        return lastNonNsm >= 0 && isBidiSequenceValid(codePoints, lastNonNsm)
    }

    /** RFC 5893 condition 1: the first code point's direction selects the LTR or RTL conditions. */
    private fun isBidiSequenceValid(
        codePoints: IntArray,
        lastNonNsm: Int,
    ): Boolean =
        if (bidiClasses.codeAt(codePoints[0]) == BIDI_L) {
            isBidiLtrValid(codePoints, lastNonNsm)
        } else {
            isBidiRtlValid(codePoints, lastNonNsm)
        }

    /** True when [codePoints] holds an R, AL, or AN code point, making the label subject to the rule. */
    private fun isRtlLabel(codePoints: IntArray): Boolean = codePoints.any { isRtlClass(bidiClasses.codeAt(it)) }

    /** True for the Bidi classes that make a label RTL: R, AL, or AN. */
    private fun isRtlClass(code: Char): Boolean = code == BIDI_R || code == BIDI_AL || code == BIDI_AN

    /** Index of the last non-NSM code point of [codePoints], or `-1` when every one is NSM. */
    private fun lastNonNsmIndex(codePoints: IntArray): Int {
        var index = codePoints.size - 1
        while (index >= 0 && bidiClasses.codeAt(codePoints[index]) == BIDI_NSM) {
            index -= 1
        }
        return index
    }

    /**
     * RFC 5893 conditions 5 & 6 for an LTR label (first code point already known to be L).
     *
     * Reached only for a label that carries an R/AL/AN code point ([checkBidi]'s trigger); since
     * condition 5 ([isLtrAllowed]) forbids those three classes, that triggering code point always
     * fails the loop, so in practice every LTR Bidi label is rejected here. The condition-6 end-check
     * is kept verbatim for faithful parity with RFC 5893 and the Go conformance reference, but is
     * unreachable under the per-label trigger and so cannot be exercised in isolation.
     */
    private fun isBidiLtrValid(
        codePoints: IntArray,
        lastNonNsm: Int,
    ): Boolean {
        require(lastNonNsm in codePoints.indices) { "lastNonNsm out of bounds: $lastNonNsm" }
        for (index in 0..lastNonNsm) {
            if (!isLtrAllowed(bidiClasses.codeAt(codePoints[index]))) {
                return false
            }
        }
        val last = bidiClasses.codeAt(codePoints[lastNonNsm])
        return last == BIDI_L || last == BIDI_EN
    }

    /** RFC 5893 condition 5: the Bidi classes permitted anywhere in an LTR label. */
    private fun isLtrAllowed(code: Char): Boolean =
        when (code) {
            BIDI_L, BIDI_EN, BIDI_ES, BIDI_CS, BIDI_ET, BIDI_ON, BIDI_BN, BIDI_NSM -> true
            else -> false
        }

    /** RFC 5893 conditions 1–4 for an RTL label (first code point is not L). */
    private fun isBidiRtlValid(
        codePoints: IntArray,
        lastNonNsm: Int,
    ): Boolean {
        require(lastNonNsm in codePoints.indices) { "lastNonNsm out of bounds: $lastNonNsm" }
        val first = bidiClasses.codeAt(codePoints[0])
        if (first != BIDI_R && first != BIDI_AL) {
            return false
        }
        var hasArabicNumber = false
        var hasEuropeanNumber = false
        var valid = true
        var index = 0
        while (index <= lastNonNsm && valid) {
            val code = bidiClasses.codeAt(codePoints[index])
            hasEuropeanNumber = hasEuropeanNumber || code == BIDI_EN
            hasArabicNumber = hasArabicNumber || code == BIDI_AN
            // Condition 2 (allowed class) and condition 4 (EN and AN are mutually exclusive).
            valid = isRtlAllowed(code) && !(hasArabicNumber && hasEuropeanNumber)
            index += 1
        }
        return valid && isRtlLabelEnd(bidiClasses.codeAt(codePoints[lastNonNsm]))
    }

    /** RFC 5893 condition 2: the Bidi classes permitted anywhere in an RTL label. */
    private fun isRtlAllowed(code: Char): Boolean =
        when (code) {
            BIDI_R, BIDI_AL, BIDI_AN, BIDI_EN, BIDI_ES, BIDI_CS, BIDI_ET, BIDI_ON, BIDI_BN, BIDI_NSM -> true
            else -> false
        }

    /** RFC 5893 condition 3: an RTL label's last non-NSM code point must be R, AL, EN, or AN. */
    private fun isRtlLabelEnd(code: Char): Boolean =
        code == BIDI_R || code == BIDI_AL || code == BIDI_EN || code == BIDI_AN

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

    /** Decodes a `<START>-<END>;<CODE>` blob into a [TypedRangeTable] returning [absent] off-table. */
    private fun decodeTyped(
        chunks: List<String>,
        absent: Char,
    ): TypedRangeTable {
        val records = chunks.joinToString(separator = "").split(RECORD_SEPARATOR)
        check(records.isNotEmpty()) { "typed range table data is empty" }
        val starts = IntArray(records.size)
        val ends = IntArray(records.size)
        val codes = CharArray(records.size)
        records.forEachIndexed { index, record ->
            val dash = record.indexOf(RANGE_SEPARATOR)
            val semi = record.indexOf(TYPE_SEPARATOR)
            check(dash in 1 until semi) { "malformed typed range record: $record" }
            starts[index] = record.substring(0, dash).toInt(RADIX_HEX)
            ends[index] = record.substring(dash + 1, semi).toInt(RADIX_HEX)
            codes[index] = record[semi + 1]
        }
        return TypedRangeTable(starts, ends, codes, absent)
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
}
