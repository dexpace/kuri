/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.template

/**
 * The eight RFC 6570 expression operators and their expansion parameters (RFC 6570 §2.2, Appendix A).
 *
 * @property symbol the operator character, or `' '` for the default (no-operator) form.
 * @property first the string prepended before the first defined value in the expression.
 * @property separator the string placed between successive values.
 * @property named whether each value is emitted as `name=value` (`;` `?` `&`).
 * @property ifEmpty what follows a name whose value is the empty string (`"="` for `?`/`&`, else `""`).
 * @property allowReserved whether reserved characters and pct-triplets pass through unencoded (`+` `#`).
 */
internal enum class Operator(
    val symbol: Char,
    val first: String,
    val separator: String,
    val named: Boolean,
    val ifEmpty: String,
    val allowReserved: Boolean,
) {
    SIMPLE(' ', "", ",", false, "", false),
    RESERVED('+', "", ",", false, "", true),
    FRAGMENT('#', "#", ",", false, "", true),
    LABEL('.', ".", ".", false, "", false),
    PATH('/', "/", "/", false, "", false),
    PARAMETER(';', ";", ";", true, "", false),
    QUERY('?', "?", "&", true, "=", false),
    CONTINUATION('&', "&", "&", true, "=", false),
    ;

    internal companion object {
        /** Reserved-for-future operators (RFC 6570 §2.2) that a conforming parser must reject. */
        private const val RESERVED_SYMBOLS = "=,!@|"

        /**
         * Resolves the operator for [symbol], returning [SIMPLE] paired with `consumed = false` when the
         * first body character is not an operator (it belongs to the first varname).
         *
         * @return the operator and whether [symbol] was consumed as an operator character.
         * @throws UriTemplateException when [symbol] is a reserved-for-future operator.
         */
        fun resolve(symbol: Char): Pair<Operator, Boolean> {
            entries.firstOrNull { it.symbol == symbol && it != SIMPLE }?.let { return it to true }
            if (symbol in RESERVED_SYMBOLS) {
                throw UriTemplateException("reserved operator '$symbol' is not allowed")
            }
            return SIMPLE to false
        }
    }
}
