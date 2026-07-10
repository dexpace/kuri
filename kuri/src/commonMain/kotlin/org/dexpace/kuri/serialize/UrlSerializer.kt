/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.serialize

import org.dexpace.kuri.parser.ComponentPath
import org.dexpace.kuri.parser.ParsedComponents

/**
 * The WHATWG URL serializer recomposition of stored components back into the canonical string (SPEC
 * [NORM-15]..[NORM-18], [NORM-30], [NORM-31]; WHATWG URL serializer).
 *
 * The recomposition order — `scheme:`, authority, path, `?query`, `#fragment` — is shared with
 * [UriSerializer] ([NORM-15]); the profiles differ only in the path rule and the (here identical)
 * userinfo rule. This profile follows the WHATWG URL serializer, including the `/.` guard for a
 * no-authority hierarchical path that begins `//`.
 */
internal object UrlSerializer {
    /**
     * Recomposes [c] into its canonical WHATWG URL serialization ([NORM-15]); a `Url` always
     * carries a non-null scheme.
     *
     * @param c the stored components to serialize; already canonical for a `Url`.
     * @param excludeFragment when `true`, the trailing `#fragment` is omitted ([NORM-31]); the
     *   basis for fragment-insensitive equality.
     * @return the canonical string form of [c].
     */
    internal fun serialize(
        c: ParsedComponents,
        excludeFragment: Boolean = false,
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
}

/**
 * WHATWG URL path serialization: an opaque path verbatim, else the segment list ([NORM-15] step 3).
 *
 * The single home shared by [UrlSerializer.serialize] and `Url.encodedPath`.
 *
 * @param components the stored components whose path is rendered.
 * @param guardAgainstAuthority whether to prepend the [NORM-18] `/.` guard where required
 *   (`true` for the full `href`, where an unguarded `//`-leading path would re-parse with a
 *   spurious authority); the standalone `pathname` getter passes `false` since it is never
 *   concatenated onto a bare `scheme:` and WHATWG's pathname-getter algorithm has no such guard.
 * @return the encoded URL path string.
 */
internal fun serializeUrlPath(
    components: ParsedComponents,
    guardAgainstAuthority: Boolean = true,
): String =
    when (val path = components.path) {
        is ComponentPath.Opaque -> path.path
        is ComponentPath.Segments ->
            serializeUrlSegments(
                path.segments,
                noAuthority = components.host == null,
                guardAgainstAuthority = guardAgainstAuthority,
            )
    }

/**
 * Joins URL path [segments] as `"/" + segment` runs, applying the [NORM-18] `/.` guard.
 *
 * The guard fires only when [guardAgainstAuthority] is requested, for a no-authority value whose
 * first segment is empty and which has a further segment (so the serialization would open `//`
 * and re-parse with a spurious authority).
 */
private fun serializeUrlSegments(
    segments: List<String>,
    noAuthority: Boolean,
    guardAgainstAuthority: Boolean,
): String {
    val needsGuard = guardAgainstAuthority && noAuthority && segments.size > 1 && segments[0] == ""
    val prefix = if (needsGuard) LEADING_DOT_GUARD else ""
    return prefix + segments.joinToString("") { "$SLASH$it" }
}
