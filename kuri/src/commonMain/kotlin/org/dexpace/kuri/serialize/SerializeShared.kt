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
 * §11.2 [NORM-16]/[NORM-30] authority serializer `[userinfo "@"] host [":" port]`; the single home
 * shared by both profiles' authority rendering ([UriSerializer], [UrlSerializer], and `Url.authority`).
 *
 * @param components the stored components whose authority is rendered; MUST carry a non-null host.
 * @param preserveEmptyUserinfo `true` for the `Uri` profile's [NORM-16] null/empty rule, which
 *   distinguishes an absent userinfo (`username == null`) from a present-but-empty one
 *   (`username == ""`); `false` (the default) for the `Url` profile's [NORM-30] WHATWG rule, which
 *   has no such distinction and collapses both to no credentials.
 * @return the authority text `[userinfo@]host[:port]`.
 */
internal fun serializeAuthority(
    components: ParsedComponents,
    preserveEmptyUserinfo: Boolean = false,
): String {
    val host = requireNotNull(components.host) { "authority serialization requires a host" }
    val port = if (components.port != null) ":${components.port}" else ""
    return credentialsPrefix(components, preserveEmptyUserinfo) + serializeHost(host) + port
}

/**
 * The `userinfo@` prefix ([NORM-16] / [NORM-30]); empty for a value with no credentials.
 *
 * Under [preserveEmptyUserinfo] (the `Uri` profile), presence is tracked by nullability: the
 * prefix appears iff `username` or `password` is non-null, and `:password` appears iff `password`
 * is non-null — so a present-but-empty username or password still renders its `@` or `:`
 * ([MODEL-11]). Otherwise (the `Url` profile, [NORM-30]), [ParsedComponents] never holds a `null`
 * credential and the WHATWG "includes credentials" rule applies instead: the prefix appears iff
 * `username` or `password` is non-empty, and `:password` iff `password` is non-empty.
 */
private fun credentialsPrefix(
    components: ParsedComponents,
    preserveEmptyUserinfo: Boolean,
): String =
    if (preserveEmptyUserinfo) {
        preservedCredentialsPrefix(components.username, components.password)
    } else {
        collapsedCredentialsPrefix(components.username, components.password)
    }

/** [NORM-16]: emits `@` iff either credential is non-`null`; `username` defaults to `""` when absent. */
private fun preservedCredentialsPrefix(
    username: String?,
    password: String?,
): String {
    if (username == null && password == null) return ""
    val passwordPart = if (password != null) ":$password" else ""
    return "${username.orEmpty()}$passwordPart@"
}

/** [NORM-30]: emits `@` iff either credential is non-empty (a `Url` credential is never `null`). */
private fun collapsedCredentialsPrefix(
    username: String?,
    password: String?,
): String {
    if (username.isNullOrEmpty() && password.isNullOrEmpty()) return ""
    val passwordPart = if (!password.isNullOrEmpty()) ":$password" else ""
    return "${username.orEmpty()}$passwordPart@"
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
