/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.parser

import org.dexpace.kuri.percent.PercentCodec
import org.dexpace.kuri.percent.PercentEncodeSets

/** The canonical scheme name whose state machine owns the Windows-drive-letter quirks. */
private const val FILE_SCHEME: String = "file"

/**
 * True when [ch] is one of the three universal authority/path terminators `/`, `?`, `#`
 * (SPEC §8.3 [PARSE-26]/[PARSE-29]). Backslash is added separately for special schemes so
 * this stays a small, profile-agnostic predicate.
 */
internal fun isSlashQueryHash(ch: Char): Boolean = ch == '/' || ch == '?' || ch == '#'

/**
 * True when [ch] terminates the authority/host scan under [state] (SPEC §8.3
 * [PARSE-26]/[PARSE-29]): the universal `/`, `?`, `#`, plus `\` for special schemes
 * ([PARSE-49]).
 */
internal fun isAuthorityTerminator(
    state: UrlParserState,
    ch: Char,
): Boolean = isSlashQueryHash(ch) || (state.special && ch == '\\')

/**
 * True when [ch] (or EOF, `null`) ends the current path segment (SPEC §8.3 PATH
 * [PARSE-39]): EOF, `/`, `?`, or — for special schemes — `\` ([PARSE-49]).
 */
internal fun isPathSegmentEnd(
    state: UrlParserState,
    ch: Char?,
): Boolean {
    if (ch == null) {
        return true
    }
    return ch == '/' || ch == '?' || (state.special && ch == '\\')
}

/**
 * True when [seg] is a *single-dot URL path segment*: `.` or an ASCII-case-insensitive
 * `%2e` (SPEC §9.2 [PATH-9]; WHATWG single-dot path segment).
 */
internal fun isSingleDotSegment(seg: String): Boolean = seg == "." || seg.equals("%2e", ignoreCase = true)

/**
 * True when [seg] is a *double-dot URL path segment*: `..` or an ASCII-case-insensitive
 * `.%2e`, `%2e.`, or `%2e%2e` (SPEC §9.2 [PATH-9]; WHATWG double-dot path segment).
 */
internal fun isDoubleDotSegment(seg: String): Boolean =
    seg == ".." ||
        seg.equals(".%2e", ignoreCase = true) ||
        seg.equals("%2e.", ignoreCase = true) ||
        seg.equals("%2e%2e", ignoreCase = true)

/**
 * "Shorten a url's path" (SPEC §9.2/§9.4; WHATWG "shorten a url's path").
 *
 * Removes the last path segment, except it is a no-op when the scheme is `file` and the
 * sole segment is a normalized Windows drive letter ([PATH-16]). Operates in place on the
 * mutable segment list.
 */
internal fun shortenPath(state: UrlParserState) {
    require(!state.isOpaque) { "shorten must not run on an opaque path" }
    val segments = state.path
    val keepDrive = state.scheme == FILE_SCHEME && segments.size == 1 && isNormalizedWindowsDrive(segments[0])
    if (!keepDrive && segments.isNotEmpty()) {
        segments.removeAt(segments.lastIndex)
    }
}

/**
 * Appends [seg] to the path (SPEC §8.3 PATH [PARSE-40]; WHATWG path state final branch).
 *
 * Applies the `file` Windows-drive normalization (`C|` → `C:`) when the path is empty and
 * [seg] is a drive letter ([PATH-15]), then percent-encodes the segment with the path set
 * (§5) before storing the encoded form.
 */
internal fun appendPathSegment(
    state: UrlParserState,
    seg: String,
) {
    require(!state.isOpaque) { "cannot append a segment to an opaque path" }
    val normalized =
        if (state.scheme == FILE_SCHEME && state.path.isEmpty() && isWindowsDriveLetter(seg)) {
            seg[0] + ":"
        } else {
            seg
        }
    state.path.add(PercentCodec.encode(normalized, PercentEncodeSets.PATH))
}

/**
 * Clones [base]'s path into a fresh mutable segment list for relative resolution (SPEC §8.3
 * RELATIVE/FILE base-copy branches; WHATWG "clone").
 *
 * An opaque base path contributes no segments here (it is handled by NO_SCHEME's
 * fragment-only branch, [PARSE-18]); a `null` base yields an empty list.
 */
internal fun cloneBasePath(base: ParsedComponents?): MutableList<String> {
    val segments = (base?.path as? UrlPath.Segments)?.segments ?: emptyList()
    return segments.toMutableList()
}
