/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.query

import org.dexpace.kuri.percent.PercentCodec
import org.dexpace.kuri.percent.PercentEncodeSet
import org.dexpace.kuri.percent.PercentEncodeSets
import kotlin.jvm.JvmName
import kotlin.jvm.JvmStatic
import kotlin.jvm.JvmSynthetic

/** The `&` that joins pairs and splits the raw query (SPEC §10.2.1, §10.3.3). */
private const val PAIR_SEPARATOR: String = "&"

/** The `=` that splits the first name/value boundary of a pair (SPEC §10.2.1, §10.3.3). */
private const val VALUE_SEPARATOR: String = "="

/** The optional leading `?` dropped before deriving pairs (SPEC §10.2.1). */
private const val QUERY_PREFIX: String = "?"

/**
 * `encodeName` set: the query set plus `&` and `=`, since a name terminates at the
 * first `=` or `&` and both must survive a round-trip.
 */
private val QUERY_NAME_ENCODE_SET: PercentEncodeSet = PercentEncodeSets.QUERY.including('&', '=')

/**
 * `encodeValue` set: the query set plus `&` only. `=` is left literal so that only the
 * first `=` of a pair splits and `?===3===` round-trips exactly.
 */
private val QUERY_VALUE_ENCODE_SET: PercentEncodeSet = PercentEncodeSets.QUERY.including('&')

/** Percent-encodes a decoded name with [QUERY_NAME_ENCODE_SET]. */
private fun encodeName(name: String): String = PercentCodec.encode(name, QUERY_NAME_ENCODE_SET)

/** Percent-encodes a decoded value with [QUERY_VALUE_ENCODE_SET]. */
private fun encodeValue(value: String): String = PercentCodec.encode(value, QUERY_VALUE_ENCODE_SET)

/**
 * A single decoded query pair: a [name] with an optional [value] (SPEC §10.2).
 *
 * A `null` [value] denotes a pair that had no `=`; an empty-string [value] denotes `=` with nothing
 * after it, and the two stay distinguishable. This is the element type yielded when a
 * [QueryParameters] snapshot is iterated.
 *
 * @property name the decoded pair name, matched case-sensitively.
 * @property value the decoded pair value, or `null` when the pair had no `=`.
 */
public data class QueryParameter(
    public val name: String,
    public val value: String?,
)

/**
 * An immutable, ordered, duplicate-preserving, case-sensitive snapshot of decoded query
 * `(name, value?)` pairs (SPEC §10.2). A `null` value denotes a pair that had no `=`;
 * an empty-string value denotes `=` with nothing after it, and the two MUST stay distinguishable.
 * The snapshot is never a live view of any `Uri`/`Url`; mutation goes
 * through [QueryParametersBuilder], obtained from [newBuilder].
 *
 * The type has value semantics: [equals]/[hashCode] compare the full ordered pair sequence, so two
 * snapshots are equal only when they hold the same pairs in the same order. It is [Iterable], and
 * [toMap] projects it to a first-value-wins map. Its accessors, iteration, and factories form one
 * cohesive value-type surface, so the method count is intentional.
 */
@Suppress("TooManyFunctions")
public class QueryParameters internal constructor(
    pairs: List<Pair<String, String?>>,
) : Iterable<QueryParameter> {
    /** Defensive immutable copy of the decoded pairs in appearance order. */
    internal val entries: List<Pair<String, String?>> = pairs.toList()

    /**
     * Total pair count, counting duplicates (SPEC §10.3).
     *
     * Exposed to Kotlin as the read-only property `size` and, via [JvmName], to Java as `size()`.
     */
    @get:JvmName("size")
    public val size: Int get() = entries.size

    /**
     * True when no pairs are present; equivalent to `size == 0` (SPEC §10.3).
     *
     * @return `true` iff this snapshot holds no pairs.
     */
    public fun isEmpty(): Boolean = entries.isEmpty()

    /**
     * The decoded value of the **first** pair named [name], or `null` when absent or when that
     * first pair had no `=` (SPEC §10.3). `get` cannot distinguish "absent" from
     * "present with no `=`"; use [names]/[nameAt]/[valueAt] for that distinction.
     *
     * The `operator` modifier enables index syntax from Kotlin: `params["name"]`.
     *
     * @param name the decoded name to look up, matched case-sensitively.
     * @return the first matching decoded value, or `null` when absent or when that pair had no `=`.
     */
    public operator fun get(name: String): String? = entries.firstOrNull { it.first == name }?.second

    /**
     * The decoded values of **all** pairs named [name], in appearance order (SPEC §10.3).
     * The list is read-only and preserves `null` (no `=`) distinctly from `""`.
     *
     * The element type is `String?`: `null` entries are retained rather than mapped
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
     * The Kotlin `in` operator over [has], enabling membership syntax `"name" in params` (SPEC §10.3).
     *
     * Marked `@JvmSynthetic` because it only exists to back the `in` operator: Java callers see the
     * single canonical [has] method instead of a redundant `contains`. From Kotlin the operator and
     * [has] are interchangeable.
     *
     * @param name the decoded name to test, matched case-sensitively.
     * @return `true` iff at least one pair carries [name].
     */
    @JvmSynthetic
    public operator fun contains(name: String): Boolean = has(name)

    /**
     * The distinct decoded names in first-appearance order, backed by a [LinkedHashSet]
     * (SPEC §10.3). Each name appears exactly once even when duplicated in the pairs.
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
     * The decoded name of the pair at [index] (SPEC §10.3).
     *
     * @param index the zero-based position in `0 until size`.
     * @return the decoded name at [index].
     * @throws IndexOutOfBoundsException when [index] is negative or `>= size`.
     */
    public fun nameAt(index: Int): String {
        checkIndex(index)
        return entries[index].first
    }

    /**
     * The decoded value of the pair at [index], or `null` for a pair that had no `=`
     * (SPEC §10.3).
     *
     * @param index the zero-based position in `0 until size`.
     * @return the decoded value at [index], or `null` when that pair had no `=`.
     * @throws IndexOutOfBoundsException when [index] is negative or `>= size`.
     */
    public fun valueAt(index: Int): String? {
        checkIndex(index)
        return entries[index].second
    }

    /**
     * Re-serializes the pairs to a generic raw query string (SPEC §10.3.3).
     *
     * Pairs join with `&`; each emits `encodeName(name)` followed by `= encodeValue(value)` only
     * when `value` is non-`null`. `+` is never specially encoded, and the null-vs-empty distinction
     * is preserved, so a query needing no escaping round-trips exactly (e.g. `===3===`).
     * Serialization is total: a snapshot of any size serializes, so [toString] never fails.
     *
     * @return the raw query string (without a leading `?`); `""` when [isEmpty].
     */
    public fun toQueryString(): String =
        entries.joinToString(PAIR_SEPARATOR) { (name, value) ->
            encodeName(name) + (value?.let { VALUE_SEPARATOR + encodeValue(it) } ?: "")
        }

    /**
     * Re-serializes the pairs as an `application/x-www-form-urlencoded` body (SPEC §10.4).
     *
     * Unlike [toQueryString], this uses the HTML form dialect: space becomes `+`, encoding is always
     * UTF-8, and only `* - . _` and ASCII alphanumerics survive unescaped (so literal `+`/`&`/`=`
     * become `%2B`/`%26`/`%3D`). The form dialect has no null, so a `null` value renders as an empty
     * value (`name=`), matching the WHATWG serializer; the no-`=` sentinel is preserved only by
     * [toQueryString]. Serialization is total and never fails.
     *
     * @return the form-encoded body (without a leading `?`); `""` when [isEmpty].
     * @see parseForm to parse such a body back into a snapshot.
     */
    public fun toFormUrlEncoded(): String = FormUrlEncoded.serialize(entries)

    /**
     * Iterates the decoded pairs in appearance order, duplicates included (SPEC §10.3).
     *
     * @return an iterator over each `(name, value?)` pair as a [QueryParameter], in order.
     */
    public override operator fun iterator(): Iterator<QueryParameter> =
        entries.asSequence().map { (name, value) -> QueryParameter(name, value) }.iterator()

    /**
     * A first-value-wins, insertion-ordered [Map] of the distinct names to their first value
     * (SPEC §10.3).
     *
     * Duplicates collapse: only the first pair per name is kept, so the ordering of duplicates and
     * any later values are lost, though a `null` first value is retained. Use [getAll] to recover
     * every value for a name.
     *
     * @return a read-only, insertion-ordered map of each distinct name to its first decoded value.
     */
    public fun toMap(): Map<String, String?> {
        val result = LinkedHashMap<String, String?>(entries.size)
        for ((name, value) in entries) {
            if (name !in result) result[name] = value
        }
        check(result.size <= entries.size) { "distinct names cannot exceed the pair count" }
        return result
    }

    /**
     * A pre-filled [QueryParametersBuilder] over this snapshot's pairs (SPEC §10.3.2).
     *
     * @return a builder seeded with this snapshot's pairs, so an unmodified
     *   [build][QueryParametersBuilder.build] reproduces an equal snapshot.
     */
    public fun newBuilder(): QueryParametersBuilder = QueryParametersBuilder(entries)

    /**
     * Structural equality over the ordered [entries] list.
     *
     * Two snapshots are equal only when they hold the same pairs in the same order; order,
     * duplicates, and the `null`-vs-`""` value distinction are all significant. Consistent with
     * [hashCode].
     *
     * @param other the value to compare against.
     * @return `true` iff [other] is a [QueryParameters] with an identical ordered pair sequence.
     */
    override fun equals(other: Any?): Boolean = other is QueryParameters && other.entries == entries

    /**
     * The hash of the ordered [entries] list, consistent with [equals].
     *
     * @return a hash derived from the ordered pair sequence.
     */
    override fun hashCode(): Int = entries.hashCode()

    /**
     * A readable rendering of the snapshot as its raw query string.
     *
     * @return `"QueryParameters(<query>)"`, where `<query>` is [toQueryString].
     */
    override fun toString(): String = "QueryParameters(${toQueryString()})"

    /** Throws [IndexOutOfBoundsException] when [index] is outside `0 until size`. */
    private fun checkIndex(index: Int) {
        if (index < 0 || index >= entries.size) {
            throw IndexOutOfBoundsException("index $index out of bounds for size ${entries.size}")
        }
    }

    public companion object {
        /** The shared empty snapshot; reused for every absent-query projection (see [parseOrEmpty]). */
        internal val EMPTY: QueryParameters = QueryParameters(emptyList())

        /**
         * Projects a raw [query] to a decoded snapshot, reusing the shared [EMPTY] snapshot when there
         * is nothing to parse.
         *
         * The snapshot helper shared by the `Uri`/`Url` query accessors and their builders. Both an
         * absent query (`null`, no `?`) and a present-but-empty query (`""`, a bare `?`) project to
         * [EMPTY] — matching WHATWG's `URLSearchParams`, which yields no pairs for an empty query — so
         * an edit that starts from a bare `?` does not carry a phantom empty pair into the result. The
         * raw `null`-vs-`""` distinction stays observable on the `query` string property; it is simply
         * not a *pair* in the decoded view. Use [parse] directly to keep `parse("")`'s single sentinel
         * pair.
         */
        internal fun parseOrEmpty(query: String?): QueryParameters = if (query.isNullOrEmpty()) EMPTY else parse(query)

        /**
         * Derives a [QueryParameters] from the raw query [query] (SPEC §10.2.1).
         *
         * A single leading `?` is dropped, then the body is split on `&`; each segment splits on its
         * **first** `=` into `(name, value)` or `(name, null)` when no `=` is present, and both sides
         * are percent-decoded with no plus-as-space. Order and duplicates are preserved,
         * and the sentinels hold (`""` -> one empty pair, `&` -> two empty pairs).
         *
         * Every pair in [query] is materialized: like the WHATWG `URLSearchParams` parser, this
         * factory applies no pair-count cap and never truncates. Work and memory are linear in the
         * query length — each pair consumes at least one input character — so hostile input is
         * bounded by the string the caller already holds, not by any cap here.
         *
         * @param query the raw query string, with or without a single leading `?`.
         * @return the decoded, ordered, duplicate-preserving snapshot of every pair in [query].
         */
        @JvmStatic
        public fun parse(query: String): QueryParameters {
            val body = if (query.startsWith(QUERY_PREFIX)) query.substring(1) else query
            val pairs = ArrayList<Pair<String, String?>>()
            var pos = 0
            while (pos <= body.length) {
                val amp = nextSeparator(body, pos)
                val eq = firstEquals(body, pos, amp)
                pairs.add(splitPair(body, pos, amp, eq))
                pos = amp + 1
            }
            check(pairs.size <= body.length + 1) { "pair count must stay linear in the query length" }
            return QueryParameters(pairs)
        }

        /**
         * Parses an `application/x-www-form-urlencoded` [body] into a snapshot (SPEC §10.4).
         *
         * This is the HTML form dialect, distinct from [parse]: it splits on `&`, **skips** empty
         * segments (leading, trailing, or doubled `&`), decodes `+` as space, and always decodes as
         * UTF-8. A segment with no `=` yields an empty value, never the `null` no-`=` sentinel that
         * [parse] produces, because the form grammar has no such distinction. Order and duplicates are
         * preserved, and no pair-count cap is applied (work stays linear in `body`'s length).
         *
         * @param body the form-encoded body, without a leading `?`.
         * @return the decoded, ordered, duplicate-preserving snapshot of every non-empty segment.
         * @see parse for the generic query decoding used by URL/URI query strings.
         */
        @JvmStatic
        public fun parseForm(body: String): QueryParameters = QueryParameters(FormUrlEncoded.parse(body))

        /**
         * Builds a [QueryParameters] from [pairs] in the map's iteration order (SPEC §10.3.2).
         *
         * One pair is appended per map entry, so the snapshot has no duplicate names and its order
         * follows the map's iteration order (use a [LinkedHashMap] for a predictable order). A `null`
         * value is retained as the no-`=` sentinel. To preserve duplicates, use [parse] or the
         * [QueryParametersBuilder] directly.
         *
         * @param pairs the decoded name-to-value entries; a `null` value is the no-`=` sentinel.
         * @return a snapshot holding one pair per map entry, in the map's iteration order.
         */
        @JvmStatic
        public fun of(pairs: Map<String, String?>): QueryParameters = QueryParametersBuilder().addAll(pairs).build()

        /**
         * Builds a [QueryParameters] from [pairs] in argument order (SPEC §10.3.2).
         *
         * One pair is appended per argument, so ordering and duplicate names are both preserved — the
         * map-keyed `of` overload cannot express duplicates (a map collapses repeated keys), so use
         * this factory when two pairs share a name. A `null` [QueryParameter.value] is retained as the
         * no-`=` sentinel.
         *
         * @param pairs the decoded pairs to include, in order; duplicates are kept.
         * @return a snapshot holding one pair per argument, in argument order.
         */
        @JvmStatic
        public fun of(vararg pairs: QueryParameter): QueryParameters =
            QueryParameters(pairs.map { it.name to it.value })

        /** Index of the next `&` at or after [pos], or `length` when none remains. */
        private fun nextSeparator(
            body: String,
            pos: Int,
        ): Int {
            val amp = body.indexOf(PAIR_SEPARATOR, pos)
            return if (amp < 0) body.length else amp
        }

        /** Index of the first `=` within `[pos, amp)`, or `-1` when none. */
        private fun firstEquals(
            body: String,
            pos: Int,
            amp: Int,
        ): Int {
            val eq = body.indexOf(VALUE_SEPARATOR, pos)
            return if (eq in pos until amp) eq else -1
        }

        /** Decodes one segment into a `(name, value?)` pair using the first-`=` split. */
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

        /** Percent-decodes a raw name or value with `+` kept literal. */
        private fun decode(raw: String): String = PercentCodec.decode(raw)
    }
}
