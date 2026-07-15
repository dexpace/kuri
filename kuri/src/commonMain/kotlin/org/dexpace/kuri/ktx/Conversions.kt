/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.ktx

import org.dexpace.kuri.Uri
import org.dexpace.kuri.Url
import org.dexpace.kuri.error.ParseResult
import kotlin.jvm.JvmSynthetic

/*
 * String-to-reference parse sugar and constructor-style factories. All `@JvmSynthetic`, split out from
 * Dsl.kt purely to stay under the file's function-count limit — see that file for the DSL proper.
 */

/**
 * Parses this string as a WHATWG [Url], or `null` on failure — the Kotlin-idiomatic `toX` spelling of
 * [Url.parseOrNull].
 *
 * @param base an optional base URL to resolve a relative reference against.
 * @return the parsed [Url], or `null` when the input is not a valid URL.
 */
@JvmSynthetic
public fun String.toUrl(base: Url? = null): Url? = Url.parseOrNull(this, base)

/**
 * Parses this string as a WHATWG [Url], throwing [org.dexpace.kuri.error.UriSyntaxException] on failure.
 *
 * @param base an optional base URL to resolve a relative reference against.
 * @return the parsed [Url].
 */
@JvmSynthetic
public fun String.toUrlOrThrow(base: Url? = null): Url = Url.parseOrThrow(this, base)

/**
 * Parses this string as a WHATWG [Url], returning the structured [ParseResult] (errors as values).
 *
 * @param base an optional base URL to resolve a relative reference against.
 * @return `Ok` with the [Url], or `Err` with the structured parse error.
 */
@JvmSynthetic
public fun String.toUrlResult(base: Url? = null): ParseResult<Url> = Url.parse(this, base)

/**
 * Parses this string as a generic RFC 3986 [Uri], or `null` on failure.
 *
 * @return the parsed [Uri], or `null` when the input is not a valid URI.
 */
@JvmSynthetic
public fun String.toUri(): Uri? = Uri.parseOrNull(this)

/**
 * Parses this string as a generic RFC 3986 [Uri], throwing on failure.
 *
 * @return the parsed [Uri].
 */
@JvmSynthetic
public fun String.toUriOrThrow(): Uri = Uri.parseOrThrow(this)

/**
 * Parses this string as a generic RFC 3986 [Uri], returning the structured [ParseResult].
 *
 * @return `Ok` with the [Uri], or `Err` with the structured parse error.
 */
@JvmSynthetic
public fun String.toUriResult(): ParseResult<Uri> = Uri.parse(this)

/**
 * `Url("https://example.com")` as a throwing constructor-style factory, mirroring Kotlin's own
 * `Regex(...)`. Delegates to [Url.parseOrThrow].
 *
 * @param input the URL text.
 * @param base an optional base URL to resolve a relative reference against.
 * @return the parsed [Url].
 */
@JvmSynthetic
public operator fun Url.Companion.invoke(
    input: String,
    base: Url? = null,
): Url = Url.parseOrThrow(input, base)

/**
 * `Uri("mailto:a@b.com")` as a throwing constructor-style factory. Delegates to [Uri.parseOrThrow].
 *
 * @param input the URI text.
 * @return the parsed [Uri].
 */
@JvmSynthetic
public operator fun Uri.Companion.invoke(input: String): Uri = Uri.parseOrThrow(input)
