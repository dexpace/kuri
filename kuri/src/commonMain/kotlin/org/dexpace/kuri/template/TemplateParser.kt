/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.template

/**
 * Splits a template into a flat [Part] list of literals and expressions (RFC 6570 §2).
 *
 * @param template the raw template string.
 * @return the ordered parts.
 * @throws UriTemplateException on an unterminated `{` or a stray `}`.
 */
internal fun parseParts(template: String): List<Part> {
    val parts = mutableListOf<Part>()
    val literal = StringBuilder()
    var i = 0
    while (i < template.length) {
        when (val c = template[i]) {
            '{' -> {
                flushLiteral(literal, parts)
                val close = template.indexOf('}', startIndex = i + 1)
                if (close < 0) throw UriTemplateException("unterminated expression", i)
                parts.add(parseExpression(template.substring(i + 1, close), i))
                i = close + 1
            }
            '}' -> throw UriTemplateException("unexpected '}'", i)
            else -> {
                literal.append(c)
                i++
            }
        }
    }
    flushLiteral(literal, parts)
    return parts
}

/** Emits the accumulated literal (percent-encoding disallowed characters) and clears the buffer. */
private fun flushLiteral(
    literal: StringBuilder,
    parts: MutableList<Part>,
) {
    if (literal.isEmpty()) return
    parts.add(Part.Literal(pctEncode(literal.toString(), allowReserved = true)))
    literal.clear()
}

/** Parses one `{…}` body (operator + comma-separated varspecs) at template offset [at]. */
private fun parseExpression(
    body: String,
    at: Int,
): Part.Expression {
    if (body.isEmpty()) throw UriTemplateException("empty expression", at)
    val (operator, consumed) = Operator.resolve(body[0])
    val varlist = if (consumed) body.substring(1) else body
    if (varlist.isEmpty()) throw UriTemplateException("expression has no variables", at)
    return Part.Expression(operator, varlist.split(',').map { spec -> parseVarspec(spec, at) })
}

/** Parses one varspec: `name`, `name*` (explode), or `name:N` (prefix, 1..9999). */
private fun parseVarspec(
    spec: String,
    at: Int,
): Varspec {
    if (spec.endsWith('*')) {
        val name = spec.dropLast(1)
        validateName(name, at)
        return Varspec(name, explode = true, prefix = null)
    }
    return parsePlainOrPrefixedVarspec(spec, at)
}

/** Parses the non-explode varspec forms: `name` or `name:N` (prefix). */
private fun parsePlainOrPrefixedVarspec(
    spec: String,
    at: Int,
): Varspec {
    val colon = spec.indexOf(':')
    if (colon < 0) {
        validateName(spec, at)
        return Varspec(spec, explode = false, prefix = null)
    }
    val name = spec.substring(0, colon)
    validateName(name, at)
    val rawPrefix = spec.substring(colon + 1)
    // RFC 6570 §2.4.1: max-length = %x31-39 0*3DIGIT — the first character must be a digit
    // 1-9 and any remaining characters must be digits, which rules out a leading '+'/'-' sign
    // and leading zeros that `String.toIntOrNull()` would otherwise accept silently.
    val hasValidShape =
        rawPrefix.isNotEmpty() &&
            rawPrefix[0] in '1'..'9' &&
            (1 until rawPrefix.length).all { rawPrefix[it] in '0'..'9' }
    val prefix = if (hasValidShape) rawPrefix.toIntOrNull() else null
    if (prefix == null || prefix !in 1..MAX_PREFIX) {
        throw UriTemplateException("invalid prefix in '$spec'", at)
    }
    return Varspec(name, explode = false, prefix = prefix)
}

/**
 * Validates a varname against the RFC 6570 grammar `varname = varchar *( ["."] varchar )`: a dot is
 * accepted only strictly between two varchars, so a leading dot, a trailing dot, and consecutive dots
 * (`.foo`, `foo.`, `a..b`) are all rejected, not just an isolated invalid character.
 */
private fun validateName(
    name: String,
    at: Int,
) {
    if (name.isEmpty()) throw UriTemplateException("empty variable name", at)
    var i = requireVarchar(name, 0, at)
    while (i < name.length) {
        if (name[i] == '.') {
            i++
            if (i >= name.length) throw UriTemplateException("variable name '$name' cannot end in '.'", at)
        }
        i += requireVarchar(name, i, at)
    }
}

/** Consumes and validates one `varchar` (ALPHA / DIGIT / `_` / pct-encoded) at [i], returning its length. */
private fun requireVarchar(
    name: String,
    i: Int,
    at: Int,
): Int {
    val c = name[i]
    return when {
        c == '%' -> validatePercentEncodedTriplet(name, i, at)
        isVarchar(c) -> 1
        else -> throw UriTemplateException("invalid character in variable name '$name'", at)
    }
}

/** Validates the `%HH` triplet starting at [i] in [name] and returns its length (always 3). */
private fun validatePercentEncodedTriplet(
    name: String,
    i: Int,
    at: Int,
): Int {
    if (i + 2 >= name.length || !isHex(name[i + 1]) || !isHex(name[i + 2])) {
        throw UriTemplateException("bad percent-encoding in variable name '$name'", at)
    }
    return PERCENT_TRIPLET_LENGTH
}

/** RFC 6570 prefix modifier upper bound (`max-length = %x31-39 0*3DIGIT`, i.e. up to 9999). */
private const val MAX_PREFIX: Int = 9999

/** Length of a percent-encoded triplet, `%XX`. */
private const val PERCENT_TRIPLET_LENGTH: Int = 3

private fun isVarchar(c: Char): Boolean = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '_'

internal fun isHex(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
