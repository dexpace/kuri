/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.serialize

import org.dexpace.kuri.ParseProfile
import org.dexpace.kuri.host.serializeHost
import org.dexpace.kuri.parser.ParsedComponents
import org.dexpace.kuri.parser.UrlPath
import org.dexpace.kuri.parser.toUriPathString
import org.dexpace.kuri.scheme.schemeColonIndex

/** The path-segment separator and the per-segment prefix used by both path serializers. */
private const val SLASH: String = "/"

/** The authority-introducing prefix emitted only when a value has an authority ([NORM-17]). */
private const val DOUBLE_SLASH: String = "//"

/** §11.2 [NORM-18] leading-`/.` guard prepended to a hierarchical no-authority path that opens `//`. */
private const val LEADING_DOT_GUARD: String = "/."

/**
 * The RFC 3986 §4.2 dot-segment guard prepended to a scheme-less, authority-less path whose first
 * segment contains a colon, so it does not re-parse as a scheme ([NORM-18]).
 */
private const val COLON_SEGMENT_GUARD: String = "./"

/**
 * Guards a recomposed `Uri` path so the serialized string re-parses to the same structure (RFC 3986
 * §4.2; [NORM-18]): with no authority, a `//`-leading path gets the `/.` guard and a scheme-less
 * path whose first segment contains `:` gets the `./` guard. Both guards fire only on component
 * states the parser never produces, so a parsed value round-trips unchanged.
 */
internal fun guardRecomposedUriPath(
    scheme: String?,
    hasAuthority: Boolean,
    path: String,
): String =
    when {
        hasAuthority -> path
        path.startsWith(DOUBLE_SLASH) -> LEADING_DOT_GUARD + path
        scheme == null && schemeColonIndex(path) >= 0 -> COLON_SEGMENT_GUARD + path
        else -> path
    }

/**
 * The §11.2 recomposition of stored components back into the canonical string (SPEC [NORM-15]..
 * [NORM-18], [NORM-30], [NORM-31]; RFC 3986 §5.3; WHATWG URL serializer).
 *
 * The recomposition order — `scheme:`, authority, path, `?query`, `#fragment` — is shared by both
 * profiles ([NORM-15]); the profiles differ only in the path rule and the (here identical) userinfo
 * rule. The `Url` branch follows the WHATWG URL serializer, including the `/.` guard for a
 * no-authority hierarchical path that begins `//`; the `Uri` branch follows RFC 3986 §5.3 over the
 * preserved (or explicitly normalized) components and applies the same leading-`/.` guard plus the
 * §4.2 `./` dot-segment guard for a scheme-less, authority-less colon-first path, so every serialized
 * `Uri` (including `normalized()`/`resolve()` output) re-parses to the same structure.
 */
internal object Serializer {
    /**
     * Recomposes [c] into its canonical serialization under [profile] ([NORM-15]).
     *
     * @param c the stored components to serialize; already canonical for a `Url`, preserved or
     *   explicitly normalized for a `Uri`.
     * @param profile selects the WHATWG URL serializer ([ParseProfile.URL]) versus the RFC 3986
     *   §5.3 recomposition ([ParseProfile.URI]).
     * @param excludeFragment when `true`, the trailing `#fragment` is omitted ([NORM-31]); the
     *   basis for fragment-insensitive equality.
     * @return the canonical string form of [c].
     */
    internal fun serialize(
        c: ParsedComponents,
        profile: ParseProfile,
        excludeFragment: Boolean = false,
    ): String = if (profile.isWhatwg) serializeUrl(c, excludeFragment) else serializeUri(c, excludeFragment)

    /** The WHATWG URL serializer ([NORM-15], [NORM-30]); a `Url` always carries a non-null scheme. */
    private fun serializeUrl(
        c: ParsedComponents,
        excludeFragment: Boolean,
    ): String {
        val scheme = requireNotNull(c.scheme) { "a Url value always carries a scheme" }
        val sb = StringBuilder()
        sb.append(scheme).append(':')
        if (c.host != null) sb.append(DOUBLE_SLASH).append(serializeAuthority(c))
        sb.append(serializeUrlPath(c))
        appendQueryFragment(sb, c, excludeFragment)
        check(sb.startsWith(scheme)) { "serialization must open with the scheme" }
        return sb.toString()
    }

    /** RFC 3986 §5.3 recomposition for the `Uri` profile; the scheme may be absent (relative ref). */
    private fun serializeUri(
        c: ParsedComponents,
        excludeFragment: Boolean,
    ): String {
        val sb = StringBuilder()
        if (c.scheme != null) sb.append(c.scheme).append(':')
        if (c.host != null) sb.append(DOUBLE_SLASH).append(serializeAuthority(c))
        sb.append(guardRecomposedUriPath(c.scheme, c.host != null, c.path.toUriPathString()))
        appendQueryFragment(sb, c, excludeFragment)
        check(c.host == null || sb.contains(DOUBLE_SLASH)) { "an authority must emit //" }
        return sb.toString()
    }

    /** Appends `?query` then (unless excluded) `#fragment`, each only when its component is present. */
    private fun appendQueryFragment(
        sb: StringBuilder,
        c: ParsedComponents,
        excludeFragment: Boolean,
    ) {
        if (c.query != null) sb.append('?').append(c.query)
        if (!excludeFragment && c.fragment != null) sb.append('#').append(c.fragment)
    }
}

/**
 * §11.2 [NORM-16] authority serializer `[userinfo "@"] host [":" port]`; the single home shared by both
 * profiles' URL-side authority rendering (the `Serializer` object and `Url.authority`).
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
 * WHATWG URL path serialization: an opaque path verbatim, else the segment list ([NORM-15] step 3).
 *
 * The single home shared by the `Serializer` object and `Url.encodedPath`.
 *
 * @param components the stored components whose path is rendered.
 * @return the encoded URL path string, with the [NORM-18] `/.` guard applied where required.
 */
internal fun serializeUrlPath(components: ParsedComponents): String =
    when (val path = components.path) {
        is UrlPath.Opaque -> path.path
        is UrlPath.Segments -> serializeUrlSegments(path.segments, noAuthority = components.host == null)
    }

/**
 * Joins URL path [segments] as `"/" + segment` runs, applying the [NORM-18] `/.` guard.
 *
 * The guard fires only for a no-authority value whose first segment is empty and which has a
 * further segment (so the serialization would open `//` and re-parse with a spurious authority).
 */
private fun serializeUrlSegments(
    segments: List<String>,
    noAuthority: Boolean,
): String {
    val needsGuard = noAuthority && segments.size > 1 && segments[0] == ""
    val prefix = if (needsGuard) LEADING_DOT_GUARD else ""
    return prefix + segments.joinToString("") { "$SLASH$it" }
}
