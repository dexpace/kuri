/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.query

import org.dexpace.kuri.percent.PercentCodec
import org.dexpace.kuri.percent.PercentEncodeSet
import org.dexpace.kuri.percent.PercentEncodeSets

/** The query-pair resource bound: at most this many pairs are materialized ([QUERY-24], §10.5). */
internal const val MAX_PAIRS: Int = 1000

/** The `&` that joins pairs and splits the raw query (SPEC §10.2.1, §10.3.3). */
private const val PAIR_SEPARATOR: String = "&"

/** The `=` that splits the first name/value boundary of a pair (SPEC §10.2.1, §10.3.3). */
private const val VALUE_SEPARATOR: String = "="

/** The optional leading `?` dropped before deriving pairs (SPEC §10.2.1). */
private const val QUERY_PREFIX: String = "?"

/**
 * `encodeName` set ([QUERY-19]): the query set plus `&` and `=`, since a name terminates at the
 * first `=` or `&` and both must survive a round-trip.
 */
private val QUERY_NAME_ENCODE_SET: PercentEncodeSet = PercentEncodeSets.QUERY.including('&', '=')

/**
 * `encodeValue` set ([QUERY-19]): the query set plus `&` only. `=` is left literal so that only the
 * first `=` of a pair splits and `?===3===` round-trips exactly.
 */
private val QUERY_VALUE_ENCODE_SET: PercentEncodeSet = PercentEncodeSets.QUERY.including('&')

/** Percent-encodes a decoded name with [QUERY_NAME_ENCODE_SET] ([QUERY-19]). */
private fun encodeName(name: String): String = PercentCodec.encode(name, QUERY_NAME_ENCODE_SET)

/** Percent-encodes a decoded value with [QUERY_VALUE_ENCODE_SET] ([QUERY-19]). */
private fun encodeValue(value: String): String = PercentCodec.encode(value, QUERY_VALUE_ENCODE_SET)

/**
 * An immutable, ordered, duplicate-preserving, case-sensitive snapshot of decoded query
 * `(name, value?)` pairs (SPEC §10.2, [QUERY-5]). A `null` value denotes a pair that had no `=`;
 * an empty-string value denotes `=` with nothing after it, and the two MUST stay distinguishable
 * ([QUERY-8]). The snapshot is never a live view of any `Uri`/`Url` ([QUERY-14]); mutation goes
 * through [QueryParametersBuilder].
 */
public class QueryParameters internal constructor(
    pairs: List<Pair<String, String?>>,
) {
    /** Defensive immutable copy of the decoded pairs in appearance order ([QUERY-5]). */
    internal val entries: List<Pair<String, String?>> = pairs.toList()

    /**
     * Total pair count, counting duplicates (SPEC §10.3, [QUERY-9]).
     *
     * @return the number of pairs, including repeats of the same name.
     */
    public fun size(): Int = entries.size

    /**
     * True when no pairs are present; equivalent to `size() == 0` (SPEC §10.3).
     *
     * @return `true` iff this snapshot holds no pairs.
     */
    public fun isEmpty(): Boolean = entries.isEmpty()

    /**
     * The decoded value of the **first** pair named [name], or `null` when absent or when that
     * first pair had no `=` (SPEC §10.3, [QUERY-11]). `get` cannot distinguish "absent" from
     * "present with no `=`"; use [names]/[nameAt]/[valueAt] for that distinction.
     *
     * @param name the decoded name to look up, matched case-sensitively.
     * @return the first matching decoded value, or `null` when absent or when that pair had no `=`.
     */
    public fun get(name: String): String? = entries.firstOrNull { it.first == name }?.second

    /**
     * The decoded values of **all** pairs named [name], in appearance order (SPEC §10.3,
     * [QUERY-12]). The list is read-only and preserves `null` (no `=`) distinctly from `""`.
     *
     * Per [QUERY-12] the element type is `String?`: `null` entries are retained rather than mapped
     * to `""` or dropped, so the no-`=` sentinel survives a `getAll` round-trip. Empty when none.
     *
     * @param name the decoded name to look up, matched case-sensitively.
     * @return a read-only list of every matching value in order, `null` per no-`=` pair; empty when none.
     */
    public fun getAll(name: String): List<String?> {
        val result = ArrayList<String?>()
        for (pair in entries) {
            if (pair.first == name) result.add(pair.second)
        }
        check(result.size <= entries.size) { "getAll cannot exceed the pair count" }
        return result
    }

    /**
     * True when at least one pair is named [name], case-sensitively (SPEC §10.3).
     *
     * @param name the decoded name to test, matched case-sensitively.
     * @return `true` iff at least one pair carries [name].
     */
    public fun has(name: String): Boolean = entries.any { it.first == name }

    /**
     * The distinct decoded names in first-appearance order, backed by a [LinkedHashSet]
     * (SPEC §10.3, [QUERY-13]). Each name appears exactly once even when duplicated in the pairs.
     *
     * @return a read-only set of the distinct names in first-appearance order.
     */
    public fun names(): Set<String> {
        val result = LinkedHashSet<String>(entries.size)
        for (pair in entries) {
            result.add(pair.first)
        }
        check(result.size <= entries.size) { "distinct names cannot exceed the pair count" }
        return result
    }

    /**
     * The decoded name of the pair at [index] (SPEC §10.3, [QUERY-10]).
     *
     * @param index the zero-based position in `0 until size()`.
     * @return the decoded name at [index].
     * @throws IndexOutOfBoundsException when [index] is negative or `>= size()`.
     */
    public fun nameAt(index: Int): String {
        checkIndex(index)
        return entries[index].first
    }

    /**
     * The decoded value of the pair at [index], or `null` for a pair that had no `=`
     * (SPEC §10.3, [QUERY-10]).
     *
     * @param index the zero-based position in `0 until size()`.
     * @return the decoded value at [index], or `null` when that pair had no `=`.
     * @throws IndexOutOfBoundsException when [index] is negative or `>= size()`.
     */
    public fun valueAt(index: Int): String? {
        checkIndex(index)
        return entries[index].second
    }

    /**
     * Re-serializes the pairs to a generic raw query string (SPEC §10.3.3, [QUERY-19]).
     *
     * Pairs join with `&`; each emits `encodeName(name)` followed by `= encodeValue(value)` only
     * when `value` is non-`null`. `+` is never specially encoded, and the null-vs-empty distinction
     * is preserved, so a query needing no escaping round-trips exactly (e.g. `===3===`).
     *
     * @return the raw query string (without a leading `?`); `""` when [isEmpty].
     */
    public fun toQueryString(): String {
        check(entries.size <= MAX_PAIRS) { "pair count exceeds the parse bound" }
        return entries.joinToString(PAIR_SEPARATOR) { (name, value) ->
            encodeName(name) + (value?.let { VALUE_SEPARATOR + encodeValue(it) } ?: "")
        }
    }

    /** Throws [IndexOutOfBoundsException] when [index] is outside `0 until size()` ([QUERY-10]). */
    private fun checkIndex(index: Int) {
        if (index < 0 || index >= entries.size) {
            throw IndexOutOfBoundsException("index $index out of bounds for size ${entries.size}")
        }
    }

    internal companion object {
        /**
         * Derives a [QueryParameters] from the raw query [query] (SPEC §10.2.1, [QUERY-6]).
         *
         * A single leading `?` is dropped, then the body is split on `&`; each segment splits on its
         * **first** `=` into `(name, value)` or `(name, null)` when no `=` is present, and both sides
         * are percent-decoded with no plus-as-space ([QUERY-7]). Order and duplicates are preserved,
         * and the sentinels of [QUERY-8] hold (`""` -> one empty pair, `&` -> two empty pairs).
         *
         * The pair count is bounded by [MAX_PAIRS] ([QUERY-24]): once the cap is reached, parsing
         * stops and no further pairs are recorded (these internal classes return a value, not a
         * `ParseResult`; the surfaced `LimitExceeded` failure of §10.5 is the caller's concern).
         */
        internal fun parse(query: String): QueryParameters {
            val body = if (query.startsWith(QUERY_PREFIX)) query.substring(1) else query
            val pairs = ArrayList<Pair<String, String?>>()
            var pos = 0
            while (pos <= body.length && pairs.size < MAX_PAIRS) {
                val amp = nextSeparator(body, pos)
                val eq = firstEquals(body, pos, amp)
                pairs.add(splitPair(body, pos, amp, eq))
                pos = amp + 1
            }
            check(pairs.size <= MAX_PAIRS) { "parse exceeded the pair bound" }
            return QueryParameters(pairs)
        }

        /** Index of the next `&` at or after [pos], or `length` when none remains ([QUERY-6]). */
        private fun nextSeparator(
            body: String,
            pos: Int,
        ): Int {
            val amp = body.indexOf(PAIR_SEPARATOR, pos)
            return if (amp < 0) body.length else amp
        }

        /** Index of the first `=` within `[pos, amp)`, or `-1` when none ([QUERY-6]). */
        private fun firstEquals(
            body: String,
            pos: Int,
            amp: Int,
        ): Int {
            val eq = body.indexOf(VALUE_SEPARATOR, pos)
            return if (eq in pos until amp) eq else -1
        }

        /** Decodes one segment into a `(name, value?)` pair using the first-`=` split ([QUERY-8]). */
        private fun splitPair(
            body: String,
            pos: Int,
            amp: Int,
            eq: Int,
        ): Pair<String, String?> {
            require(pos <= amp) { "segment start $pos must not exceed its end $amp" }
            check(amp <= body.length) { "segment end $amp must be within the body" }
            return if (eq < 0) {
                decode(body.substring(pos, amp)) to null
            } else {
                decode(body.substring(pos, eq)) to decode(body.substring(eq + 1, amp))
            }
        }

        /** Percent-decodes a raw name or value with `+` kept literal ([QUERY-7]). */
        private fun decode(raw: String): String = PercentCodec.decode(raw)
    }
}
