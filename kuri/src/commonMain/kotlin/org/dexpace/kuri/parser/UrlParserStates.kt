/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.parser

import org.dexpace.kuri.error.UriParseError
import org.dexpace.kuri.error.ValidationError
import org.dexpace.kuri.percent.PercentCodec
import org.dexpace.kuri.percent.PercentEncodeSets
import org.dexpace.kuri.scheme.Scheme
import org.dexpace.kuri.text.isAsciiAlpha
import org.dexpace.kuri.text.isAsciiAlphanumeric

/** The canonical `file` scheme, special-cased throughout the §8 state machine. */
private const val FILE_SCHEME: String = "file"

/**
 * The routing, scheme, relative-resolution, path, query, and opaque-path states of the §8.3
 * `Url`-profile state machine (WHATWG basic URL parser).
 *
 * Each `internal` member is exactly one WHATWG state function; the `private` members are the
 * decompositions that keep every state under the 60-line / 2-return budgets. The object groups
 * the state set as a cohesive unit (its many small functions are by design, hence the
 * `TooManyFunctions` suppression); the authority/host/port/file states live in the sibling
 * [UrlParserAuthority].
 */
@Suppress("TooManyFunctions")
internal object UrlParserStates {
    // --- scheme start / scheme (§8.3 [PARSE-13]–[PARSE-16]) --------------------------

    /**
     * SCHEME_START (§8.3 [PARSE-13]; WHATWG scheme-start state).
     *
     * An ASCII-alpha first code point seeds the scheme buffer (lowercased) and enters SCHEME;
     * anything else backs up into NO_SCHEME without consuming.
     */
    internal fun schemeStartState(state: UrlParserState): UrlTransition {
        val c = state.currentChar()
        if (c != null && c.isAsciiAlpha()) {
            state.buffer.append(c.lowercaseChar())
            return UrlTransition.Advance(UrlState.SCHEME)
        }
        return UrlTransition.Reconsume(UrlState.NO_SCHEME)
    }

    /**
     * SCHEME (§8.3 [PARSE-14]–[PARSE-16]; WHATWG scheme state).
     *
     * Accumulates valid scheme code points (lowercased); a `:` finalizes the scheme, any other
     * code point restarts the scan as NO_SCHEME from the first code point ([PARSE-16], the one
     * sanctioned pointer reset).
     */
    internal fun schemeState(state: UrlParserState): UrlTransition {
        val c = state.currentChar()
        return when {
            c != null && isSchemeTailChar(c) -> {
                state.buffer.append(c.lowercaseChar())
                UrlTransition.Advance(UrlState.SCHEME)
            }
            c == ':' -> schemeColon(state)
            else -> schemeRestart(state)
        }
    }

    /** True when [c] may continue a scheme: ASCII alphanumeric, `+`, `-`, or `.` ([PARSE-14]). */
    private fun isSchemeTailChar(c: Char): Boolean = c.isAsciiAlphanumeric() || c == '+' || c == '-' || c == '.'

    /** Resets the scan to NO_SCHEME from the first code point ([PARSE-16]; the only `pos` reset). */
    private fun schemeRestart(state: UrlParserState): UrlTransition {
        state.buffer.setLength(0)
        state.pos = 0
        return UrlTransition.Reconsume(UrlState.NO_SCHEME)
    }

    /**
     * Finalizes the scheme on `:` ([PARSE-15]) and dispatches to the next state by specialness
     * and the shape of the remaining input.
     */
    private fun schemeColon(state: UrlParserState): UrlTransition {
        val scheme = state.buffer.toString()
        check(Scheme.isValidScheme(scheme)) { "scheme buffer must already be valid: $scheme" }
        state.scheme = scheme
        state.special = Scheme.isSpecial(scheme)
        state.buffer.setLength(0)
        return dispatchAfterScheme(state, scheme)
    }

    /** The post-`:` dispatch of [PARSE-15]: file / special-relative / special / path-or-authority / opaque. */
    private fun dispatchAfterScheme(
        state: UrlParserState,
        scheme: String,
    ): UrlTransition =
        when {
            scheme == FILE_SCHEME -> enterFile(state)
            state.special && state.base?.scheme == scheme ->
                UrlTransition.Advance(UrlState.SPECIAL_RELATIVE_OR_AUTHORITY)
            state.special -> UrlTransition.Advance(UrlState.SPECIAL_AUTHORITY_SLASHES)
            state.remaining().startsWith("/") -> enterPathOrAuthority(state)
            else -> enterOpaquePath(state)
        }

    /** Records [PARSE-55] when a `file:` scheme is not followed by `//`, then enters FILE. */
    private fun enterFile(state: UrlParserState): UrlTransition {
        if (!state.remaining().startsWith("//")) {
            state.errors.add(ValidationError.MISSING_AUTHORITY_SLASHES)
        }
        return UrlTransition.Advance(UrlState.FILE)
    }

    /** Consumes the leading `/` of `scheme:/…` and enters PATH_OR_AUTHORITY ([PARSE-15]). */
    private fun enterPathOrAuthority(state: UrlParserState): UrlTransition {
        state.pos += 1
        return UrlTransition.Advance(UrlState.PATH_OR_AUTHORITY)
    }

    /** Marks the URL cannot-be-a-base and enters OPAQUE_PATH ([PARSE-15] final branch). */
    private fun enterOpaquePath(state: UrlParserState): UrlTransition {
        state.isOpaque = true
        return UrlTransition.Advance(UrlState.OPAQUE_PATH)
    }

    // --- no scheme / special-relative-or-authority / path-or-authority (§8.3) --------

    /**
     * NO_SCHEME (§8.3 [PARSE-17]/[PARSE-18]; WHATWG no-scheme state).
     *
     * Resolves a relative reference against [UrlParserState.base]: a missing or opaque-path base
     * fails (except a bare fragment against an opaque base), a `file` base enters FILE, any other
     * base enters RELATIVE.
     */
    internal fun noSchemeState(state: UrlParserState): UrlTransition {
        val base = state.base
        val c = state.currentChar()
        return when {
            base == null -> UrlTransition.Fail(UriParseError.MissingScheme)
            base.path is UrlPath.Opaque -> noSchemeOpaqueBase(state, base, c)
            base.scheme != FILE_SCHEME -> UrlTransition.Reconsume(UrlState.RELATIVE)
            else -> UrlTransition.Reconsume(UrlState.FILE)
        }
    }

    /** Handles an opaque-path base: only a pruned bare fragment ([PARSE-18]) succeeds. */
    private fun noSchemeOpaqueBase(
        state: UrlParserState,
        base: ParsedComponents,
        c: Char?,
    ): UrlTransition {
        if (c == null && state.fragmentRaw != null) {
            copyOpaqueBaseForFragment(state, base)
            return UrlTransition.Advance(UrlState.NO_SCHEME)
        }
        return UrlTransition.Fail(UriParseError.MissingScheme)
    }

    /** Copies scheme/opaque-path/query from an opaque base for a fragment-only reference ([PARSE-18]). */
    private fun copyOpaqueBaseForFragment(
        state: UrlParserState,
        base: ParsedComponents,
    ) {
        state.scheme = base.scheme
        state.isOpaque = true
        state.opaque = (base.path as UrlPath.Opaque).path
        state.query = base.query
    }

    /**
     * SPECIAL_RELATIVE_OR_AUTHORITY (§8.3 [PARSE-20]; WHATWG special-relative-or-authority state).
     *
     * Exactly `//` enters the authority machinery; otherwise it is a relative reference.
     */
    internal fun specialRelativeOrAuthorityState(state: UrlParserState): UrlTransition {
        val c = state.currentChar()
        if (c == '/' && state.peek(1) == '/') {
            state.pos += 1
            return UrlTransition.Advance(UrlState.SPECIAL_AUTHORITY_IGNORE_SLASHES)
        }
        state.errors.add(ValidationError.MISSING_AUTHORITY_SLASHES)
        return UrlTransition.Reconsume(UrlState.RELATIVE)
    }

    /** PATH_OR_AUTHORITY (§8.3 [PARSE-21]; WHATWG path-or-authority state). */
    internal fun pathOrAuthorityState(state: UrlParserState): UrlTransition {
        val c = state.currentChar()
        return if (c == '/') {
            UrlTransition.Advance(UrlState.AUTHORITY)
        } else {
            UrlTransition.Reconsume(UrlState.PATH)
        }
    }

    // --- relative / relative-slash (§8.3 [PARSE-22]) ---------------------------------

    /**
     * RELATIVE (§8.3 [PARSE-22]; WHATWG relative state).
     *
     * Adopts the base scheme, then branches on the leading code point: `/` (or special `\`) into
     * RELATIVE_SLASH, otherwise copies the base authority/path/query and continues per the code
     * point (query, fragment-only, or a shortened path).
     */
    internal fun relativeState(state: UrlParserState): UrlTransition {
        val base = checkNotNull(state.base) { "relative state requires a base" }
        state.scheme = base.scheme
        state.special = baseIsSpecial(base)
        val c = state.currentChar()
        return when {
            c == '/' -> UrlTransition.Advance(UrlState.RELATIVE_SLASH)
            state.special && c == '\\' -> relativeBackslash(state)
            else -> relativeOther(state, base, c)
        }
    }

    /** True when [base] is non-null with a special scheme (SPEC §6; WHATWG "is special"). */
    private fun baseIsSpecial(base: ParsedComponents): Boolean {
        val scheme = base.scheme ?: return false
        return Scheme.isSpecial(scheme)
    }

    /** Records [PARSE-22]'s invalid-reverse-solidus and routes a special `\` into RELATIVE_SLASH. */
    private fun relativeBackslash(state: UrlParserState): UrlTransition {
        state.errors.add(ValidationError.BACKSLASH_AS_SOLIDUS)
        return UrlTransition.Advance(UrlState.RELATIVE_SLASH)
    }

    /** Copies the base authority/path/query, then dispatches by the current code point ([PARSE-22]). */
    private fun relativeOther(
        state: UrlParserState,
        base: ParsedComponents,
        c: Char?,
    ): UrlTransition {
        copyBaseAuthority(state, base)
        state.path.clear()
        state.path.addAll(cloneBasePath(base))
        state.query = base.query
        return relativeOtherTransition(state, c)
    }

    /** Per-code-point transition for [relativeOther]: query, fragment-only EOF, or shortened path. */
    private fun relativeOtherTransition(
        state: UrlParserState,
        c: Char?,
    ): UrlTransition =
        when {
            c == '?' -> {
                state.query = ""
                UrlTransition.Advance(UrlState.QUERY)
            }
            c == null -> UrlTransition.Advance(UrlState.RELATIVE)
            else -> {
                state.query = null
                shortenPath(state)
                UrlTransition.Reconsume(UrlState.PATH)
            }
        }

    /**
     * RELATIVE_SLASH (§8.3; WHATWG relative-slash state).
     *
     * A special-scheme `/` or `\` collapses into the authority machinery; a non-special `/`
     * enters AUTHORITY; anything else copies the base authority and falls into PATH.
     */
    internal fun relativeSlashState(state: UrlParserState): UrlTransition {
        val base = checkNotNull(state.base) { "relative slash requires a base" }
        val c = state.currentChar()
        return when {
            state.special && (c == '/' || c == '\\') -> relativeSlashSpecial(state, c)
            c == '/' -> UrlTransition.Advance(UrlState.AUTHORITY)
            else -> relativeSlashCopyAuthority(state, base)
        }
    }

    /** Records invalid-reverse-solidus for a `\` and enters the special authority machinery. */
    private fun relativeSlashSpecial(
        state: UrlParserState,
        c: Char?,
    ): UrlTransition {
        if (c == '\\') {
            state.errors.add(ValidationError.BACKSLASH_AS_SOLIDUS)
        }
        return UrlTransition.Advance(UrlState.SPECIAL_AUTHORITY_IGNORE_SLASHES)
    }

    /** Copies the base authority (no path) and reconsumes in PATH (WHATWG relative-slash final branch). */
    private fun relativeSlashCopyAuthority(
        state: UrlParserState,
        base: ParsedComponents,
    ): UrlTransition {
        copyBaseAuthority(state, base)
        return UrlTransition.Reconsume(UrlState.PATH)
    }

    /** Copies the base's username/password/host/port into [state]. */
    private fun copyBaseAuthority(
        state: UrlParserState,
        base: ParsedComponents,
    ) {
        state.username = base.username
        state.password = base.password
        state.host = base.host
        state.port = base.port
    }

    // --- special authority slashes / ignore slashes (§8.3 [PARSE-24]/[PARSE-25]) -----

    /** SPECIAL_AUTHORITY_SLASHES (§8.3 [PARSE-24]; WHATWG special-authority-slashes state). */
    internal fun specialAuthoritySlashesState(state: UrlParserState): UrlTransition {
        val c = state.currentChar()
        if (c == '/' && state.peek(1) == '/') {
            state.pos += 1
            return UrlTransition.Advance(UrlState.SPECIAL_AUTHORITY_IGNORE_SLASHES)
        }
        state.errors.add(ValidationError.MISSING_AUTHORITY_SLASHES)
        return UrlTransition.Reconsume(UrlState.SPECIAL_AUTHORITY_IGNORE_SLASHES)
    }

    /**
     * SPECIAL_AUTHORITY_IGNORE_SLASHES (§8.3 [PARSE-25]; WHATWG special-authority-ignore-slashes
     * state).
     *
     * Collapses any further run of `/` and `\` (recording one error each), then reconsumes the
     * first non-slash in AUTHORITY.
     */
    internal fun specialAuthorityIgnoreSlashesState(state: UrlParserState): UrlTransition {
        val c = state.currentChar()
        if (c != null && (c == '/' || c == '\\')) {
            state.errors.add(ValidationError.MISSING_AUTHORITY_SLASHES)
            return UrlTransition.Advance(UrlState.SPECIAL_AUTHORITY_IGNORE_SLASHES)
        }
        return UrlTransition.Reconsume(UrlState.AUTHORITY)
    }

    // --- path start / path (§8.3 [PARSE-38]–[PARSE-40]) ------------------------------

    /** PATH_START (§8.3 [PARSE-38]; WHATWG path-start state). */
    internal fun pathStartState(state: UrlParserState): UrlTransition {
        val c = state.currentChar()
        return if (state.special) pathStartSpecial(state, c) else pathStartNonSpecial(state, c)
    }

    /** Special-scheme PATH_START: always enters PATH; a non-slash reconsumes, a `\` records an error. */
    private fun pathStartSpecial(
        state: UrlParserState,
        c: Char?,
    ): UrlTransition {
        if (c == '\\') {
            state.errors.add(ValidationError.BACKSLASH_AS_SOLIDUS)
        }
        val isSlash = c == '/' || c == '\\'
        return if (isSlash) UrlTransition.Advance(UrlState.PATH) else UrlTransition.Reconsume(UrlState.PATH)
    }

    /**
     * Non-special PATH_START: `?` opens the query; EOF terminates with an empty path (PATH never
     * runs, so no root segment is synthesized); a leading `/` is consumed, anything else
     * reconsumed, in PATH.
     */
    private fun pathStartNonSpecial(
        state: UrlParserState,
        c: Char?,
    ): UrlTransition =
        when {
            c == '?' -> {
                state.query = ""
                UrlTransition.Advance(UrlState.QUERY)
            }
            c == null -> UrlTransition.Advance(UrlState.PATH)
            c == '/' -> UrlTransition.Advance(UrlState.PATH)
            else -> UrlTransition.Reconsume(UrlState.PATH)
        }

    /**
     * PATH (§8.3 [PARSE-39]/[PARSE-40]; WHATWG path state), code-point at a time.
     *
     * A segment boundary (`/`, special `\`, `?`, or EOF) processes the accumulated buffer with
     * dot-segment and Windows-drive handling; any other code point is appended raw for boundary
     * percent-encoding.
     */
    internal fun pathState(state: UrlParserState): UrlTransition {
        val c = state.currentChar()
        return if (isPathSegmentEnd(state, c)) pathBoundary(state, c) else pathAccumulate(state, c)
    }

    /** Appends a non-boundary code point to the segment buffer (encoded later, on the boundary). */
    private fun pathAccumulate(
        state: UrlParserState,
        c: Char?,
    ): UrlTransition {
        val ch = checkNotNull(c) { "path accumulate must not see EOF" }
        state.buffer.append(ch)
        return UrlTransition.Advance(UrlState.PATH)
    }

    /** Processes the buffered segment at a boundary and transitions to QUERY or stays in PATH. */
    private fun pathBoundary(
        state: UrlParserState,
        c: Char?,
    ): UrlTransition {
        if (state.special && c == '\\') {
            state.errors.add(ValidationError.BACKSLASH_AS_SOLIDUS)
        }
        val seg = state.buffer.toString()
        state.buffer.setLength(0)
        val cIsSeparator = c == '/' || (state.special && c == '\\')
        processPathSegment(state, seg, cIsSeparator)
        return pathBoundaryTransition(state, c)
    }

    /** Applies single-/double-dot shortening or appends the segment ([PARSE-39]; WHATWG path state). */
    private fun processPathSegment(
        state: UrlParserState,
        seg: String,
        cIsSeparator: Boolean,
    ) {
        when {
            isDoubleDotSegment(seg) -> {
                shortenPath(state)
                if (!cIsSeparator) state.path.add("")
            }
            isSingleDotSegment(seg) -> if (!cIsSeparator) state.path.add("")
            else -> appendPathSegment(state, seg)
        }
    }

    /** A `?` at a path boundary opens the query; every other boundary continues in PATH. */
    private fun pathBoundaryTransition(
        state: UrlParserState,
        c: Char?,
    ): UrlTransition {
        if (c == '?') {
            state.query = ""
            return UrlTransition.Advance(UrlState.QUERY)
        }
        return UrlTransition.Advance(UrlState.PATH)
    }

    // --- opaque path / query (§8.3 [PARSE-41]/[PARSE-42]) ----------------------------

    /**
     * OPAQUE_PATH (§8.3 [PARSE-41]; WHATWG opaque-path state), scanned to the first `?` or EOF.
     *
     * The slice is C0-control percent-encoded; a single trailing space immediately before a `?`
     * or `#` is emitted as `%20` ([PARSE-41]).
     */
    internal fun opaquePathState(state: UrlParserState): UrlTransition {
        val queryAt = state.input.indexOf('?', state.pos)
        val end = if (queryAt < 0) state.input.length else queryAt
        val raw = state.input.substring(state.pos, end)
        val hasTrailingDelimiter = end < state.input.length || state.fragmentRaw != null
        state.opaque = encodeOpaque(raw, hasTrailingDelimiter)
        state.isOpaque = true
        state.pos = end
        if (end < state.input.length) {
            state.query = ""
            return UrlTransition.Advance(UrlState.QUERY)
        }
        return UrlTransition.Advance(UrlState.OPAQUE_PATH)
    }

    /** C0-control-encodes an opaque slice, applying the trailing-space `%20` rule ([PARSE-41]). */
    private fun encodeOpaque(
        raw: String,
        hasTrailingDelimiter: Boolean,
    ): String {
        val encoded = PercentCodec.encode(raw, PercentEncodeSets.C0_CONTROL)
        return if (hasTrailingDelimiter && encoded.endsWith(' ')) {
            encoded.dropLast(1) + "%20"
        } else {
            encoded
        }
    }

    /**
     * QUERY (§8.3 [PARSE-42]; WHATWG query state), scanned to EOF (the fragment was pruned).
     *
     * The slice is percent-encoded with the special-query set for special schemes, otherwise the
     * query set, and appended to the (already non-null) query buffer.
     */
    internal fun queryState(state: UrlParserState): UrlTransition {
        require(state.query != null) { "query state entered without an opening '?'" }
        val raw = state.input.substring(state.pos)
        val set = if (state.special) PercentEncodeSets.SPECIAL_QUERY else PercentEncodeSets.QUERY
        state.query = (state.query ?: "") + PercentCodec.encode(raw, set)
        state.pos = state.input.length
        return UrlTransition.Advance(UrlState.QUERY)
    }
}
