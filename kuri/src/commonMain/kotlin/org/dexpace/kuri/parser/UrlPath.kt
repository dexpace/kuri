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
 * serializer/encoded form, not by an extra list element: a path is absolute unless
 * the `Uri` profile recorded it as rootless. An empty path is therefore the
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
     */
    data class Segments(
        val segments: List<String>,
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
