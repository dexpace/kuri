/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.query

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
     * Stable sort by **name only**, comparing raw UTF-16 code units (SPEC §10.3.2).
     *
     * This matches the WHATWG-mandated order, which is *not* code-point order: a supplementary
     * name's leading surrogate (`U+D800`-`U+DBFF`) can sort below a BMP character at or above
     * `U+D800`, so a supplementary name can sort before a BMP name that would precede it under
     * code-point comparison. Equal names keep their pre-sort order, so the relative order of
     * their values is preserved; values are never compared.
     */
    public fun sort(): QueryParametersBuilder {
        val before = pairs.size
        pairs.sortWith { left, right -> left.first.compareTo(right.first) }
        check(pairs.size == before) { "sort must not change the pair count" }
        return this
    }

    /**
     * Materializes an immutable [QueryParameters] from the current pairs (SPEC §10.3.2).
     *
     * @return a snapshot of the accumulated pairs; later mutation of this builder does not affect it.
     */
    public fun build(): QueryParameters = QueryParameters(pairs)

    /** True when no pairs are accumulated; the O(1) check the `Uri`/`Url` builders use to collapse an emptied query. */
    internal fun isEmpty(): Boolean = pairs.isEmpty()

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
 * The query a `Uri`/`Url` builder holds: a verbatim [Raw] string exactly as passed to
 * `query(String?)`, or a structured [Params] parameter edit in progress. Keeping the raw form verbatim
 * means a non-canonically-encoded query (e.g. `query("a=%41")`) survives to `build()` byte-for-byte
 * rather than being folded to its canonical form, while the structured form makes a chain of parameter
 * edits O(parse + N) instead of re-serializing on every call.
 */
internal sealed interface QueryState {
    /** A verbatim query string exactly as supplied; `null` denotes an absent query (no `?`). */
    data class Raw(
        val query: String?,
    ) : QueryState

    /** A structured, in-progress parameter edit; always denotes a present query. */
    data class Params(
        val builder: QueryParametersBuilder,
    ) : QueryState

    /** The raw query string this state contributes to a recomposed URI/URL, or `null` when absent. */
    fun resolve(): String? =
        when (this) {
            is Raw -> query
            is Params -> builder.build().toQueryString()
        }
}

/**
 * Applies [edit] to this query state's parameters and collapses the result per [emptyBecomesNull],
 * without re-serializing the query on every call. A [QueryState.Raw] state is parsed to a builder
 * exactly once; a subsequent edit reuses the [QueryState.Params] builder in place.
 *
 * [emptyBecomesNull] selects the empty policy: `true` (the `Url` rule) drops the `?` whenever no pairs
 * remain; `false` (the `Uri` rule) drops it only when the query was already absent, so a
 * present-but-empty `""` query survives an edit. A `set`/`add` never empties, so it always yields a
 * present [QueryState.Params].
 */
internal fun QueryState.applyParameterEdit(
    emptyBecomesNull: Boolean,
    edit: (QueryParametersBuilder) -> Unit,
): QueryState {
    val currentAbsent = this is QueryState.Raw && query == null
    val builder =
        when (this) {
            is QueryState.Params -> builder
            is QueryState.Raw -> QueryParameters.parseOrEmpty(query).newBuilder()
        }
    edit(builder)
    return if (builder.isEmpty() && (emptyBecomesNull || currentAbsent)) {
        QueryState.Raw(null)
    } else {
        QueryState.Params(builder)
    }
}
