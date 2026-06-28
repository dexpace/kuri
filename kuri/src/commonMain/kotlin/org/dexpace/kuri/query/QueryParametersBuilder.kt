/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.query

/** Smallest supplementary code point, `U+10000`; below it a code point fits one UTF-16 unit. */
private const val SUPPLEMENTARY_MIN: Int = 0x10000

/** Base of the high-surrogate range, `U+D800`, subtracted when recomposing a surrogate pair. */
private const val HIGH_SURROGATE_BASE: Int = 0xD800

/** Base of the low-surrogate range, `U+DC00`, subtracted when recomposing a surrogate pair. */
private const val LOW_SURROGATE_BASE: Int = 0xDC00

/** Bit width contributed by the high surrogate when recomposing a supplementary code point. */
private const val SURROGATE_SHIFT: Int = 10

/**
 * Mutable, ordered, duplicate-preserving accumulator that produces an immutable [QueryParameters]
 * via [build] (SPEC §10.3.2). Name matching is byte-exact and case-sensitive throughout
 * ([QUERY-5]); a `null` value is the no-`=` sentinel, an empty string the `=`-with-empty sentinel.
 */
internal class QueryParametersBuilder internal constructor(
    initial: List<Pair<String, String?>> = emptyList(),
) {
    /** The working pair list; copied from [initial] so the source snapshot is never mutated. */
    private val pairs: MutableList<Pair<String, String?>> = initial.toMutableList()

    /**
     * Appends `(name, value)` to the end without deduplicating (SPEC §10.3.2, [QUERY-15]). A `null`
     * value is retained as the no-`=` sentinel; an empty string as the `=`-with-empty sentinel.
     */
    internal fun add(
        name: String,
        value: String?,
    ): QueryParametersBuilder {
        pairs.add(name to value)
        check(pairs.isNotEmpty()) { "add must leave at least one pair" }
        return this
    }

    /**
     * Replace-first / remove-rest / keep-position (SPEC §10.3.2, [QUERY-16]): replaces the value of
     * the **first** pair named [name] in place, removes every later pair named [name], and appends
     * `(name, value)` when no pair has the name.
     */
    internal fun set(
        name: String,
        value: String?,
    ): QueryParametersBuilder {
        val firstIndex = pairs.indexOfFirst { it.first == name }
        if (firstIndex < 0) {
            pairs.add(name to value)
        } else {
            pairs[firstIndex] = name to value
            removeAfter(firstIndex, name)
        }
        check(pairs.count { it.first == name } == 1) { "set must leave exactly one pair named $name" }
        return this
    }

    /**
     * Removes every pair named [name], preserving the order of the rest (SPEC §10.3.2, [QUERY-17]).
     * A no-op when no pair matches.
     */
    internal fun removeAll(name: String): QueryParametersBuilder {
        pairs.removeAll { it.first == name }
        check(pairs.none { it.first == name }) { "removeAll must drop every pair named $name" }
        return this
    }

    /**
     * Stable sort by **name only**, comparing Unicode code points (SPEC §10.3.2, [QUERY-18]).
     *
     * The comparison is surrogate-aware: supplementary code points (`> U+FFFF`) sort after every
     * BMP character rather than by raw UTF-16 unit. Equal names keep their pre-sort order, so the
     * relative order of their values is preserved; values are never compared.
     */
    internal fun sort(): QueryParametersBuilder {
        val before = pairs.size
        pairs.sortWith { left, right -> compareByCodePoint(left.first, right.first) }
        check(pairs.size == before) { "sort must not change the pair count" }
        return this
    }

    /** Materializes an immutable [QueryParameters] from the current pairs (SPEC §10.3.2). */
    internal fun build(): QueryParameters = QueryParameters(pairs.toList())

    /** Removes pairs named [name] strictly after [firstIndex], scanning back to front ([QUERY-16]). */
    private fun removeAfter(
        firstIndex: Int,
        name: String,
    ) {
        require(firstIndex >= 0) { "first index must be non-negative: $firstIndex" }
        var i = pairs.size - 1
        while (i > firstIndex) {
            if (pairs[i].first == name) pairs.removeAt(i)
            i--
        }
    }
}

/** A pre-filled [QueryParametersBuilder] over this snapshot's pairs (SPEC §10.3.2, [QUERY-19]). */
internal fun QueryParameters.newBuilder(): QueryParametersBuilder = QueryParametersBuilder(entries)

/**
 * Compares [left] and [right] by Unicode code point sequence, surrogate-aware ([QUERY-18]).
 *
 * Returns a negative, zero, or positive result as [left] orders before, equal to, or after [right];
 * when one is a prefix of the other the shorter sorts first.
 */
private fun compareByCodePoint(
    left: String,
    right: String,
): Int {
    var i = 0
    var j = 0
    var result = 0
    while (i < left.length && j < right.length && result == 0) {
        val cpLeft = codePointAt(left, i)
        val cpRight = codePointAt(right, j)
        result = cpLeft - cpRight
        i += charCount(cpLeft)
        j += charCount(cpRight)
    }
    check(i <= left.length && j <= right.length) { "code-point scan overran a name" }
    return if (result != 0) result else (left.length - i) - (right.length - j)
}

/** The code point at [index], recomposing a high+low surrogate pair into one value ([QUERY-18]). */
private fun codePointAt(
    text: String,
    index: Int,
): Int {
    val high = text[index]
    val next = index + 1
    val paired = high.isHighSurrogate() && next < text.length && text[next].isLowSurrogate()
    return if (paired) {
        SUPPLEMENTARY_MIN + ((high.code - HIGH_SURROGATE_BASE) shl SURROGATE_SHIFT) +
            (text[next].code - LOW_SURROGATE_BASE)
    } else {
        high.code
    }
}

/** UTF-16 unit width of [codePoint]: `2` for supplementary, `1` otherwise ([QUERY-18]). */
private fun charCount(codePoint: Int): Int = if (codePoint >= SUPPLEMENTARY_MIN) 2 else 1
