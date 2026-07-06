/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.parser

/**
 * The stored shape of a parsed path (SPEC §3.7, §8.3 PATH/OPAQUE-PATH states,
 * [MODEL-26]–[MODEL-29]; WHATWG basic URL parser "path" / "opaque path", RFC 3986
 * §3.3).
 *
 * A path is always present — there is no `null` path; an absent path is the empty
 * value [Segments] with no elements ([MODEL-26]). The two stored kinds are closed:
 *
 * - [Segments] — a hierarchical, slash-decomposed path held as an ordered list of
 *   segments (the decoded/ergonomic view; the canonical encoded string is derived
 *   from it by the serializer, §11). Used for every special-scheme `Url` path and
 *   for any RFC 3986 `path-abempty` / `path-absolute` / `path-rootless` value.
 * - [Opaque] — a "cannot-be-a-base" / scheme-specific opaque path held verbatim as a
 *   single encoded string with no segment structure ([MODEL-28]; e.g.
 *   `mailto:user@example.com`, `urn:isbn:…`). In the `Url` profile opaque paths
 *   arise only for non-special schemes.
 *
 * ## Empty path vs. single empty segment
 *
 * This is the convention callers MUST follow (it mirrors the WHATWG path-list
 * serialization and [MODEL-27]):
 *
 * | Path state                       | [Segments] value        | Encoded form |
 * |----------------------------------|-------------------------|--------------|
 * | empty path                       | `Segments(emptyList())` | `""`         |
 * | root-only path (`/`)             | `Segments(listOf(""))`  | `"/"`        |
 * | absolute path (`/a/b`)           | `Segments(["a","b"])`*  | `"/a/b"`     |
 * | rootless path (`a/b`, `Uri` only)| `Segments(["a","b"])`*  | `"a/b"`      |
 *
 * \* The leading-slash (absolute vs. rootless) distinction is carried by the
 * [Segments.rooted] flag, not by an extra list element: a path is absolute (`rooted`)
 * unless the `Uri` profile recorded it as rootless. An empty path is still the
 * *empty list*, while a root-only path is the *single empty segment* `[""]` — the
 * two MUST NOT be conflated. Per [MODEL-29], a special-scheme `Url` whose input
 * path was empty is canonicalized to the root path `Segments(listOf(""))` (encoded
 * `"/"`); in the `Uri` profile an empty path stays `Segments(emptyList())`.
 *
 * A trailing empty segment (trailing `/`, e.g. `"/a/"` → `["a", ""]`) is preserved
 * as distinct from its absence (`"/a"` → `["a"]`), per [MODEL-27].
 *
 * Like [org.dexpace.kuri.host.Host], these variants carry no behaviour and do not
 * enforce their invariants in the constructor (a throwing data-class constructor
 * would leak into the future public surface); the parser/serializer modules that
 * construct them are responsible for upholding the table above.
 */
internal sealed interface UrlPath {
    /**
     * A hierarchical path as an ordered list of decoded segments (SPEC §3.7,
     * [MODEL-26]/[MODEL-27]; WHATWG URL "path" list). See [UrlPath] for the
     * empty-path (`emptyList()`) vs. root-only (`listOf("")`) convention.
     *
     * @property segments the decoded path segments in order; `emptyList()` is the
     *   empty path and `listOf("")` is the root-only path `"/"`.
     * @property rooted whether the path is absolute (a leading `/`, the default) as
     *   opposed to rootless (`a/b`, the `Uri` profile only — `Url` paths are always
     *   rooted). Only meaningful for a non-empty segment list: the empty path always
     *   serializes to `""`, so its rootedness is irrelevant.
     */
    data class Segments(
        val segments: List<String>,
        val rooted: Boolean = true,
    ) : UrlPath

    /**
     * A "cannot-be-a-base" / opaque path stored verbatim as one encoded string with
     * no segment structure (SPEC §3.7 [MODEL-28], §9.5; WHATWG URL "opaque path",
     * RFC 3986 `path-rootless` for a scheme with no authority). Not subject to
     * dot-segment removal.
     *
     * @property path the opaque path text in its canonical encoded form, without a
     *   leading `/` and without the `scheme:` prefix.
     */
    data class Opaque(
        val path: String,
    ) : UrlPath
}

/** The RFC 3986 §3.3 path-segment separator shared by the `Uri`-profile path helpers below. */
private const val URI_PATH_SEPARATOR: String = "/"

/**
 * Encodes these [UrlPath.Segments] back to their RFC 3986 §5.3 path string: `""` for the empty
 * path, an absolute `/`-prefixed join when [rooted][UrlPath.Segments.rooted], or a bare rootless
 * join otherwise.
 *
 * The empty check comes FIRST so a root-only path (`Segments(listOf(""))`, encoded `"/"`) stays
 * distinct from the empty path (`Segments(emptyList())`, encoded `""`); the `rooted` flag then
 * selects the absolute (`/a/b`) versus rootless (`a/b`) form, so a relative reference (`a/b`) and a
 * scheme-rootless path (`mailto:a@b`) each round-trip unchanged.
 */
internal fun UrlPath.Segments.toUriPathString(): String =
    when {
        segments.isEmpty() -> ""
        rooted -> URI_PATH_SEPARATOR + segments.joinToString(URI_PATH_SEPARATOR)
        else -> segments.joinToString(URI_PATH_SEPARATOR)
    }

/**
 * Encodes any [UrlPath] back to its RFC 3986 §5.3 string form: an [Opaque][UrlPath.Opaque] path
 * verbatim (no `/` guard, never dot-collapsed), else the [Segments][UrlPath.Segments] join.
 *
 * The smart-cast `Segments` receiver dispatches to the more specific [toUriPathString] overload, so
 * this is a dispatch, not a recursion.
 */
internal fun UrlPath.toUriPathString(): String =
    when (this) {
        is UrlPath.Opaque -> path
        is UrlPath.Segments -> toUriPathString()
    }

/**
 * Splits a raw RFC 3986 path string into [UrlPath.Segments], mirroring the parser's empty-path vs.
 * single-empty-segment conventions (SPEC §3.7, [MODEL-26]/[MODEL-27]).
 *
 * An empty path is `emptyList()`; an absolute path drops the leading empty element so root-only `/`
 * is `listOf("")`; a rootless path keeps every segment. A trailing `/` is preserved as a trailing
 * `""`. No dot-segment removal is performed ([PARSE-39]) — the caller applies §5.2.4 beforehand if
 * needed; the `rooted` flag records the absolute (leading `/`) versus rootless distinction so the
 * serializer can round-trip it faithfully.
 */
internal fun splitUriPath(path: String): UrlPath.Segments =
    when {
        path.isEmpty() -> UrlPath.Segments(emptyList())
        path.startsWith(URI_PATH_SEPARATOR) -> UrlPath.Segments(path.substring(1).split('/'), rooted = true)
        else -> UrlPath.Segments(path.split('/'), rooted = false)
    }

/**
 * The "file name" of decoded path [segments]: the last non-empty segment, or `""` when there is
 * none (SPEC §3.3). Shared by the `Uri`/`Url` `fileName()` projections.
 *
 * A trailing empty segment (a trailing `/`, e.g. `["a", ""]`) is skipped, so both `["a", "b"]` and
 * `["a", "b", ""]` yield `"b"`; an empty, root-only, or all-empty segment list yields `""`.
 */
internal fun fileNameOf(segments: List<String>): String = segments.lastOrNull { it.isNotEmpty() } ?: ""

/**
 * The extension of a decoded file [name]: the text after its last interior `.`, or `""` when it has
 * none (SPEC §3.3). Shared by the `Uri`/`Url` `fileExtension()` projections.
 *
 * A leading dot marks a dotfile (`".bashrc"` → `""`) and a trailing dot leaves nothing after it
 * (`"file."` → `""`); `"archive.tar.gz"` yields `"gz"`.
 */
internal fun fileExtensionOf(name: String): String {
    val dot = name.lastIndexOf('.')
    return if (dot > 0 && dot < name.length - 1) name.substring(dot + 1) else ""
}
