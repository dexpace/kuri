/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.parser

import org.dexpace.kuri.error.ParseResult
import org.dexpace.kuri.error.UriParseError
import org.dexpace.kuri.error.ValidationError
import org.dexpace.kuri.error.ValidationErrorKind
import org.dexpace.kuri.host.Host
import org.dexpace.kuri.host.UrlHostParser
import org.dexpace.kuri.percent.PercentCodec
import org.dexpace.kuri.percent.PercentEncodeSets
import org.dexpace.kuri.scheme.Scheme
import org.dexpace.kuri.text.isAsciiDigit

/** WHATWG `localhost`, mapped to the empty host in the FILE_HOST state ([PARSE-37]). */
private const val LOCALHOST: String = "localhost"

/** The inclusive `Url`-profile port ceiling, a 16-bit unsigned integer ([PARSE-33]). */
private const val MAX_PORT: Long = 65535L

/** Sentinel for "no `@` found in the authority span" returned by [UrlParserAuthority]. */
private const val NOT_FOUND: Int = -1

/** Radix for the base-10 port integer accumulation ([PARSE-33]). */
private const val DECIMAL_RADIX: Int = 10

/**
 * The authority, userinfo, host, port, and `file` sub-machine states of the §8.3 `Url`-profile
 * state machine (WHATWG basic URL parser).
 *
 * Each `internal` member is exactly one WHATWG state function (AUTHORITY, HOST, PORT, FILE,
 * FILE_SLASH, FILE_HOST); `private` members decompose them under the 60-line / 2-return budgets.
 * The authority and host states scan forward and jump the single pointer to the next component
 * boundary so it stays non-decreasing ([PARSE-9]), rather than rewinding as the WHATWG
 * char-at-a-time phrasing does. The routing/path/query states live in the sibling
 * [UrlParserStates] (hence the cohesive grouping and `TooManyFunctions` suppression).
 */
@Suppress("TooManyFunctions")
internal object UrlParserAuthority {
    // --- authority + userinfo (§8.3 [PARSE-26]–[PARSE-28], §8.4) ----------------------

    /**
     * AUTHORITY (§8.3 [PARSE-26]–[PARSE-28], §8.4; WHATWG authority state), forward-scanning.
     *
     * Locates the authority delimiter and the LAST `@` before it; everything before that `@` is
     * userinfo (split on its first `:`), and the pointer advances to the host candidate.
     */
    internal fun authorityState(state: UrlParserState): UrlTransition {
        val delimiter = authorityDelimiter(state, state.pos)
        val lastAt = lastAtSign(state, state.pos, delimiter)
        return resolveAuthority(state, lastAt, delimiter)
    }

    /** Splits off userinfo at [lastAt] (if any) and positions the pointer at the host candidate. */
    private fun resolveAuthority(
        state: UrlParserState,
        lastAt: Int,
        delimiter: Int,
    ): UrlTransition =
        when {
            lastAt == NOT_FOUND -> UrlTransition.Reconsume(UrlState.HOST)
            lastAt + 1 == delimiter -> UrlTransition.Fail(UriParseError.EmptyHost)
            else -> {
                applyUserinfo(state, state.pos, lastAt)
                state.pos = lastAt + 1
                UrlTransition.Reconsume(UrlState.HOST)
            }
        }

    /** The first authority terminator at or after [from] (`/`, `?`, `#`, special `\`), or the length. */
    private fun authorityDelimiter(
        state: UrlParserState,
        from: Int,
    ): Int {
        var i = from
        var found = state.input.length
        while (i < state.input.length && found == state.input.length) {
            if (isAuthorityTerminator(state, state.input[i])) found = i
            i++
        }
        check(found in from..state.input.length) { "authority delimiter out of range: $found" }
        return found
    }

    /** The index of the LAST `@` in `[from, end)` (§8.4 [PARSE-44]), or [NOT_FOUND]. */
    private fun lastAtSign(
        state: UrlParserState,
        from: Int,
        end: Int,
    ): Int {
        require(end <= state.input.length) { "authority end out of range: $end" }
        var i = end - 1
        var found = NOT_FOUND
        while (i >= from && found == NOT_FOUND) {
            if (state.input[i] == '@') found = i
            i--
        }
        return found
    }

    /**
     * Accumulates the userinfo span `[start, lastAt)` into username/password (§8.4
     * [PARSE-44]–[PARSE-47]).
     *
     * The first `:` splits username from password; any non-final `@` inside the span and any
     * subsequent `:` are percent-encoded by the userinfo set (so `a@b@h` yields `a%40b` and
     * `u:p:q` yields password `p%3Aq`), matching the last-`@`/first-`:` rules.
     */
    private fun applyUserinfo(
        state: UrlParserState,
        start: Int,
        lastAt: Int,
    ) {
        require(start <= lastAt) { "userinfo span is inverted: $start..$lastAt" }
        recordAtSigns(state, start, lastAt)
        val userinfo = state.input.substring(start, lastAt)
        val colon = userinfo.indexOf(':')
        val rawUser = if (colon >= 0) userinfo.substring(0, colon) else userinfo
        state.username = PercentCodec.encode(rawUser, PercentEncodeSets.USERINFO)
        state.password =
            if (colon >= 0) PercentCodec.encode(userinfo.substring(colon + 1), PercentEncodeSets.USERINFO) else ""
        state.atSignSeen = true
    }

    /**
     * Records one [ValidationErrorKind.INVALID_CREDENTIALS] per `@` in `[start, lastAt]`
     * ([PARSE-56]): both a non-final `@` folded into the username and the final delimiter
     * itself (guaranteed present at [lastAt] by [lastAtSign]'s contract), each at its own offset.
     */
    private fun recordAtSigns(
        state: UrlParserState,
        start: Int,
        lastAt: Int,
    ) {
        for (i in start..lastAt) {
            if (state.input[i] == '@') {
                state.errors.add(ValidationError(ValidationErrorKind.INVALID_CREDENTIALS, at = i))
            }
        }
    }

    // --- host (§8.3 [PARSE-29]–[PARSE-31]) -------------------------------------------

    /**
     * HOST (§8.3 [PARSE-29]–[PARSE-31]; WHATWG host/hostname state), forward-scanning.
     *
     * Scans to the first `:` outside brackets (→ PORT) or the first path terminator (→ PATH_START),
     * isolates the host slice, and delegates classification to [UrlHostParser]. An empty special-scheme
     * host is fatal ([PARSE-31]).
     */
    internal fun hostState(state: UrlParserState): UrlTransition {
        if (state.stateOverride != null && state.scheme == FILE_SCHEME) {
            return UrlTransition.Reconsume(UrlState.FILE_HOST)
        }
        val scan = scanHost(state)
        val hostSlice = state.input.substring(state.pos, scan.first)
        return finishHost(state, hostSlice, scan.first, scan.second)
    }

    /** Returns the host-delimiter index and whether it is a port-introducing `:` ([PARSE-30]). */
    private fun scanHost(state: UrlParserState): Pair<Int, Boolean> {
        var i = state.pos
        var insideBrackets = false
        var found = NOT_FOUND
        var isColon = false
        while (i < state.input.length && found == NOT_FOUND) {
            val ch = state.input[i]
            if (ch == '[') {
                insideBrackets = true
            } else if (ch == ']') {
                insideBrackets = false
            }
            when {
                !insideBrackets && ch == ':' -> {
                    found = i
                    isColon = true
                }
                isAuthorityTerminator(state, ch) -> found = i
                else -> i++
            }
        }
        val idx = if (found == NOT_FOUND) state.input.length else found
        return idx to isColon
    }

    /** Validates the host slice for emptiness, then parses and transitions ([PARSE-30]/[PARSE-31]). */
    private fun finishHost(
        state: UrlParserState,
        hostSlice: String,
        idx: Int,
        isColon: Boolean,
    ): UrlTransition =
        when {
            isColon && hostSlice.isEmpty() -> UrlTransition.Fail(UriParseError.EmptyHost)
            // WHATWG host state, `:` branch: "If state override is given and state override is
            // hostname state, then return" -- BEFORE the buffer is ever parsed as a host, so a `:`
            // in a `hostname` setter value is a no-op (the port-bearing suffix is for `host` only).
            isColon && state.stateOverride == StateOverride.HOSTNAME -> UrlTransition.Done
            !isColon && state.special && hostSlice.isEmpty() -> UrlTransition.Fail(UriParseError.EmptyHost)
            // WHATWG host state, delimiter branch: "if state override is given, buffer is the empty
            // string, and either url includes credentials or url's port is non-null, then return" --
            // clearing a non-special host is refused when doing so would strand existing
            // userinfo/port with no host to attach to.
            !isColon && hostSlice.isEmpty() && state.stateOverride != null && hasCredentialsOrPort(state) -> {
                state.pos = idx
                UrlTransition.Done
            }
            else -> parseAndStoreHost(state, hostSlice, idx, isColon)
        }

    /** True when [state] has a non-empty username/password or an explicit port ([PARSE-30] guard). */
    private fun hasCredentialsOrPort(state: UrlParserState): Boolean =
        state.username.isNotEmpty() || state.password.isNotEmpty() || state.port != null

    /** Parses [hostSlice] via [UrlHostParser] and advances to PORT (on `:`) or PATH_START. */
    private fun parseAndStoreHost(
        state: UrlParserState,
        hostSlice: String,
        idx: Int,
        isColon: Boolean,
    ): UrlTransition {
        // The host slice begins at the current pointer (the Ok branch only advances it afterwards),
        // so this is the host's start offset used to rebase a host-relative error to full input.
        val hostStart = state.pos
        return when (val host = resolveHost(state, hostSlice)) {
            is ParseResult.Err -> UrlTransition.Fail(rebaseHostError(host.error, hostStart))
            is ParseResult.Ok -> {
                state.host = host.value
                state.pos = idx
                hostTransition(state, isColon)
            }
        }
    }

    /**
     * Rebases a host-pipeline error's offset from host-slice-relative to full-input coordinates
     * ([HOST-37]), the `Url`-profile counterpart of the same rebase `UriParser` applies.
     *
     * [UrlHostParser] reports [UriParseError.ForbiddenHostCodePoint.at] relative to the host
     * substring it was handed — for an opaque host that offset is already in the raw (pre-decode)
     * slice, and for a special domain [UrlHostParser] has mapped it back through IDNA and
     * percent-decoding to the same raw-slice coordinates — so adding [hostStart], the slice's own
     * offset in the pre-processed input, recovers the position [UriParseError.ForbiddenHostCodePoint]
     * documents. Every other host error carries no offset ([UriParseError.EmptyHost]) or the
     * offending text verbatim ([UriParseError.InvalidHost]), so no other variant is adjusted.
     */
    private fun rebaseHostError(
        error: UriParseError,
        hostStart: Int,
    ): UriParseError {
        require(hostStart >= 0) { "host start offset must not be negative: $hostStart" }
        return when (error) {
            is UriParseError.ForbiddenHostCodePoint -> error.copy(at = hostStart + error.at)
            else -> error
        }
    }

    /** Post-host transition, honouring a HOST/HOSTNAME override ([SET-*]; WHATWG host state). */
    private fun hostTransition(
        state: UrlParserState,
        isColon: Boolean,
    ): UrlTransition =
        when (state.stateOverride) {
            StateOverride.HOSTNAME -> UrlTransition.Done
            StateOverride.HOST -> if (isColon) UrlTransition.Advance(UrlState.PORT) else UrlTransition.Done
            else -> if (isColon) UrlTransition.Advance(UrlState.PORT) else UrlTransition.Reconsume(UrlState.PATH_START)
        }

    /** An empty non-special host is [Host.Empty]; otherwise the §7 host pipeline classifies it. */
    private fun resolveHost(
        state: UrlParserState,
        hostSlice: String,
    ): ParseResult<Host> =
        if (hostSlice.isEmpty()) {
            ParseResult.Ok(Host.Empty)
        } else {
            UrlHostParser.parse(
                hostSlice,
                isSpecial = state.special,
                isFile = false,
            )
        }

    // --- port (§8.3 [PARSE-32]–[PARSE-34]) -------------------------------------------

    /**
     * PORT (§8.3 [PARSE-32]–[PARSE-34]; WHATWG port state), forward-scanning the digit run.
     *
     * A non-digit that is also not a terminator is fatal; an empty run leaves the port unspecified;
     * otherwise the value is range-checked and default-port-elided ([PARSE-34]).
     */
    internal fun portState(state: UrlParserState): UrlTransition {
        var i = state.pos
        while (i < state.input.length && state.input[i].isAsciiDigit()) {
            i++
        }
        val digits = state.input.substring(state.pos, i)
        val terminator = if (i < state.input.length) state.input[i] else null
        return resolvePort(state, digits, i, terminator)
    }

    /** Validates the post-digit terminator and finalizes (or skips) the port ([PARSE-32]–[PARSE-34]). */
    private fun resolvePort(
        state: UrlParserState,
        digits: String,
        end: Int,
        terminator: Char?,
    ): UrlTransition {
        // Per the WHATWG port state, ANY state override ends the digit run on any trailing
        // character -- not just an authority terminator, and not only for the `port` setter itself
        // -- since there is no following state to reconsume into ([PARSE-34]; e.g. the `port`
        // setter's "8080stuff" keeps port 8080, and the `host` setter's "h:8080stuff" must stop
        // the same way once it falls through into port sub-parsing).
        val terminated =
            terminator == null || isAuthorityTerminator(state, terminator) || state.stateOverride != null
        if (!terminated) {
            return UrlTransition.Fail(UriParseError.InvalidPort(digits + terminator))
        }
        return finalizePort(state, digits, end)
    }

    /** Parses, range-checks, and default-port-elides the digit run, then reconsumes in PATH_START. */
    private fun finalizePort(
        state: UrlParserState,
        digits: String,
        end: Int,
    ): UrlTransition {
        val overridden = state.stateOverride != null
        if (digits.isNotEmpty()) {
            val value = computePort(digits)
            if (value == null) {
                // Outside an override, an out-of-range port is fatal (WHATWG "port-out-of-range,
                // return failure"). Inside one, the setter algorithm mutates the live URL field by
                // field as it goes, so a downstream failure here does not roll back a host/hostname
                // that already committed earlier in the same run -- the port is simply left as-is.
                if (!overridden) return UrlTransition.Fail(UriParseError.InvalidPort(digits))
            } else {
                state.port = elidedPort(state, value)
            }
        }
        // WHATWG port state: "if buffer is not the empty string" gates the assignment above, so an
        // empty digit run (state override, delimiter reached with nothing scanned) leaves the port
        // untouched rather than clearing it to null.
        state.pos = end
        return if (overridden) UrlTransition.Done else UrlTransition.Reconsume(UrlState.PATH_START)
    }

    /** The base-10 value of [digits], or `null` when it exceeds the 16-bit ceiling ([PARSE-33]). */
    private fun computePort(digits: String): Long? {
        var value = 0L
        var overflow = false
        for (d in digits) {
            value = value * DECIMAL_RADIX + (d - '0')
            if (value > MAX_PORT) overflow = true
        }
        return if (digits.isEmpty() || overflow) null else value
    }

    /** Drops the port when it equals the scheme's default ([PARSE-34]); an empty run yields `null`. */
    private fun elidedPort(
        state: UrlParserState,
        value: Long?,
    ): Int? {
        if (value == null) {
            return null
        }
        val scheme = state.scheme
        val isDefault = scheme != null && Scheme.defaultPort(scheme)?.toLong() == value
        return if (isDefault) null else value.toInt()
    }

    // --- file sub-machine (§8.3 [PARSE-35]–[PARSE-37]) -------------------------------

    /**
     * FILE (§8.3 [PARSE-35]; WHATWG file state).
     *
     * Establishes the `file` scheme (special, empty host), routes a leading `/`/`\` into
     * FILE_SLASH, copies a `file` base where present, else falls into PATH.
     */
    internal fun fileState(state: UrlParserState): UrlTransition {
        state.scheme = FILE_SCHEME
        state.special = true
        state.host = Host.Empty
        val c = state.currentChar()
        return when {
            c == '/' || c == '\\' -> fileLeadingSlash(state, c)
            baseIsFile(state) -> fileWithBase(state, c)
            else -> UrlTransition.Reconsume(UrlState.PATH)
        }
    }

    /** A leading `/`/`\` enters FILE_SLASH, recording invalid-reverse-solidus for `\` ([PARSE-35]). */
    private fun fileLeadingSlash(
        state: UrlParserState,
        c: Char?,
    ): UrlTransition {
        if (c == '\\') {
            state.errors.add(ValidationError(ValidationErrorKind.BACKSLASH_AS_SOLIDUS, at = state.pos))
        }
        return UrlTransition.Advance(UrlState.FILE_SLASH)
    }

    /** True when [UrlParserState.base] is a `file:` URL (WHATWG file-state base branch). */
    private fun baseIsFile(state: UrlParserState): Boolean = state.base?.scheme == FILE_SCHEME

    /** Copies a `file` base's host/path/query and dispatches by the current code point ([PARSE-35]). */
    private fun fileWithBase(
        state: UrlParserState,
        c: Char?,
    ): UrlTransition {
        val base = checkNotNull(state.base) { "file base branch requires a base" }
        state.host = base.host
        state.path.clear()
        state.path.addAll(cloneBasePath(base))
        state.query = base.query
        return fileBaseTransition(state, c)
    }

    /** Per-code-point transition for [fileWithBase]: query, fragment-only EOF, or drive-aware path. */
    private fun fileBaseTransition(
        state: UrlParserState,
        c: Char?,
    ): UrlTransition =
        when {
            c == '?' -> {
                state.query = ""
                UrlTransition.Advance(UrlState.QUERY)
            }
            c == null -> UrlTransition.Advance(UrlState.FILE)
            else -> fileBaseDriveBranch(state)
        }

    /**
     * Shortens the copied path unless the remaining input is a drive letter, then reconsumes PATH.
     * The drive-letter branch records [PARSE-57] before clearing the path.
     */
    private fun fileBaseDriveBranch(state: UrlParserState): UrlTransition {
        state.query = null
        if (startsWithWindowsDrive(state.fromCurrent())) {
            state.errors.add(ValidationError(ValidationErrorKind.FILE_INVALID_WINDOWS_DRIVE_LETTER, at = state.pos))
            state.path.clear()
        } else {
            shortenPath(state)
        }
        return UrlTransition.Reconsume(UrlState.PATH)
    }

    /**
     * FILE_SLASH (§8.3 [PARSE-36]; WHATWG file-slash state).
     *
     * A second `/`/`\` enters FILE_HOST; otherwise applies the `file`-base drive-letter quirk and
     * falls into PATH.
     */
    internal fun fileSlashState(state: UrlParserState): UrlTransition {
        val c = state.currentChar()
        if (c == '/' || c == '\\') {
            if (c == '\\') {
                state.errors.add(ValidationError(ValidationErrorKind.BACKSLASH_AS_SOLIDUS, at = state.pos))
            }
            return UrlTransition.Advance(UrlState.FILE_HOST)
        }
        return fileSlashBase(state)
    }

    /** Applies the WHATWG file-slash base drive-letter carry-over, then reconsumes in PATH. */
    private fun fileSlashBase(state: UrlParserState): UrlTransition {
        val base = state.base
        if (base?.scheme == FILE_SCHEME) {
            state.host = base.host
            carryDriveLetter(state, base)
        }
        return UrlTransition.Reconsume(UrlState.PATH)
    }

    /** Appends the base's drive-letter segment when the remaining input is not itself a drive. */
    private fun carryDriveLetter(
        state: UrlParserState,
        base: ParsedComponents,
    ) {
        val baseSegments = (base.path as? ComponentPath.Segments)?.segments ?: emptyList()
        val firstIsDrive = baseSegments.isNotEmpty() && isNormalizedWindowsDrive(baseSegments[0])
        if (!startsWithWindowsDrive(state.fromCurrent()) && firstIsDrive) {
            state.path.add(baseSegments[0])
        }
    }

    /**
     * FILE_HOST (§8.3 [PARSE-37]; WHATWG file-host state), forward-scanning.
     *
     * Scans to the first `/`, `\`, `?`, `#`, or EOF; a Windows-drive-letter buffer is reinterpreted
     * as a path (the pointer stays put so PATH re-reads it), an empty buffer is the empty host, and
     * `localhost` is mapped to the empty host ([PARSE-37]).
     */
    internal fun fileHostState(state: UrlParserState): UrlTransition {
        var i = state.pos
        while (i < state.input.length && !isFileHostEnd(state.input[i])) {
            i++
        }
        val buffer = state.input.substring(state.pos, i)
        return resolveFileHost(state, buffer, i)
    }

    /** True when [ch] ends a file-host scan (`/`, `\`, `?`, `#`) ([PARSE-37]). */
    private fun isFileHostEnd(ch: Char): Boolean = ch == '/' || ch == '\\' || ch == '?' || ch == '#'

    /** Classifies the file-host [buffer]: drive letter, empty host, or a parsed (localhost→empty) host. */
    private fun resolveFileHost(
        state: UrlParserState,
        buffer: String,
        end: Int,
    ): UrlTransition =
        when {
            // WHATWG file-host state 1.1: the drive-letter reinterpretation applies only outside a
            // state override; a `host`/`hostname` setter value that happens to look like a drive
            // letter (e.g. "C:") is instead parsed as an ordinary host in the `else` branch below.
            state.stateOverride == null && isWindowsDriveLetter(buffer) -> driveLetterAsPath(state)
            buffer.isEmpty() -> {
                state.host = Host.Empty
                state.pos = end
                fileHostTransition(state)
            }
            else -> parseFileHost(state, buffer, end)
        }

    /**
     * Records [PARSE-58] and reinterprets a drive-letter file-host buffer as a path; the pointer
     * is left untouched so PATH re-reads the same buffer it was scanned from.
     */
    private fun driveLetterAsPath(state: UrlParserState): UrlTransition {
        state.errors.add(
            ValidationError(ValidationErrorKind.FILE_INVALID_WINDOWS_DRIVE_LETTER_HOST, at = state.pos),
        )
        return UrlTransition.Reconsume(UrlState.PATH)
    }

    /**
     * Post-file-host transition ([PARSE-37]; WHATWG "if state override is given, then return"): a
     * `host`/`hostname` setter stops the moment the host is set, never touching the seeded path,
     * while a full parse continues into path start state to build the path from scratch.
     */
    private fun fileHostTransition(state: UrlParserState): UrlTransition =
        if (state.stateOverride != null) UrlTransition.Done else UrlTransition.Reconsume(UrlState.PATH_START)

    /** Parses a non-empty file host and maps `localhost` to the empty host ([PARSE-37]). */
    private fun parseFileHost(
        state: UrlParserState,
        buffer: String,
        end: Int,
    ): UrlTransition {
        // The file-host buffer begins at the current pointer (the Ok branch only advances it
        // afterwards), so this is the host start offset used to rebase a host-relative error.
        val hostStart = state.pos
        return when (
            val host =
                UrlHostParser.parse(
                    buffer,
                    isSpecial = true,
                    isFile = true,
                )
        ) {
            is ParseResult.Err -> UrlTransition.Fail(rebaseHostError(host.error, hostStart))
            is ParseResult.Ok -> {
                state.host = if (host.value == Host.RegName(LOCALHOST)) Host.Empty else host.value
                state.pos = end
                fileHostTransition(state)
            }
        }
    }
}
