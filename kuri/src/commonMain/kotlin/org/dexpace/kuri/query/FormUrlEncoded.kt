/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.query

import org.dexpace.kuri.percent.PercentCodec
import org.dexpace.kuri.percent.PercentEncodeSets

/** The `&` that joins and splits `application/x-www-form-urlencoded` pairs (SPEC §10.4). */
private const val PAIR_DELIMITER: String = "&"

/** The `=` that splits the first name/value boundary of a form segment (SPEC §10.4). */
private const val NAME_VALUE_DELIMITER: String = "="

/**
 * The `application/x-www-form-urlencoded` codec (SPEC §10.4), a dialect kept deliberately separate
 * from the generic query model. It is the only place where `+` means space ([QUERY-23]) and it
 * always uses UTF-8 ([QUERY-22]).
 */
internal object FormUrlEncoded {
    /**
     * Parses [input] per the WHATWG form parser (SPEC §10.4, [QUERY-21]).
     *
     * Splits on `&`, **skips every empty segment** (leading, trailing, or doubled `&`), splits each
     * remaining segment on its first `=` (no `=` yields an empty value, never `null`), maps `+` to
     * space, then percent-decodes as UTF-8. Every pair is kept: the WHATWG form parser has no pair
     * cap ([QUERY-24]), and work and memory stay linear in the input length.
     */
    internal fun parse(input: String): List<Pair<String, String>> {
        val segments = input.split(PAIR_DELIMITER)
        val pairs = ArrayList<Pair<String, String>>(segments.size)
        for (segment in segments) {
            if (segment.isNotEmpty()) pairs.add(decodeSegment(segment))
        }
        check(pairs.size <= segments.size) { "form parse must yield at most one pair per segment" }
        return pairs
    }

    /**
     * Serializes [pairs] per the WHATWG form serializer (SPEC §10.4, [QUERY-20]).
     *
     * Joins pairs with `&`, emits `name=value`, and encodes each octet over UTF-8: space becomes
     * `+`, ASCII alphanumerics and `* - . _` pass through, and every other octet becomes `%XX`.
     * Thus literal `+` -> `%2B`, `&` -> `%26`, `=` -> `%3D`.
     *
     * The value is `String?` so this bridges the generic query model, whose no-`=` sentinel is `null`.
     * The `=` is always emitted, matching the WHATWG form serializer, which joins name and value with
     * `=` unconditionally: a `null` value serializes as an empty value (`name=`), which the form parser
     * reads back as that same empty value, so the round-trip is stable. Only the generic query
     * serializer (`toQueryString`) preserves the no-`=` sentinel.
     */
    internal fun serialize(pairs: List<Pair<String, String?>>): String =
        pairs.joinToString(PAIR_DELIMITER) { (name, value) ->
            encodeComponent(name) + NAME_VALUE_DELIMITER + encodeComponent(value.orEmpty())
        }

    /** Splits a non-empty segment on its first `=` and decodes both sides ([QUERY-21]). */
    private fun decodeSegment(segment: String): Pair<String, String> {
        require(segment.isNotEmpty()) { "form segment must be non-empty after the empty-skip" }
        val eq = segment.indexOf(NAME_VALUE_DELIMITER)
        val rawName = if (eq < 0) segment else segment.substring(0, eq)
        val rawValue = if (eq < 0) "" else segment.substring(eq + 1)
        return decodeComponent(rawName) to decodeComponent(rawValue)
    }

    /** Form-decodes one component: `+` to space, then UTF-8 percent-decode ([QUERY-21]). */
    private fun decodeComponent(raw: String): String = PercentCodec.decode(raw, plusAsSpace = true)

    /** Form-encodes one component with the form set and space rendered as `+` ([QUERY-20]). */
    private fun encodeComponent(text: String): String =
        PercentCodec.encode(text, PercentEncodeSets.FORM_URLENCODED, spaceAsPlus = true)
}
