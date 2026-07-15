/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.template

import org.dexpace.kuri.Uri
import org.dexpace.kuri.Url
import org.dexpace.kuri.error.ParseResult
import kotlin.jvm.JvmStatic
import kotlin.jvm.JvmSynthetic

/** One parsed template fragment: verbatim [Literal] text or a `{…}` [Expression]. */
internal sealed interface Part {
    data class Literal(
        val text: String,
    ) : Part

    data class Expression(
        val operator: Operator,
        val varspecs: List<Varspec>,
    ) : Part
}

/** A single variable reference within an expression: a name plus an optional modifier. */
internal data class Varspec(
    val name: String,
    val explode: Boolean,
    val prefix: Int?,
)

/**
 * An [RFC 6570](https://www.rfc-editor.org/rfc/rfc6570) URI Template — the standards-based, string-first
 * companion to `kuri-bind`'s annotation binding. Parse a template once, then [expand] it against a set
 * of variables to produce a URI reference string.
 *
 * All four RFC levels are supported: simple `{var}`, the reserved (`+`), fragment (`#`), label (`.`),
 * path (`/`), path-parameter (`;`), query (`?`), and continuation (`&`) operators, plus the prefix
 * (`{var:3}`) and explode (`{list*}`) modifiers over string, list, and associative-array values.
 *
 * ```
 * val t = UriTemplate.parse("https://api.example.com/users/{id}{?fields*,page}")
 * t.expand(mapOf("id" to 42, "fields" to listOf("a", "b"), "page" to 2))
 * // → https://api.example.com/users/42?fields=a&fields=b&page=2
 * ```
 *
 * Instances are immutable and thread-safe. Expansion never throws; an undefined variable (absent, or a
 * `null`/empty list/empty map value) contributes nothing, exactly as RFC 6570 §3.2.1 prescribes.
 *
 * @property template the original template string this was parsed from.
 */
public class UriTemplate internal constructor(
    internal val parts: List<Part>,
    public val template: String,
) {
    /** The distinct variable names referenced by the template, in first-appearance order. */
    public val variableNames: Set<String> =
        parts
            .filterIsInstance<Part.Expression>()
            .flatMap { expression -> expression.varspecs }
            .map { varspec -> varspec.name }
            .toSet()

    /**
     * Expands the template against [variables], returning the resulting URI-reference string.
     *
     * A value may be a `String` (scalar), a `List<*>`/`Iterable<*>` (list), a `Map<*,*>` (associative
     * array), or any other non-null value (rendered via `toString()`). A `null`, empty list, or empty
     * map is treated as undefined and contributes nothing.
     *
     * @param variables the variable bindings; keys absent from the map are undefined.
     * @return the expanded URI reference (never `null`; empty when every expression is undefined).
     */
    public fun expand(variables: Map<String, Any?>): String {
        val out = StringBuilder(template.length)
        for (part in parts) {
            when (part) {
                is Part.Literal -> out.append(part.text)
                is Part.Expression -> expandExpression(part, variables, out)
            }
        }
        return out.toString()
    }

    /** Kotlin convenience: `template.expand("id" to 42, "page" to 2)`. */
    @JvmSynthetic
    public fun expand(vararg variables: Pair<String, Any?>): String = expand(variables.toMap())

    /**
     * Expands the template and parses the result as a WHATWG [Url].
     *
     * @param variables the variable bindings.
     * @return `Ok` with the [Url], or `Err` when the expansion is not a valid URL.
     */
    public fun expandToUrl(variables: Map<String, Any?>): ParseResult<Url> = Url.parse(expand(variables))

    /**
     * Expands the template and parses the result as a generic RFC 3986 [Uri].
     *
     * @param variables the variable bindings.
     * @return `Ok` with the [Uri], or `Err` when the expansion is not a valid URI.
     */
    public fun expandToUri(variables: Map<String, Any?>): ParseResult<Uri> = Uri.parse(expand(variables))

    /** Structural over the source [template]. */
    override fun equals(other: Any?): Boolean = other is UriTemplate && other.template == template

    /** Consistent with [equals]. */
    override fun hashCode(): Int = template.hashCode()

    /** The original [template] string. */
    override fun toString(): String = template

    /** Parse entry points for [UriTemplate]. */
    public companion object {
        /**
         * Parses [template] into a reusable [UriTemplate].
         *
         * @param template an RFC 6570 template string.
         * @return the parsed template.
         * @throws UriTemplateException when [template] is syntactically malformed.
         */
        @JvmStatic
        public fun parse(template: String): UriTemplate = UriTemplate(parseParts(template), template)

        /**
         * Parses [template], returning `null` instead of throwing on malformed input.
         *
         * @param template an RFC 6570 template string.
         * @return the parsed template, or `null` when malformed.
         */
        @JvmStatic
        public fun parseOrNull(template: String): UriTemplate? =
            try {
                parse(template)
            } catch (_: UriTemplateException) {
                null
            }
    }
}
