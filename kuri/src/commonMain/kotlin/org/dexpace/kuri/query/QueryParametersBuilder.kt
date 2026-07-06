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
 * via [build] (SPEC §10.3.2). Name matching is byte-exact and case-sensitive throughout;
 * a `null` value is the no-`=` sentinel, an empty string the `=`-with-empty sentinel.
 */
public class QueryParametersBuilder internal constructor(
    initial: List<Pair<String, String?>>,
) {
    /**
     * Creates an empty builder. Append pairs with [add]/[addAll], then materialize with [build]; to
     * start from an existing snapshot use [QueryParameters.newBuilder] instead.
     */
    public constructor() : this(emptyList())

    /** The working pair list; copied from [initial] so the source snapshot is never mutated. */
    private val pairs: MutableList<Pair<String, String?>> = initial.toMutableList()

    /**
     * Appends `(name, value)` to the end without deduplicating (SPEC §10.3.2). A `null`
     * value is retained as the no-`=` sentinel; an empty string as the `=`-with-empty sentinel.
     *
     * @param name the decoded name to append.
     * @param value the decoded value, or `null` for a name with no `=`.
     */
    public fun add(
        name: String,
        value: String?,
    ): QueryParametersBuilder {
        pairs.add(name to value)
        check(pairs.isNotEmpty()) { "add must leave at least one pair" }
        return this
    }

    /**
     * Appends [parameter] without deduplicating, the [QueryParameter] overload of [add] (SPEC §10.3.2).
     *
     * Accepts the element type yielded when a [QueryParameters] snapshot is iterated, so a pair can be
     * re-appended directly without first destructuring it into its name and value. A `null`
     * [QueryParameter.value] is retained as the no-`=` sentinel.
     *
     * @param parameter the decoded pair to append.
     * @return this builder, for chaining.
     */
    public fun add(parameter: QueryParameter): QueryParametersBuilder = add(parameter.name, parameter.value)

    /**
     * Appends every entry of [pairs] in the map's iteration order without deduplicating, the bulk
     * form of [add] (SPEC §10.3.2). A `null` value is retained as the no-`=` sentinel.
     *
     * @param pairs the decoded name-to-value entries to append, in the map's iteration order.
     * @return this builder, for chaining.
     */
    public fun addAll(pairs: Map<String, String?>): QueryParametersBuilder {
        val expected = this.pairs.size + pairs.size
        for ((name, value) in pairs) {
            add(name, value)
        }
        check(this.pairs.size == expected) { "addAll must append exactly one pair per entry" }
        return this
    }

    /**
     * Replace-first / remove-rest / keep-position (SPEC §10.3.2): replaces the value of
     * the **first** pair named [name] in place, removes every later pair named [name], and appends
     * `(name, value)` when no pair has the name.
     *
     * @param name the decoded name to set, matched case-sensitively.
     * @param value the decoded value, or `null` for a name with no `=`.
     */
    public fun set(
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
     * Replace-first / remove-rest / keep-position for [parameter], the [QueryParameter] overload of
     * [set] (SPEC §10.3.2).
     *
     * Accepts the element type yielded when a [QueryParameters] snapshot is iterated, so a pair can be
     * applied directly without first destructuring it into its name and value. A `null`
     * [QueryParameter.value] is retained as the no-`=` sentinel.
     *
     * @param parameter the decoded pair to set, matched case-sensitively on its [QueryParameter.name].
     * @return this builder, for chaining.
     */
    public fun set(parameter: QueryParameter): QueryParametersBuilder = set(parameter.name, parameter.value)

    /**
     * Removes every pair named [name], preserving the order of the rest (SPEC §10.3.2).
     * A no-op when no pair matches.
     *
     * @param name the decoded name whose pairs are removed, matched case-sensitively.
     */
    public fun removeAll(name: String): QueryParametersBuilder {
        pairs.removeAll { it.first == name }
        check(pairs.none { it.first == name }) { "removeAll must drop every pair named $name" }
        return this
    }

    /**
     * Stable sort by **name only**, comparing Unicode code points (SPEC §10.3.2).
     *
     * The comparison is surrogate-aware: supplementary code points (`> U+FFFF`) sort after every
     * BMP character rather than by raw UTF-16 unit. Equal names keep their pre-sort order, so the
     * relative order of their values is preserved; values are never compared.
     */
    public fun sort(): QueryParametersBuilder {
        val before = pairs.size
        pairs.sortWith { left, right -> compareByCodePoint(left.first, right.first) }
        check(pairs.size == before) { "sort must not change the pair count" }
        return this
    }

    /**
     * Materializes an immutable [QueryParameters] from the current pairs (SPEC §10.3.2).
     *
     * @return a snapshot of the accumulated pairs; later mutation of this builder does not affect it.
     */
    public fun build(): QueryParameters = QueryParameters(pairs.toList())

    /** Removes pairs named [name] strictly after [firstIndex], scanning back to front. */
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

/**
 * Applies [edit] to the decoded pairs of the raw query [current] and re-serializes the result, the
 * shared query-parameter edit used by the `Uri`/`Url` builders. [emptyBecomesNull] selects the empty
 * policy: `true` (the `Url` rule) drops the `?` whenever no pairs remain; `false` (the `Uri` rule)
 * drops it only when [current] was already absent, so a present-but-empty `""` query survives an edit.
 */
internal fun editQuery(
    current: String?,
    emptyBecomesNull: Boolean,
    edit: (QueryParametersBuilder) -> Unit,
): String? {
    val params =
        QueryParameters
            .parseOrEmpty(current)
            .newBuilder()
            .apply(edit)
            .build()
    return if (params.isEmpty() && (emptyBecomesNull || current == null)) null else params.toQueryString()
}

/**
 * Compares [left] and [right] by Unicode code point sequence, surrogate-aware.
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

/** The code point at [index], recomposing a high+low surrogate pair into one value. */
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

/** UTF-16 unit width of [codePoint]: `2` for supplementary, `1` otherwise. */
private fun charCount(codePoint: Int): Int = if (codePoint >= SUPPLEMENTARY_MIN) 2 else 1
