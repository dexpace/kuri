/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.serialize

import org.dexpace.kuri.host.serializeHost
import org.dexpace.kuri.parser.ParsedComponents

/** The path-segment separator and the per-segment prefix used by both path serializers. */
internal const val SLASH: String = "/"

/** The authority-introducing prefix emitted only when a value has an authority ([NORM-17]). */
internal const val DOUBLE_SLASH: String = "//"

/**
 * §11.2 [NORM-18] leading-`/.` guard prepended to a hierarchical no-authority path that opens `//`.
 *
 * Shared by [UriSerializer]'s `guardRecomposedUriPath` and [UrlSerializer]'s `serializeUrlSegments`:
 * both profiles must guard the same re-parse hazard, so the literal has one home.
 */
internal const val LEADING_DOT_GUARD: String = "/."

/**
 * §11.2 [NORM-16] authority serializer `[userinfo "@"] host [":" port]`; the single home shared by
 * both profiles' authority rendering ([UriSerializer], [UrlSerializer], and `Url.authority`).
 *
 * @param components the stored components whose authority is rendered; MUST carry a non-null host.
 * @return the authority text `[userinfo@]host[:port]`.
 */
internal fun serializeAuthority(components: ParsedComponents): String {
    val host = requireNotNull(components.host) { "authority serialization requires a host" }
    val port = if (components.port != null) ":${components.port}" else ""
    return credentialsPrefix(components) + serializeHost(host) + port
}

/**
 * The `userinfo@` prefix ([NORM-16] / [NORM-30]); empty for a value with no credentials.
 *
 * The WHATWG "includes credentials" rule and the RFC null/empty rule coincide here because
 * [ParsedComponents] holds the credentials as (possibly empty) strings, never null: the prefix
 * appears iff `username` or `password` is non-empty, and `:password` iff `password` is non-empty.
 */
private fun credentialsPrefix(components: ParsedComponents): String {
    if (components.username.isEmpty() && components.password.isEmpty()) return ""
    val password = if (components.password.isNotEmpty()) ":${components.password}" else ""
    return "${components.username}$password@"
}

/**
 * Appends `?query` then (unless excluded) `#fragment`, each only when its component is present.
 *
 * Shared by [UriSerializer.serialize] and [UrlSerializer.serialize]: the query/fragment tail is
 * identical across both profiles ([NORM-15]).
 */
internal fun appendQueryFragment(
    sb: StringBuilder,
    c: ParsedComponents,
    excludeFragment: Boolean,
) {
    if (c.query != null) sb.append('?').append(c.query)
    if (!excludeFragment && c.fragment != null) sb.append('#').append(c.fragment)
}
