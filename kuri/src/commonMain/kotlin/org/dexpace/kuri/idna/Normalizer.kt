/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.kuri.idna

import org.dexpace.kuri.text.appendCodePoint
import org.dexpace.kuri.text.codePointsOf

/** Combining class of a starter (and of all non-combining characters). */
private const val CCC_STARTER: Int = 0

/** Radix used to decode the hex tokens in [NfcData]. */
private const val RADIX_HEX: Int = 16

/** Separator between records in each decoded blob. */
private const val RECORD_SEPARATOR: Char = ';'

/** Separator between a record's key and value (CCC and composition/decomposition tables). */
private const val KEY_VALUE_SEPARATOR: Char = ':'

/** Separator between key and value in the decomposition and composition tables. */
private const val MAP_SEPARATOR: Char = '='

/** Separator between the two members of a composition key. */
private const val PAIR_SEPARATOR: Char = ','

/** Separator between code points of a decomposition value. */
private const val DECOMP_SEPARATOR: Char = ' '

/** Bit width reserved for a code point when packing a composition key (covers U+10FFFF). */
private const val CODE_POINT_BITS: Int = 21

// Algorithmic Hangul syllable composition/decomposition parameters (UAX #15, §3.12 of the core spec).
private const val S_BASE: Int = 0xAC00
private const val L_BASE: Int = 0x1100
private const val V_BASE: Int = 0x1161
private const val T_BASE: Int = 0x11A7
private const val L_COUNT: Int = 19
private const val V_COUNT: Int = 21
private const val T_COUNT: Int = 28
private const val N_COUNT: Int = V_COUNT * T_COUNT
private const val S_COUNT: Int = L_COUNT * N_COUNT

/**
 * Canonical normalization to NFC (Normalization Form C) per Unicode Standard Annex #15.
 *
 * [nfc] applies the three UAX #15 steps in order: full canonical decomposition (table-driven plus
 * algorithmic Hangul), canonical ordering (a stable sort of each combining run by combining class),
 * and canonical composition (left-to-right, table-driven plus algorithmic Hangul, honouring the
 * blocked-starter rule). All processing is over Unicode scalar values, so surrogate pairs are
 * handled correctly. Table data lives in [NfcData]; this object owns the decoder and the algorithm.
 */
internal object Normalizer {
    private val combiningClasses: Map<Int, Int> by lazy { decodeCcc() }
    private val decompositions: Map<Int, IntArray> by lazy { decodeDecompositions() }
    private val compositions: Map<Long, Int> by lazy { decodeCompositions() }

    /**
     * Returns the NFC form of [input].
     *
     * @param input any Kotlin string (well-formed or with lone surrogates, which pass through).
     * @return the canonically composed equivalent; idempotent (`nfc(nfc(x)) == nfc(x)`).
     */
    internal fun nfc(input: String): String {
        if (input.isEmpty()) {
            return input
        }
        val decomposed = decompose(input)
        canonicalOrder(decomposed)
        return compose(decomposed)
    }

    /** Fully canonically decomposes [input] into a flat list of code points. */
    private fun decompose(input: String): MutableList<Int> {
        require(input.isNotEmpty()) { "decompose requires non-empty input" }
        val output = ArrayList<Int>(input.length)
        for (codePoint in codePointsOf(input)) {
            decomposeCodePoint(codePoint, output)
        }
        check(output.isNotEmpty()) { "decomposition of non-empty input must be non-empty" }
        return output
    }

    /** Appends the full canonical decomposition of [codePoint] to [output] (recursive + Hangul). */
    private fun decomposeCodePoint(
        codePoint: Int,
        output: MutableList<Int>,
    ) {
        require(codePoint >= 0) { "code point must be non-negative: $codePoint" }
        if (hangulDecompose(codePoint, output)) {
            return
        }
        val mapping = decompositions[codePoint]
        if (mapping == null) {
            output.add(codePoint)
            return
        }
        for (part in mapping) {
            decomposeCodePoint(part, output)
        }
    }

    /** Stably sorts each maximal combining run of [codePoints] by combining class (in place). */
    private fun canonicalOrder(codePoints: MutableList<Int>) {
        require(codePoints.isNotEmpty()) { "ordering requires a non-empty buffer" }
        var index = 1
        while (index < codePoints.size) {
            val ccc = combiningClass(codePoints[index])
            val previous = combiningClass(codePoints[index - 1])
            if (ccc != CCC_STARTER && previous != CCC_STARTER && previous > ccc) {
                val swap = codePoints[index]
                codePoints[index] = codePoints[index - 1]
                codePoints[index - 1] = swap
                index = if (index > 1) index - 1 else index + 1
            } else {
                index += 1
            }
        }
    }

    /** Canonically composes the ordered [codePoints] back into a string (table + Hangul). */
    private fun compose(codePoints: MutableList<Int>): String {
        require(codePoints.isNotEmpty()) { "composition requires a non-empty buffer" }
        var starterIndex = 0
        var starter = codePoints[0]
        var lastClass = CCC_STARTER
        var index = 1
        while (index < codePoints.size) {
            val current = codePoints[index]
            val currentClass = combiningClass(current)
            val composed = if (notBlocked(lastClass, currentClass)) composeStarter(starter, current) else -1
            if (composed >= 0) {
                starter = composed
                codePoints[starterIndex] = composed
                codePoints.removeAt(index)
            } else {
                lastClass = currentClass
                if (currentClass == CCC_STARTER) {
                    starterIndex = index
                    starter = current
                }
                index += 1
            }
        }
        return buildScalarString(codePoints)
    }

    /** Composes [starter] with [combining] via the Hangul algorithm or the primary-composite table. */
    private fun composeStarter(
        starter: Int,
        combining: Int,
    ): Int {
        require(starter >= 0) { "starter must be non-negative: $starter" }
        val hangul = hangulCompose(starter, combining)
        if (hangul >= 0) {
            return hangul
        }
        return compositions[packPair(starter, combining)] ?: -1
    }

    /** Combining class of [codePoint]; zero for any starter (the common case). */
    private fun combiningClass(codePoint: Int): Int = combiningClasses[codePoint] ?: CCC_STARTER

    private fun decodeCcc(): Map<Int, Int> {
        val records = NFC_CCC_CHUNKS.joinToString(separator = "")
        check(records.isNotEmpty()) { "CCC table data is empty" }
        val result = HashMap<Int, Int>()
        for (record in records.split(RECORD_SEPARATOR)) {
            val separator = record.indexOf(KEY_VALUE_SEPARATOR)
            check(separator > 0) { "malformed CCC record: $record" }
            val codePoint = record.substring(0, separator).toInt(RADIX_HEX)
            result[codePoint] = record.substring(separator + 1).toInt(RADIX_HEX)
        }
        return result
    }

    private fun decodeDecompositions(): Map<Int, IntArray> {
        val records = NFC_DECOMPOSITION_CHUNKS.joinToString(separator = "")
        check(records.isNotEmpty()) { "decomposition table data is empty" }
        val result = HashMap<Int, IntArray>()
        for (record in records.split(RECORD_SEPARATOR)) {
            val separator = record.indexOf(MAP_SEPARATOR)
            check(separator > 0) { "malformed decomposition record: $record" }
            val codePoint = record.substring(0, separator).toInt(RADIX_HEX)
            val targets = record.substring(separator + 1).split(DECOMP_SEPARATOR)
            result[codePoint] = IntArray(targets.size) { targets[it].toInt(RADIX_HEX) }
        }
        return result
    }

    private fun decodeCompositions(): Map<Long, Int> {
        val records = NFC_COMPOSITION_CHUNKS.joinToString(separator = "")
        check(records.isNotEmpty()) { "composition table data is empty" }
        val result = HashMap<Long, Int>()
        for (record in records.split(RECORD_SEPARATOR)) {
            val pivot = record.indexOf(MAP_SEPARATOR)
            val comma = record.indexOf(PAIR_SEPARATOR)
            check(comma in 1 until pivot) { "malformed composition record: $record" }
            val starter = record.substring(0, comma).toInt(RADIX_HEX)
            val combining = record.substring(comma + 1, pivot).toInt(RADIX_HEX)
            result[packPair(starter, combining)] = record.substring(pivot + 1).toInt(RADIX_HEX)
        }
        return result
    }
}

/** Decomposes a Hangul syllable algorithmically; returns false (no-op) for non-syllables. */
private fun hangulDecompose(
    codePoint: Int,
    output: MutableList<Int>,
): Boolean {
    val syllable = codePoint - S_BASE
    if (syllable < 0 || syllable >= S_COUNT) {
        return false
    }
    output.add(L_BASE + syllable / N_COUNT)
    output.add(V_BASE + syllable % N_COUNT / T_COUNT)
    val trailing = syllable % T_COUNT
    if (trailing != 0) {
        output.add(T_BASE + trailing)
    }
    return true
}

/** Composes an L+V or LV+T Hangul pair algorithmically; returns -1 when not composable. */
private fun hangulCompose(
    starter: Int,
    combining: Int,
): Int {
    val leading = starter - L_BASE
    if (leading in 0 until L_COUNT) {
        val vowel = combining - V_BASE
        val composable = vowel in 0 until V_COUNT
        return if (composable) S_BASE + (leading * V_COUNT + vowel) * T_COUNT else -1
    }
    val syllable = starter - S_BASE
    val isLeadingVowel = syllable in 0 until S_COUNT && syllable % T_COUNT == 0
    val trailing = combining - T_BASE
    val composable = isLeadingVowel && trailing in 1 until T_COUNT
    return if (composable) starter + trailing else -1
}

/**
 * Whether a combining character with class [currentClass] is reachable by the active starter:
 * true when no intervening character of equal-or-greater class blocks it (UAX #15 rule).
 */
private fun notBlocked(
    lastClass: Int,
    currentClass: Int,
): Boolean {
    require(currentClass >= CCC_STARTER) { "negative combining class: $currentClass" }
    return lastClass == CCC_STARTER || lastClass < currentClass
}

/** Packs a `(starter, combining)` pair into a single key for the composition table. */
private fun packPair(
    starter: Int,
    combining: Int,
): Long = (starter.toLong() shl CODE_POINT_BITS) or combining.toLong()

/** Builds a string from a list of scalar [codePoints], emitting surrogate pairs as needed. */
private fun buildScalarString(codePoints: List<Int>): String {
    require(codePoints.isNotEmpty()) { "cannot build a string from no code points" }
    val builder = StringBuilder(codePoints.size)
    for (codePoint in codePoints) {
        appendCodePoint(builder, codePoint)
    }
    return builder.toString()
}
