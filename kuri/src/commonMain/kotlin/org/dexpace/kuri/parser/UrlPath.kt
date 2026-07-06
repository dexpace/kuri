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
 * A leading dot marks a dotfile (`".bashrc"` → `""`, `"..gz"` → `""`) and a trailing dot leaves
 * nothing after it (`"file."` → `""`); `"archive.tar.gz"` yields `"gz"`.
 */
internal fun fileExtensionOf(name: String): String {
    val dot = name.lastIndexOf('.')
    if (dot <= 0 || dot >= name.length - 1) return ""
    // A name whose entire stem before the last dot is itself dots (e.g. "..gz") is a dotfile with no
    // real base name, so it has no extension — the same rule as the single leading dot in ".gz".
    val hasNonDotStem = (0 until dot).any { name[it] != '.' }
    return if (hasNonDotStem) name.substring(dot + 1) else ""
}

/**
 * Appends the '/'-separated [input] onto [current] as decoded path segments (OkHttp
 * `HttpUrl.Builder.addPathSegments` semantics), encoding each piece with [encode].
 *
 * Every '/' delimits a segment, so an interior or doubled '/' is preserved as a genuine empty
 * segment (`"a//b"` -> `["a", "", "b"]`) rather than collapsed, and a trailing '/' opens a slot the
 * next appended segment fills — so appending onto a directory-style path (`["x", ""]`) fills the
 * slot (`+"a"` -> `["x", "a"]`) instead of doubling it. A leading empty on a from-scratch path is
 * dropped by the caller, since a rootless path cannot begin with an empty segment.
 */
internal fun appendPathSegments(
    current: List<String>,
    input: String,
    encode: (String) -> String,
): List<String> {
    val result = current.toMutableList()
    var offset = 0
    while (offset <= input.length) {
        val slash = input.indexOf('/', offset)
        val end = if (slash < 0) input.length else slash
        val piece = encode(input.substring(offset, end))
        if (result.isNotEmpty() && result.last().isEmpty()) {
            result[result.lastIndex] = piece
        } else {
            result.add(piece)
        }
        if (end < input.length) result.add("")
        offset = end + 1
    }
    return result
}

/** The raw code points a single builder path segment must not carry; each would re-split the path. */
private const val PATH_SEGMENT_DELIMITERS: String = "/\\?#"

/**
 * Whether a builder's structured path is rooted, rootless, or defers that choice to build time
 * (RFC 3986 §3.3; SPEC §3.7). The `Uri`/`Url` builders hold this alongside the decoded path segments
 * in place of a serialized string, so a path's absolute-vs-rootless shape stays explicit rather than
 * being re-derived from a re-split string on every edit.
 *
 * - [ROOTED] — a verbatim absolute path (`/a/b`, from `encodedPath("/a/b")` or a parsed rooted path);
 *   it always serializes with a leading `/`.
 * - [ROOTLESS] — a verbatim rootless path (`a/b`, the `Uri` profile's `path-rootless`, or an opaque
 *   path seeded through the builder); it always serializes without a leading `/`, so pairing it with an
 *   authority is rejected at build.
 * - [DEFERRED] — a path assembled from empty via [BuilderPath.pushSegment] or [BuilderPath.addSegments];
 *   it is stored rootless and gains its leading `/` at build iff a host is present, so setter order is
 *   irrelevant (`addPathSegment("a").host("h")` builds the same value as `host("h").addPathSegment("a")`).
 */
internal enum class PathRooting {
    ROOTED,
    ROOTLESS,
    DEFERRED,
}

/**
 * A builder's structured path: its decoded [segments] plus their [rooting] ([PathRooting]), bundled into
 * one immutable value the `Uri`/`Url` builders swap in wholesale on each path edit (the path counterpart
 * of the builders' query state).
 *
 * Bundling the two together keeps every path transform total: [pushSegment], [addSegments], [setSegment],
 * and [removeSegment] each return a NEW `BuilderPath` with both fields updated in step, rather than
 * mutating a segment list while separately threading the rooting back out. Path lengths are small, so
 * copying the segment list on each transform is cheap.
 *
 * See [UrlPath] for the empty-path (`emptyList()`) versus root-only (`listOf("")`) segment convention, and
 * [PathRooting] for how [rooting] resolves to a leading `/` at build time.
 *
 * @property segments the decoded path segments in order; `emptyList()` is the empty path and every empty
 *   segment is preserved.
 * @property rooting whether the path is absolute, rootless, or defers that choice to build ([PathRooting]).
 */
internal data class BuilderPath(
    val segments: List<String> = emptyList(),
    val rooting: PathRooting = PathRooting.ROOTED,
) {
    /**
     * Appends [encodedSegment] at the path's segment boundary, returning the new path (RFC 3986 §3.3;
     * SPEC [MODEL-39]).
     *
     * A segment must already be exactly one segment, so a raw `/`, `\`, `?`, or `#` is rejected rather than
     * silently re-split. On an empty path the push switches to [DEFERRED][PathRooting.DEFERRED] rooting and
     * a pushed `""` is dropped (an empty path stays empty); otherwise an open trailing slot (a trailing
     * empty segment, e.g. from a trailing `/`) is filled rather than doubled, any other push extends the
     * list, and a non-empty path keeps its [rooting].
     */
    fun pushSegment(encodedSegment: String): BuilderPath {
        val delimiter = encodedSegment.indexOfFirst { it in PATH_SEGMENT_DELIMITERS }
        require(delimiter < 0) {
            "a path segment must not contain '/', '\\', '?', or '#' " +
                "(found '${encodedSegment[delimiter]}' at index $delimiter): $encodedSegment"
        }
        if (segments.isEmpty()) {
            val seeded = if (encodedSegment.isEmpty()) emptyList() else listOf(encodedSegment)
            return BuilderPath(seeded, PathRooting.DEFERRED)
        }
        val next = segments.toMutableList()
        if (next.last().isEmpty()) next[next.lastIndex] = encodedSegment else next.add(encodedSegment)
        check(encodedSegment.isEmpty() || next.last() == encodedSegment) {
            "a non-empty pushed segment must terminate the path"
        }
        return copy(segments = next)
    }

    /**
     * Appends the `/`-separated [input] with [encode], returning the new path (OkHttp
     * `HttpUrl.Builder.addPathSegments`).
     *
     * A from-scratch append (onto an empty path) switches to [DEFERRED][PathRooting.DEFERRED] rooting and
     * drops a leading empty, since a rootless path cannot begin with an empty segment; an append onto
     * existing content keeps [rooting]. Every other empty piece is preserved and a trailing slot is filled
     * rather than doubled, per [appendPathSegments].
     */
    fun addSegments(
        input: String,
        encode: (String) -> String,
    ): BuilderPath {
        val startedEmpty = segments.isEmpty()
        val merged = appendPathSegments(segments, input, encode)
        val result = if (startedEmpty) merged.dropWhile { it.isEmpty() } else merged
        check(!startedEmpty || result.firstOrNull()?.isEmpty() != true) {
            "a from-scratch append must not leave a leading empty segment"
        }
        return BuilderPath(result, if (startedEmpty) PathRooting.DEFERRED else rooting)
    }

    /**
     * Replaces the segment at [index] with [encodedSegment], keeping the path's [rooting] (RFC 3986 §3.3).
     *
     * The absolute-versus-rootless shape is preserved; the "would re-root" condition an emptied first
     * segment creates is recomputed at build time by [wellFormed], not decided here.
     *
     * @throws IndexOutOfBoundsException when [index] is negative or `>=` the current segment count.
     */
    fun setSegment(
        index: Int,
        encodedSegment: String,
    ): BuilderPath {
        if (index !in segments.indices) {
            throw IndexOutOfBoundsException("path segment index $index out of bounds for size ${segments.size}")
        }
        val next = segments.toMutableList()
        next[index] = encodedSegment
        check(next.size == segments.size) { "setSegment must not change the segment count" }
        return copy(segments = next)
    }

    /**
     * Removes the segment at [index], keeping the path's [rooting] (RFC 3986 §3.3).
     *
     * As with [setSegment] the absolute-versus-rootless shape is preserved, and the "would re-root"
     * condition an emptied first segment creates is recomputed at build time by [wellFormed].
     *
     * @throws IndexOutOfBoundsException when [index] is negative or `>=` the current segment count.
     */
    fun removeSegment(index: Int): BuilderPath {
        if (index !in segments.indices) {
            throw IndexOutOfBoundsException("path segment index $index out of bounds for size ${segments.size}")
        }
        val next = segments.toMutableList()
        next.removeAt(index)
        check(next.size == segments.size - 1) { "removeSegment must drop exactly one segment" }
        return copy(segments = next)
    }

    /**
     * The RFC 3986 §5.3 path string this value recomposes to, given whether an authority is present
     * ([hasHost]).
     *
     * A [ROOTED][PathRooting.ROOTED] path serializes absolute and a [ROOTLESS][PathRooting.ROOTLESS] one
     * rootless regardless of the authority; a [DEFERRED][PathRooting.DEFERRED] segment-built path gains a
     * leading `/` exactly when [hasHost] (RFC 3986 §3.3 forbids a rootless path after an authority, while
     * the rootless form is the only shape a host-less `urn:`/`mailto:` value can carry). A deferred path
     * with no segments therefore serializes to a lone `/` under a host — matching a from-scratch
     * `addPathSegment("")` that stands for the root under an authority — but to `""` without one.
     */
    fun effectivePath(hasHost: Boolean): String {
        val rootless = UrlPath.Segments(segments, rooted = false).toUriPathString()
        return when (rooting) {
            PathRooting.ROOTED -> UrlPath.Segments(segments, rooted = true).toUriPathString()
            PathRooting.ROOTLESS -> rootless
            PathRooting.DEFERRED -> if (hasHost) URI_PATH_SEPARATOR + rootless else rootless
        }
    }

    /**
     * Whether this path can be recomposed without silently changing its absolute-vs-rootless shape
     * (RFC 3986 §3.3).
     *
     * A rootless-stored path — [ROOTLESS][PathRooting.ROOTLESS] or a still-[DEFERRED][PathRooting.DEFERRED]
     * one — must not begin with an empty segment: it would serialize with a leading `/` and re-parse as a
     * rooted path, the unrepresentable shape a from-scratch rootless build rejects. A
     * [ROOTED][PathRooting.ROOTED] path may begin with an empty segment (it serializes `//…`, guarded
     * separately against a missing authority). This recomputes the old builder's "would re-root" flag from
     * the current state instead of tracking it across edits.
     */
    fun wellFormed(): Boolean = rooting == PathRooting.ROOTED || segments.firstOrNull()?.isEmpty() != true

    internal companion object {
        /**
         * The structured path for a verbatim encoded [encodedPath] (from `encodedPath("/a/b")` or a parsed
         * path): split into [segments] and marked [ROOTED][PathRooting.ROOTED] or
         * [ROOTLESS][PathRooting.ROOTLESS] by its leading `/`, never [DEFERRED][PathRooting.DEFERRED].
         */
        fun verbatim(encodedPath: String): BuilderPath {
            val split = splitUriPath(encodedPath)
            return BuilderPath(split.segments, if (split.rooted) PathRooting.ROOTED else PathRooting.ROOTLESS)
        }
    }
}
