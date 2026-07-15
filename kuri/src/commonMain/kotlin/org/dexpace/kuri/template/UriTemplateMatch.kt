/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.template

import org.dexpace.kuri.percent.Percent
import org.dexpace.kuri.query.QueryParameters

/*
 * Best-effort reverse matching — an EXTENSION beyond RFC 6570, which defines expansion only. It inverts
 * the common path-routing shape and returns decoded variable bindings, or `null` when the template uses
 * a feature this matcher does not invert unambiguously (explode, prefix, or a multi-value non-trailing
 * named expression). It is deliberately conservative: a `null` means "not matchable here", never a
 * silent wrong answer. For anything richer, expand-and-compare or a dedicated router is the right tool.
 */

/**
 * Attempts to match [input] against this template, extracting decoded variable values.
 *
 * Supported: literal text; single-variable path-style expressions (`{var}`, `{+var}`, `{#var}`,
 * `{.var}`, `{/var}`); and a single trailing query expression (`{?a,b}` / `{&a,b}`). Explode (`*`),
 * prefix (`:n`), and named expressions anywhere but the tail are unsupported.
 *
 * ```
 * val t = UriTemplate.parse("/users/{id}{?tab}")
 * t.match("/users/42?tab=repos")   // {id=42, tab=repos}
 * t.match("/posts/42")             // null — literal mismatch
 * ```
 *
 * @param input the URI-reference string to match.
 * @return a map of variable name to decoded value, or `null` when the input does not match or the
 * template uses an uninvertible feature.
 */
public fun UriTemplate.match(input: String): Map<String, String>? {
    val trailingQuery = (parts.lastOrNull() as? Part.Expression)?.takeIf { it.operator.named }
    if (trailingQuery != null && !isSupportedQuery(trailingQuery)) return null

    val pathParts = if (trailingQuery != null) parts.dropLast(1) else parts
    val (pathInput, queryInput) = splitQuery(input, hasTrailingQuery = trailingQuery != null)
    return matchPath(pathParts, pathInput)?.let { pathValues ->
        val queryValues = if (trailingQuery != null) matchQuery(trailingQuery, queryInput) else emptyMap()
        pathValues + queryValues
    }
}

/** Builds a numbered-group regex from the non-query parts and matches [input] against it. */
private fun matchPath(
    pathParts: List<Part>,
    input: String,
): Map<String, String>? {
    val (pattern, names) = buildPathPattern(pathParts) ?: return null
    return Regex(pattern).matchEntire(input)?.let { match ->
        names.mapIndexed { index, name -> name to Percent.decode(match.groupValues[index + 1]) }.toMap()
    }
}

/** Builds the numbered-group regex source and captured variable names for [pathParts], or `null` when
 * one of the parts uses an uninvertible expression feature. */
private fun buildPathPattern(pathParts: List<Part>): Pair<String, List<String>>? {
    val pattern = StringBuilder()
    val names = mutableListOf<String>()
    for (part in pathParts) {
        when (part) {
            is Part.Literal -> pattern.append(Regex.escape(part.text))
            is Part.Expression -> {
                val group = pathGroup(part) ?: return null
                pattern.append(group.first)
                names.add(group.second)
            }
        }
    }
    return pattern.toString() to names
}

/** Regex fragment + captured variable name for one supported path expression, or `null` if unsupported. */
private fun pathGroup(expression: Part.Expression): Pair<String, String>? =
    expression.varspecs.singleOrNull()?.let { varspec ->
        capturePatternFor(expression.operator, varspec)?.let { capture ->
            (Regex.escape(expression.operator.first) + capture) to varspec.name
        }
    }

/** Regex capture fragment for [operator] on a plain (non-explode, non-prefix) varspec, or `null`. */
private fun capturePatternFor(
    operator: Operator,
    varspec: Varspec,
): String? {
    if (varspec.explode || varspec.prefix != null) return null
    return when (operator) {
        Operator.SIMPLE, Operator.PATH, Operator.LABEL -> "([^/?#]*)"
        Operator.RESERVED, Operator.FRAGMENT -> "(.*)"
        else -> null
    }
}

/** A trailing query expression is invertible only when every varspec is a plain name. */
private fun isSupportedQuery(expression: Part.Expression): Boolean =
    expression.varspecs.all { varspec -> !varspec.explode && varspec.prefix == null }

/** Pulls each named query variable out of the parsed query string (absent names are omitted). */
private fun matchQuery(
    expression: Part.Expression,
    queryInput: String,
): Map<String, String> {
    if (queryInput.isEmpty()) return emptyMap()
    val params: QueryParameters = QueryParameters.parse(queryInput)
    val values = mutableMapOf<String, String>()
    for (varspec in expression.varspecs) {
        params[varspec.name]?.let { value -> values[varspec.name] = value }
    }
    return values
}

/** Splits [input] into (path, query) at the first `?`, only when a trailing query is expected. */
private fun splitQuery(
    input: String,
    hasTrailingQuery: Boolean,
): Pair<String, String> {
    if (!hasTrailingQuery) return input to ""
    val q = input.indexOf('?')
    return if (q < 0) input to "" else input.substring(0, q) to input.substring(q + 1)
}
