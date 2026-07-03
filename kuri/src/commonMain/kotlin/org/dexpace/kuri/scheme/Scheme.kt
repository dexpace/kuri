/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.scheme

import org.dexpace.kuri.text.isAsciiAlpha
import org.dexpace.kuri.text.isAsciiDigit

/** ASCII distance from an upper-case letter to its lower-case counterpart (`'a' - 'A'`, 32). */
private const val ASCII_CASE_OFFSET: Int = 'a' - 'A'

/**
 * Lower-cases a single ASCII letter, leaving every other code point untouched (SPEC §6.3).
 *
 * Mapping is by exact numeric range so it is locale-invariant: no Unicode case folding and
 * no Turkish dotless-`i` mapping can occur ([SCH-16]). A valid scheme is ASCII-only, so this
 * is total over every code point a scheme can contain.
 */
private fun Char.asciiLowercasedChar(): Char = if (this in 'A'..'Z') this + ASCII_CASE_OFFSET else this

/**
 * True when [c] may appear after the first code point of a scheme: ASCII alpha, ASCII digit,
 * `+`, `-`, or `.` (SPEC §6.2, Table 6-2; [SCH-8]).
 */
private fun isSchemeTailChar(c: Char): Boolean =
    c.isAsciiAlpha() || c.isAsciiDigit() || c == '+' || c == '-' || c == '.'

/**
 * The index of the `:` in [text] that would introduce a `scheme:` prefix — the first `:` when it
 * precedes any `/` (RFC 3986 §3.1) — or `-1` when the first segment carries no such colon.
 *
 * This is the single source of the "a colon before the first slash reads as a scheme" rule shared
 * by the parser's scheme detection and the serializer/builder recomposition guards.
 */
internal fun schemeColonIndex(text: String): Int {
    val colon = text.indexOf(':')
    val slash = text.indexOf('/')
    return if (colon >= 0 && (slash < 0 || colon < slash)) colon else -1
}

/**
 * Syntax, normalization, and special-scheme queries for the URI/URL scheme component (SPEC §6).
 *
 * The grammar and the special-scheme registry are identical in both profiles; only the
 * *behaviours* keyed off "is special" differ by profile (§6.5). These functions cover the
 * profile-independent facts: validity ([SCH-7], [SCH-8]), lower-casing ([SCH-15]), and the
 * Table 6-1 lookups for special-ness ([SCH-3]) and default port ([SCH-18]).
 */
internal object Scheme {
    /**
     * True when [value] is a syntactically valid scheme (SPEC §6.2; [SCH-7], [SCH-8], [SCH-10]).
     *
     * A valid scheme is non-empty, begins with an ASCII alpha, and continues with ASCII
     * alpha / digit / `+` / `-` / `.`. Validity is independent of case and of whether the
     * scheme is special.
     *
     * @param value the candidate scheme text, exactly as parsed (no surrounding `:`).
     * @return `true` iff [value] satisfies Table 6-2.
     */
    fun isValidScheme(value: String): Boolean {
        // Empty input is an explicit non-scheme per [SCH-10]; guard before indexing value[0].
        if (value.isEmpty()) {
            return false
        }
        val firstIsAlpha = value[0].isAsciiAlpha()
        val tailIsValid = (1 until value.length).all { isSchemeTailChar(value[it]) }
        return firstIsAlpha && tailIsValid
    }

    /**
     * Returns the canonical lower-cased form of [value] (SPEC §6.3; [SCH-15], [SCH-16]).
     *
     * Lower-casing is unconditional in both profiles because the scheme is case-insensitive
     * and the mapping is loss-free; original case is never retained ([SCH-17]). The mapping
     * is ASCII-only and length-preserving.
     *
     * @param value the scheme text to normalize.
     * @return [value] with every ASCII `A`–`Z` mapped to `a`–`z`.
     */
    fun normalize(value: String): String {
        val normalized = buildString(value.length) { value.forEach { append(it.asciiLowercasedChar()) } }
        check(normalized.length == value.length) { "scheme normalization changed length: $value" }
        check(normalized.none { it in 'A'..'Z' }) { "scheme normalization left upper-case: $normalized" }
        return normalized
    }

    /**
     * True when [scheme] is one of the six special schemes (SPEC §6.1.1; [SCH-3], [SCH-5]).
     *
     * The input is normalized first so a scheme differing only in case (e.g. `HTTP`) still
     * resolves; a scheme differing in any non-case respect is not special.
     *
     * @param scheme the scheme text, in any case.
     * @return `true` iff the normalized scheme appears in Table 6-1.
     */
    fun isSpecial(scheme: String): Boolean = SpecialScheme.fromName(normalize(scheme)) != null

    /**
     * Returns the default port of [scheme], or `null` when it has none (SPEC §6.4; [SCH-18]).
     *
     * A non-special scheme has no default port, and `file` is special yet portless ([SCH-2]);
     * both therefore yield `null`. The input is normalized before lookup ([SCH-3]).
     *
     * @param scheme the scheme text, in any case.
     * @return the Table 6-1 default port, or `null` for `file` and every non-special scheme.
     */
    fun defaultPort(scheme: String): Int? = SpecialScheme.fromName(normalize(scheme))?.defaultPort
}
