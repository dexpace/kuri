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
     * space, then percent-decodes as UTF-8. The pair count is bounded by [MAX_PAIRS] ([QUERY-24]):
     * once the cap is reached, parsing stops and records no further pairs.
     */
    internal fun parse(input: String): List<Pair<String, String>> {
        val segments = input.split(PAIR_DELIMITER)
        val pairs = ArrayList<Pair<String, String>>()
        var i = 0
        while (i < segments.size && pairs.size < MAX_PAIRS) {
            val segment = segments[i]
            if (segment.isNotEmpty()) pairs.add(decodeSegment(segment))
            i++
        }
        check(pairs.size <= MAX_PAIRS) { "form parse exceeded the pair bound" }
        return pairs
    }

    /**
     * Serializes [pairs] per the WHATWG form serializer (SPEC §10.4, [QUERY-20]).
     *
     * Joins pairs with `&`, emits `name=value`, and encodes each octet over UTF-8: space becomes
     * `+`, ASCII alphanumerics and `* - . _` pass through, and every other octet becomes `%XX`.
     * Thus literal `+` -> `%2B`, `&` -> `%26`, `=` -> `%3D`.
     */
    internal fun serialize(pairs: List<Pair<String, String>>): String =
        pairs.joinToString(PAIR_DELIMITER) { (name, value) ->
            encodeComponent(name) + NAME_VALUE_DELIMITER + encodeComponent(value)
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
