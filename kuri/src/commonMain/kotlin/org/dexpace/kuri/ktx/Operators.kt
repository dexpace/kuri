/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.ktx

import org.dexpace.kuri.Uri
import org.dexpace.kuri.Url
import kotlin.jvm.JvmSynthetic

/*
 * Kotlin-only operator conveniences over the immutable [Url]/[Uri] value types. Every declaration here
 * is `@JvmSynthetic`: it is invisible from Java, so the ergonomic Kotlin surface never widens (or
 * competes with) the deliberately plain Java API. Each operator returns a NEW value — the inputs are
 * never mutated — and delegates to the core builder, so behaviour is identical to the equivalent
 * `newBuilder()…build()` call. For multi-step edits prefer `edit { }` (one builder, one build) over a
 * chain of value operators (a build per step); see Dsl.kt.
 */

/**
 * Appends one decoded path [segment], percent-encoding it, and returns the new [Url].
 *
 * `base / "v2" / "users"` reads as the path it builds. Equivalent to
 * `newBuilder().addPathSegment(segment).build()`, so it follows `build()`'s validation semantics.
 *
 * @param segment the decoded segment to append; the builder percent-encodes it.
 * @return a new [Url] with [segment] appended to the path.
 */
@JvmSynthetic
public operator fun Url.div(segment: String): Url = newBuilder().addPathSegment(segment).build()

/**
 * Appends one decoded path [segment] to a generic [Uri], percent-encoding it, and returns the new value.
 *
 * @param segment the decoded segment to append; the builder percent-encodes it.
 * @return a new [Uri] with [segment] appended to the path.
 */
@JvmSynthetic
public operator fun Uri.div(segment: String): Uri = newBuilder().addPathSegment(segment).build()

/**
 * Appends a query [parameter] (`name to value`, `value` nullable for a bare `?name`) and returns the
 * new [Url]. Duplicates are preserved, matching `addQueryParameter`.
 *
 * `base + ("page" to "2")` appends `page=2`.
 *
 * @param parameter the decoded name/value pair to append.
 * @return a new [Url] carrying the appended parameter.
 */
@JvmSynthetic
public operator fun Url.plus(parameter: Pair<String, String?>): Url =
    newBuilder().addQueryParameter(parameter.first, parameter.second).build()

/**
 * Appends every entry of [parameters] as a query parameter, in iteration order, and returns the new [Url].
 *
 * @param parameters the decoded name/value pairs to append.
 * @return a new [Url] carrying every appended parameter.
 */
@JvmSynthetic
public operator fun Url.plus(parameters: Map<String, String?>): Url {
    val builder = newBuilder()
    for ((name, value) in parameters) builder.addQueryParameter(name, value)
    return builder.build()
}

/**
 * Appends a query [parameter] to a generic [Uri] and returns the new value.
 *
 * @param parameter the decoded name/value pair to append.
 * @return a new [Uri] carrying the appended parameter.
 */
@JvmSynthetic
public operator fun Uri.plus(parameter: Pair<String, String?>): Uri =
    newBuilder().addQueryParameter(parameter.first, parameter.second).build()

/**
 * Reads the first decoded value for query [name], or `null` when the name is absent (use
 * [org.dexpace.kuri.query.QueryParameters.has] to distinguish absent from present-without-`=`).
 *
 * `url["page"]` reads the first `page` value.
 *
 * @param name the query parameter name.
 * @return the first decoded value, or `null` when absent or valueless.
 */
@JvmSynthetic
public operator fun Url.get(name: String): String? = queryParameters[name]

/**
 * Reads the first decoded value for query [name] on a generic [Uri], or `null` when absent.
 *
 * @param name the query parameter name.
 * @return the first decoded value, or `null` when absent or valueless.
 */
@JvmSynthetic
public operator fun Uri.get(name: String): String? = queryParameters()[name]

/**
 * `true` when the query carries [name] at least once, enabling `"page" in url`.
 *
 * @param name the query parameter name.
 * @return whether the query contains [name].
 */
@JvmSynthetic
public operator fun Url.contains(name: String): Boolean = queryParameters.has(name)

/**
 * `true` when a generic [Uri]'s query carries [name] at least once, enabling `"page" in uri`.
 *
 * @param name the query parameter name.
 * @return whether the query contains [name].
 */
@JvmSynthetic
public operator fun Uri.contains(name: String): Boolean = queryParameters().has(name)
