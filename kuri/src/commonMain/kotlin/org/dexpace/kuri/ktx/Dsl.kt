/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.ktx

import org.dexpace.kuri.Uri
import org.dexpace.kuri.Url
import kotlin.jvm.JvmSynthetic

/*
 * Scoped-builder DSL and parse conveniences. All `@JvmSynthetic` (Java keeps the plain factories and
 * `newBuilder()`). `edit`/`buildUrl`/`buildUri` are the efficient path — one builder, one `build()` —
 * and the receiver is the real [Url.Builder]/[Uri.Builder], so every existing fluent method is in scope
 * unchanged; the helpers below (`pathSegments`, `params`) only add sugar on top.
 */

/**
 * Builds a [Url] from scratch by configuring a fresh [Url.Builder]. Throws exactly as
 * [Url.Builder.build] does when the assembled components cannot form a valid URL.
 *
 * ```
 * val u = buildUrl { scheme("https"); host("api.example.com"); pathSegments("v2", "users") }
 * ```
 *
 * @param block configures the builder; its receiver is the real [Url.Builder].
 * @return the built [Url].
 */
@JvmSynthetic
public inline fun buildUrl(block: Url.Builder.() -> Unit): Url = Url.Builder().apply(block).build()

/**
 * Builds a [Uri] from scratch by configuring a fresh [Uri.Builder]. Throws exactly as
 * [Uri.Builder.build] does on invalid input.
 *
 * @param block configures the builder; its receiver is the real [Uri.Builder].
 * @return the built [Uri].
 */
@JvmSynthetic
public inline fun buildUri(block: Uri.Builder.() -> Unit): Uri = Uri.Builder().apply(block).build()

/**
 * Returns a copy of this [Url] with [block] applied to a builder pre-filled from it — the immutable,
 * single-`build()` alternative to a chain of value operators.
 *
 * ```
 * val next = url.edit { addPathSegment("42"); setQueryParameter("page", "2") }
 * ```
 *
 * @param block edits the pre-filled [Url.Builder].
 * @return a new [Url] reflecting the edits.
 */
@JvmSynthetic
public inline fun Url.edit(block: Url.Builder.() -> Unit): Url = newBuilder().apply(block).build()

/**
 * Returns a copy of this [Uri] with [block] applied to a builder pre-filled from it.
 *
 * @param block edits the pre-filled [Uri.Builder].
 * @return a new [Uri] reflecting the edits.
 */
@JvmSynthetic
public inline fun Uri.edit(block: Uri.Builder.() -> Unit): Uri = newBuilder().apply(block).build()

/**
 * Appends each of [segments] as one decoded path segment, in order, and returns this builder for
 * chaining. Sugar over repeated [Url.Builder.addPathSegment].
 *
 * @param segments the decoded segments to append.
 * @return this builder.
 */
@JvmSynthetic
public fun Url.Builder.pathSegments(vararg segments: String): Url.Builder {
    for (segment in segments) addPathSegment(segment)
    return this
}

/**
 * Appends each of [segments] as one decoded path segment on a [Uri.Builder], in order.
 *
 * @param segments the decoded segments to append.
 * @return this builder.
 */
@JvmSynthetic
public fun Uri.Builder.pathSegments(vararg segments: String): Uri.Builder {
    for (segment in segments) addPathSegment(segment)
    return this
}

/**
 * Appends each of [parameters] (`name to value`) as a query parameter, in order, preserving duplicates.
 *
 * @param parameters the decoded name/value pairs to append.
 * @return this builder.
 */
@JvmSynthetic
public fun Url.Builder.params(vararg parameters: Pair<String, String?>): Url.Builder {
    for ((name, value) in parameters) addQueryParameter(name, value)
    return this
}

/**
 * Appends each of [parameters] as a query parameter on a [Uri.Builder], in order, preserving duplicates.
 *
 * @param parameters the decoded name/value pairs to append.
 * @return this builder.
 */
@JvmSynthetic
public fun Uri.Builder.params(vararg parameters: Pair<String, String?>): Uri.Builder {
    for ((name, value) in parameters) addQueryParameter(name, value)
    return this
}
