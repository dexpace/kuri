/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.serialize

import org.dexpace.kuri.parser.ParsedComponents
import org.dexpace.kuri.parser.toUriPathString
import org.dexpace.kuri.scheme.schemeColonIndex

/**
 * The RFC 3986 §5.3 recomposition of stored components back into the canonical string (SPEC
 * [NORM-15]..[NORM-18], [NORM-30], [NORM-31]; RFC 3986 §5.3).
 *
 * The recomposition order — `scheme:`, authority, path, `?query`, `#fragment` — is shared with
 * [UrlSerializer] ([NORM-15]); the profiles differ only in the path rule and the (here identical)
 * userinfo rule. This profile follows RFC 3986 §5.3 over the preserved (or explicitly normalized)
 * components and applies the leading-`/.` guard plus the §4.2 `./` dot-segment guard for a
 * scheme-less, authority-less colon-first path, so every serialized `Uri` (including
 * `normalized()`/`resolve()` output) re-parses to the same structure.
 */
internal object UriSerializer {
    /**
     * Recomposes [c] into its canonical RFC 3986 §5.3 serialization ([NORM-15]).
     *
     * @param c the stored components to serialize; preserved or explicitly normalized for a `Uri`.
     * @param excludeFragment when `true`, the trailing `#fragment` is omitted ([NORM-31]); the
     *   basis for fragment-insensitive equality.
     * @return the canonical string form of [c].
     */
    internal fun serialize(
        c: ParsedComponents,
        excludeFragment: Boolean = false,
    ): String {
        val sb = StringBuilder()
        if (c.scheme != null) sb.append(c.scheme).append(':')
        if (c.host != null) sb.append(DOUBLE_SLASH).append(serializeAuthority(c, preserveEmptyUserinfo = true))
        sb.append(guardRecomposedUriPath(c.scheme, c.host != null, c.path.toUriPathString()))
        appendQueryFragment(sb, c, excludeFragment)
        check(c.host == null || sb.contains(DOUBLE_SLASH)) { "an authority must emit //" }
        return sb.toString()
    }
}

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
