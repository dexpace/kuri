/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri

import org.dexpace.kuri.error.ParseResult
import java.net.URI
import java.net.URL

/**
 * Converts this [Url] to a [java.net.URI] built from its canonical [Url.href].
 *
 * The bridge delegates to [URI.create], so the JDK's RFC 2396-based parser validates the string. That
 * grammar is marginally stricter than the WHATWG model this library implements, so in rare cases a
 * WHATWG-valid [href] is rejected by [URI]; the [IllegalArgumentException] from [URI.create] then
 * surfaces unchanged rather than being swallowed.
 *
 * @return the [URI] whose string form equals this URL's [href].
 * @throws IllegalArgumentException if [href] is not a valid RFC 2396 URI (raised by [URI.create]).
 */
public fun Url.toJavaUri(): URI = URI.create(href)

/**
 * Converts this [Url] to a [java.net.URI], punning the JDK's stricter-grammar rejection to `null`.
 *
 * The `null`-returning counterpart of [toJavaUri], for a call site that prefers a nullable value to
 * an unchecked [IllegalArgumentException] from [URI.create].
 *
 * @return the [URI] whose string form equals this URL's [href], or `null` when [URI.create] rejects it.
 */
public fun Url.toJavaUriOrNull(): URI? =
    try {
        URI.create(href)
    } catch (_: IllegalArgumentException) {
        null
    }

/**
 * Converts this [Url] to a [java.net.URL] by first bridging to a [java.net.URI] and calling [URI.toURL].
 *
 * @return the [URL] equivalent of this URL.
 * @throws IllegalArgumentException if [href] is not a valid RFC 2396 URI (raised by [URI.create]).
 * @throws java.net.MalformedURLException if the URI is not absolute or names a protocol with no handler.
 */
public fun Url.toJavaUrl(): URL = toJavaUri().toURL()

/**
 * Converts this [Uri] to a [java.net.URI] built from its canonical [Uri.uriString].
 *
 * @return the [URI] whose string form equals this URI's [uriString].
 * @throws IllegalArgumentException if [uriString] is not a valid RFC 2396 URI (raised by [URI.create]).
 */
public fun Uri.toJavaUri(): URI = URI.create(uriString)

/**
 * Converts this [Uri] to a [java.net.URI], punning the JDK's stricter-grammar rejection to `null`.
 *
 * The `null`-returning counterpart of [toJavaUri], for a call site that prefers a nullable value to
 * an unchecked [IllegalArgumentException] from [URI.create].
 *
 * @return the [URI] whose string form equals this URI's [uriString], or `null` when [URI.create] rejects it.
 */
public fun Uri.toJavaUriOrNull(): URI? =
    try {
        URI.create(uriString)
    } catch (_: IllegalArgumentException) {
        null
    }

/**
 * Re-parses this [java.net.URI]'s string form into a kuri [Uri] under the `Uri` profile.
 *
 * @return [ParseResult.Ok] with the parsed [Uri], or [ParseResult.Err] when the string is not a valid URI.
 */
public fun URI.toKuriUri(): ParseResult<Uri> = Uri.parse(toString())

/**
 * Re-parses this [java.net.URI]'s string form into a kuri [Url] under the `Url` profile.
 *
 * @return [ParseResult.Ok] with the parsed [Url], or [ParseResult.Err] when the string is not a valid URL.
 */
public fun URI.toKuriUrl(): ParseResult<Url> = Url.parse(toString())

/**
 * Re-parses this [java.net.URL]'s string form into a kuri [Url] under the `Url` profile.
 *
 * @return [ParseResult.Ok] with the parsed [Url], or [ParseResult.Err] when the string is not a valid URL.
 */
public fun URL.toKuriUrl(): ParseResult<Url> = Url.parse(toString())
