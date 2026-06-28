/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.percent

import org.dexpace.kuri.text.isAsciiAlphanumeric

/**
 * A percent-encode set (SPEC §5.1): the decision, per ASCII code point, of whether a component
 * MUST emit that code point as a `%XX` triplet on serialization.
 *
 * The explicit per-set list of §5.1.3 is modelled as a 128-entry decision table over ASCII
 * (`true` = MUST encode). Layered on top is the universal rule of [PCT-1] that every C0 control,
 * DEL, and non-ASCII code point is always encoded: C0 controls and DEL are baked into the table,
 * and code points above U+007F are encoded by [shouldEncode] without a table lookup because each
 * is non-ASCII and therefore selected by [PCT-1](d) under the Url-profile default this module
 * implements. U+0025 (`%`) is deliberately governable by a set ([PCT-40] component set) but is
 * otherwise left to the already-encoded rules of §5.5, so it is only ever a table member where a
 * set explicitly adds it.
 */
internal class PercentEncodeSet private constructor(
    private val encodeAscii: BooleanArray,
) {
    init {
        require(encodeAscii.size == ASCII_TABLE_SIZE) {
            "encode table must cover every ASCII code point, was ${encodeAscii.size}"
        }
        // The universal rule is folded into every table: NUL and DEL always encode ([PCT-1]).
        check(encodeAscii[0] && encodeAscii[DEL_CODE]) {
            "encode table must always encode C0 controls and DEL"
        }
    }

    /**
     * Returns `true` when [codePoint] MUST be percent-encoded under this set (SPEC §5.1, [PCT-1]).
     *
     * Non-ASCII code points (`>= U+0080`) always encode via [PCT-1](d); ASCII code points consult
     * the table, which already folds in the always-on C0-control and DEL conditions ([PCT-1](a),
     * (b)). Total over all scalar values: it never throws for a non-negative input.
     *
     * @param codePoint a Unicode scalar value; MUST be non-negative.
     * @return whether the code point is a member of this set under the universal rule.
     */
    internal fun shouldEncode(codePoint: Int): Boolean {
        require(codePoint >= 0) { "code point must be non-negative: $codePoint" }
        return codePoint >= ASCII_TABLE_SIZE || encodeAscii[codePoint]
    }

    /**
     * Returns a new set equal to this one with [additions] forced to encode, modelling the
     * incremental "base set plus these code points" definitions of §5.1.3 (e.g. the path set is
     * the query set plus `?`, backtick, `{`, `}`, `^`).
     *
     * The receiver is not mutated (the table is copied), preserving the immutability of every
     * shared named set in [PercentEncodeSets].
     *
     * @param additions printable-ASCII code points (U+0020–U+007E) to add to the explicit list.
     * @return a new set; the receiver is left unchanged.
     */
    internal fun including(vararg additions: Char): PercentEncodeSet {
        require(additions.isNotEmpty()) { "including requires at least one code point" }
        val copy = encodeAscii.copyOf()
        for (c in additions) {
            require(c.code in PRINTABLE_MIN..PRINTABLE_MAX) {
                "explicit set member out of printable ASCII range: ${c.code}"
            }
            copy[c.code] = true
        }
        return PercentEncodeSet(copy)
    }

    internal companion object {
        /** Number of ASCII code points the decision table covers, U+0000–U+007F. */
        private const val ASCII_TABLE_SIZE: Int = 128

        /** Inclusive upper bound of the C0 control range, U+001F (SPEC §4.2.1). */
        private const val C0_CONTROL_MAX_CODE: Int = 0x1F

        /** The DEL code point, U+007F, always encoded by [PCT-1](b). */
        private const val DEL_CODE: Int = 0x7F

        /** Inclusive lower bound of the printable ASCII range, U+0020 (space). */
        private const val PRINTABLE_MIN: Int = 0x20

        /** Inclusive upper bound of the printable ASCII range, U+007E (`~`). */
        private const val PRINTABLE_MAX: Int = 0x7E

        /** The four punctuation code points the form set passes through besides alphanumerics. */
        private const val FORM_UNRESERVED_PUNCTUATION: String = "*-._"

        /**
         * The C0-control percent-encode set ([PCT-5]): an empty explicit list. Every other named
         * set is built by adding code points to a copy of this one.
         */
        internal fun c0Control(): PercentEncodeSet = PercentEncodeSet(baseEncodeArray())

        /**
         * The `application/x-www-form-urlencoded` set ([PCT-13]): defined by exclusion rather than
         * an explicit list, so it is constructed directly rather than via [including].
         */
        internal fun formUrlEncoded(): PercentEncodeSet = PercentEncodeSet(formEncodeArray())

        /** Builds the base table with C0 controls and DEL pre-marked, every printable cell clear. */
        private fun baseEncodeArray(): BooleanArray {
            val array = BooleanArray(ASCII_TABLE_SIZE)
            var code = 0
            while (code <= C0_CONTROL_MAX_CODE) {
                array[code] = true
                code++
            }
            array[DEL_CODE] = true
            return array
        }

        /** Builds the form table: every cell encodes except ASCII alphanumerics and `*` `-` `.` `_`. */
        private fun formEncodeArray(): BooleanArray {
            val array = BooleanArray(ASCII_TABLE_SIZE)
            var code = 0
            while (code < ASCII_TABLE_SIZE) {
                array[code] = !isFormUnreserved(code.toChar())
                code++
            }
            return array
        }

        /** True when [c] passes through the form set unencoded ([PCT-13]). */
        private fun isFormUnreserved(c: Char): Boolean = c.isAsciiAlphanumeric() || c in FORM_UNRESERVED_PUNCTUATION
    }
}

/**
 * The named percent-encode sets of SPEC §5.1.3, each built from the previous per the spec's
 * incremental definitions. These are the shared, immutable sets the codec and the component
 * serializers select between; membership was verified code-point-for-code-point against the §5.1
 * master matrix.
 */
internal object PercentEncodeSets {
    /** [PCT-5] empty explicit list; encodes only C0 controls, DEL, and non-ASCII. */
    internal val C0_CONTROL: PercentEncodeSet = PercentEncodeSet.c0Control()

    /** [PCT-6] space, double-quote, less-than, greater-than, backtick. */
    internal val FRAGMENT: PercentEncodeSet = C0_CONTROL.including(' ', '"', '<', '>', '`')

    /** [PCT-7] space, double-quote, hash, less-than, greater-than. */
    internal val QUERY: PercentEncodeSet = C0_CONTROL.including(' ', '"', '#', '<', '>')

    /** [PCT-8] the query set plus apostrophe; used for special-scheme queries. */
    internal val SPECIAL_QUERY: PercentEncodeSet = QUERY.including('\'')

    /** [PCT-9] the query set plus `?`, backtick, `{`, `}`, `^` (ada path bitmap, includes `^`). */
    internal val PATH: PercentEncodeSet = QUERY.including('?', '`', '{', '}', '^')

    /** [PCT-10] the path set plus `/`, `:`, `;`, `=`, `@`, `[`, `\`, `]`, `|`. */
    internal val USERINFO: PercentEncodeSet =
        PATH.including('/', ':', ';', '=', '@', '[', '\\', ']', '|')

    /** [PCT-40] the userinfo set plus `$`, `%`, `&`, `+`, `,` (encodeURIComponent equivalent). */
    internal val COMPONENT: PercentEncodeSet = USERINFO.including('$', '%', '&', '+', ',')

    /** [PCT-13] passes only ASCII alphanumerics and `*` `-` `.` `_`; encodes everything else. */
    internal val FORM_URLENCODED: PercentEncodeSet = PercentEncodeSet.formUrlEncoded()
}
