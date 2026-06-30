/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.parser

import org.dexpace.kuri.ParseProfile
import org.dexpace.kuri.error.ParseResult
import org.dexpace.kuri.error.UriParseError
import org.dexpace.kuri.host.Host
import org.dexpace.kuri.host.HostParser
import org.dexpace.kuri.scheme.Scheme
import org.dexpace.kuri.text.isAsciiAlpha
import org.dexpace.kuri.text.isAsciiAlphanumeric
import org.dexpace.kuri.text.isAsciiDigit
import org.dexpace.kuri.text.isAsciiHexDigit
import org.dexpace.kuri.text.isC0Control

/** Sentinel returned by the index scanners when no offending position exists. */
private const val NOT_FOUND: Int = -1

/** U+007F DELETE; rejected alongside the C0 controls as a disallowed raw code point (RFC 3986 §2). */
private const val DEL: Char = '\u007F'

/** Number of leading code points consumed by the authority-introducing `//` (RFC 3986 §3.2). */
private const val DOUBLE_SLASH_LENGTH: Int = 2

/**
 * Fixed maximum input length in UTF-16 code units ([PARSE-1]); a longer input is rejected before
 * the scan begins. A generous bound that no well-formed URI reference approaches in practice.
 */
private const val MAX_INPUT_LENGTH: Int = 8192

/**
 * The `Uri`-profile component splitter of SPEC §8 ([PARSE-9]..[PARSE-43]) for the RFC 3986
 * generic-URI syntax (§3, Appendix B).
 *
 * Decomposes a URI-reference into its five components with a single forward, hand-rolled scan (no
 * regex, no backtracking): scheme, authority (userinfo / host / port), path, query, and fragment.
 * The posture is strict RFC 3986 and *preserve-by-default* ([PARSE-52]):
 *
 * - An authority is introduced ONLY by exactly `//` after `scheme:` (or a leading `//` in a
 *   scheme-relative reference); a third `/` begins the path ([PARSE-50]).
 * - U+005C (`\`) is an ordinary code point, never a delimiter ([PARSE-49]); it is preserved.
 * - No tab/LF/CR stripping and no leading/trailing trim ([PARSE-53]); an embedded C0 control or DEL
 *   appearing where the §4 grammar forbids it is fatal ([PARSE-4]/[PARSE-6]).
 * - No default-port elision and no dot-segment removal ([PARSE-34]/[PARSE-39]); the path is kept
 *   verbatim and resolution is deferred to §9.
 * - The scheme is preserved with its original case; case-insensitive comparison is a §11 concern.
 *
 * Because §12.2's `UriParseError` catalog has no component-specific "invalid path/query/fragment
 * unit" variant, a fatal disallowed code point or malformed percent triplet in the path, query,
 * fragment, or userinfo surfaces as [UriParseError.InvalidPercentEncoding] (the catalog's
 * component-level invalid-unit fatal, [ERR-15]); host failures keep their precise §7 errors.
 *
 * The algorithm is a short ordered procedure decomposed into single-purpose helpers, each well
 * under the line/return budgets; this legitimately exceeds the per-object function count.
 */
@Suppress("TooManyFunctions")
internal object UriParser {
    /**
     * Splits [input] into its [ParsedComponents] under RFC 3986 (§3, Appendix B; SPEC §8).
     *
     * Failures are returned as [ParseResult.Err] rather than thrown ([ERR-1]). A relative reference
     * (no scheme) is accepted and parsed standalone, deferring base merging to §9 ([PARSE-19]).
     *
     * @param input the URI-reference text exactly as supplied; no pre-processing is applied.
     * @return the decomposed components, or the first fatal [UriParseError] in input order.
     */
    internal fun parse(input: String): ParseResult<ParsedComponents> =
        when {
            input.length > MAX_INPUT_LENGTH ->
                ParseResult.Err(UriParseError.InputTooLong(input.length, MAX_INPUT_LENGTH))

            else -> decomposeAndBuild(input)
        }

    /** Runs the structural decomposition ([decompose]) and then the component build ([buildComponents]). */
    private fun decomposeAndBuild(input: String): ParseResult<ParsedComponents> =
        when (val sections = decompose(input)) {
            is ParseResult.Err -> sections
            is ParseResult.Ok -> buildComponents(sections.value)
        }

    // --- structural decomposition (Appendix B, [PARSE-7]/[PARSE-14]/[PARSE-21]) -------------------

    /**
     * Performs the Appendix B split into [Sections] (scheme / authority / path / query / fragment),
     * carrying each component's original-input start offset for later error positioning ([ERR-8]).
     *
     * Fragment and query are pruned from the end first ([PARSE-7]/[PARSE-42]), so every retained
     * prefix index equals its original-input index. Only an ill-formed scheme is fatal here.
     */
    private fun decompose(input: String): ParseResult<Sections> {
        require(input.length <= MAX_INPUT_LENGTH) { "input must be length-checked before decompose" }
        val frag = splitFragment(input)
        val query = splitQuery(frag.body)
        val scheme = splitScheme(query.hier)
        check(scheme.restStart in 0..query.hier.length) { "scheme rest start out of range" }
        return when (scheme.error) {
            null -> ParseResult.Ok(buildSections(frag, query, scheme))
            else -> ParseResult.Err(scheme.error)
        }
    }

    /** Splits off the fragment at the FIRST `#` ([PARSE-7]); a present `#` yields a defined fragment ([PARSE-8]). */
    private fun splitFragment(input: String): FragmentSplit {
        val hash = input.indexOf('#')
        return when {
            hash < 0 -> FragmentSplit(input, fragment = null, fragmentStart = NOT_FOUND)
            else -> FragmentSplit(input.substring(0, hash), input.substring(hash + 1), hash + 1)
        }
    }

    /** Splits off the query at the FIRST `?` in the fragment-pruned [body] ([PARSE-42]). */
    private fun splitQuery(body: String): QuerySplit {
        val mark = body.indexOf('?')
        return when {
            mark < 0 -> QuerySplit(body, query = null, queryStart = NOT_FOUND)
            else -> QuerySplit(body.substring(0, mark), body.substring(mark + 1), mark + 1)
        }
    }

    /**
     * Detects a scheme in [hier]: the prefix before the first `:` is a scheme only when that `:`
     * precedes any `/` ([PARSE-15]). A present-but-ill-formed candidate is fatal ([PARSE-16]/[ERR-9]).
     */
    private fun splitScheme(hier: String): SchemeSplit {
        val colon = hier.indexOf(':')
        val slash = hier.indexOf('/')
        val hasCandidate = colon >= 0 && (slash < 0 || colon < slash)
        return when {
            !hasCandidate -> SchemeSplit(scheme = null, restStart = 0, error = null)
            else -> validateCandidate(hier, colon)
        }
    }

    /** Accepts the scheme candidate `hier[0, colon)` when it satisfies §6, else reports an ill-formed scheme. */
    private fun validateCandidate(
        hier: String,
        colon: Int,
    ): SchemeSplit {
        require(colon in hier.indices) { "colon index out of range: $colon" }
        val candidate = hier.substring(0, colon)
        return when {
            Scheme.isValidScheme(candidate) -> SchemeSplit(candidate, colon + 1, error = null)
            else -> SchemeSplit(scheme = null, restStart = 0, error = schemeError(candidate))
        }
    }

    /** Builds the [UriParseError.InvalidScheme] for an ill-formed [candidate], locating the first bad code point. */
    private fun schemeError(candidate: String): UriParseError {
        val at = firstInvalidSchemeIndex(candidate)
        check(at in 0..candidate.length) { "scheme offense index out of range: $at" }
        return UriParseError.InvalidScheme(at = at, detail = "ill-formed scheme component")
    }

    /** Index of the first code point violating the §6 scheme grammar (`ALPHA *( ALPHA / DIGIT / +-. )`). */
    private fun firstInvalidSchemeIndex(candidate: String): Int =
        when {
            candidate.isEmpty() -> 0
            !candidate[0].isAsciiAlpha() -> 0
            else -> (1 until candidate.length).firstOrNull { !isSchemeTailChar(candidate[it]) } ?: 0
        }

    /** True when [c] may follow the first code point of a scheme (§6.2; ALPHA / DIGIT / `+` / `-` / `.`). */
    private fun isSchemeTailChar(c: Char): Boolean = c.isAsciiAlphanumeric() || c == '+' || c == '-' || c == '.'

    /** Assembles the [Sections] record, splitting the post-scheme remainder into authority and path. */
    private fun buildSections(
        frag: FragmentSplit,
        query: QuerySplit,
        scheme: SchemeSplit,
    ): Sections {
        require(scheme.restStart in 0..query.hier.length) { "rest start out of range: ${scheme.restStart}" }
        val rest = query.hier.substring(scheme.restStart)
        val split = splitAuthorityPath(rest, scheme.restStart)
        return Sections(
            scheme = scheme.scheme,
            authority = split.authority,
            authorityStart = split.authorityStart,
            path = split.path,
            pathStart = split.pathStart,
            query = query.query,
            queryStart = query.queryStart,
            fragment = frag.fragment,
            fragmentStart = frag.fragmentStart,
        )
    }

    /** Routes the post-scheme [rest] to the authority split when it opens with `//`, else treats it all as path. */
    private fun splitAuthorityPath(
        rest: String,
        restStart: Int,
    ): AuthorityPath =
        when {
            rest.startsWith("//") -> splitAfterDoubleSlash(rest, restStart)
            else -> AuthorityPath(authority = null, authorityStart = NOT_FOUND, path = rest, pathStart = restStart)
        }

    /** Authority runs from after `//` to the next `/` (which begins the path), or to end-of-input ([PARSE-50]). */
    private fun splitAfterDoubleSlash(
        rest: String,
        restStart: Int,
    ): AuthorityPath {
        require(rest.startsWith("//")) { "double-slash split needs a leading //" }
        val slash = rest.indexOf('/', DOUBLE_SLASH_LENGTH)
        val authStart = restStart + DOUBLE_SLASH_LENGTH
        val end = if (slash < 0) rest.length else slash
        val authority = rest.substring(DOUBLE_SLASH_LENGTH, end)
        val path = if (slash < 0) "" else rest.substring(slash)
        return AuthorityPath(authority, authStart, path, pathStart = restStart + end)
    }

    // --- component build & validation ([PARSE-26]..[PARSE-43]) ------------------------------------

    /** Parses the authority (when present), then finishes the components; a host/port failure is fatal. */
    private fun buildComponents(sections: Sections): ParseResult<ParsedComponents> =
        when (val authority = parseAuthority(sections)) {
            is ParseResult.Err -> authority
            is ParseResult.Ok -> finishComponents(sections, authority.value)
        }

    /** Parses the authority text into credentials, host, and port, or yields `null` when no `//` was present. */
    private fun parseAuthority(sections: Sections): ParseResult<Authority?> =
        when (val authority = sections.authority) {
            null -> ParseResult.Ok(null)
            else -> parsePresentAuthority(authority, sections.authorityStart)
        }

    /** Splits userinfo from host:port at the LAST `@` ([PARSE-44]); a forbidden userinfo unit is fatal. */
    private fun parsePresentAuthority(
        authority: String,
        authorityStart: Int,
    ): ParseResult<Authority?> {
        require(authorityStart >= 0) { "authority start must be known: $authorityStart" }
        val at = authority.lastIndexOf('@')
        val userinfo = if (at >= 0) authority.substring(0, at) else ""
        val hostPort = if (at >= 0) authority.substring(at + 1) else authority
        return when (val error = rawError(userinfo, authorityStart)) {
            null -> buildAuthority(userinfo, hostPort)
            else -> ParseResult.Err(error)
        }
    }

    /** Parses host (§7, `Uri` profile) and port, combining them with the split userinfo credentials. */
    private fun buildAuthority(
        userinfo: String,
        hostPort: String,
    ): ParseResult<Authority?> {
        val (username, password) = splitUserinfo(userinfo)
        val (host, portText) = splitHostPort(hostPort)
        return when (val parsed = HostParser.parse(host, ParseProfile.URI, isSpecial = false)) {
            is ParseResult.Err -> parsed
            is ParseResult.Ok -> attachPort(username, password, parsed.value, portText)
        }
    }

    /** Validates the optional port ([PARSE-32]/[PARSE-33]) and assembles the [Authority]. */
    private fun attachPort(
        username: String,
        password: String,
        host: Host,
        portText: String?,
    ): ParseResult<Authority?> =
        when (val port = parsePort(portText)) {
            is ParseResult.Err -> port
            is ParseResult.Ok -> ParseResult.Ok(Authority(username, password, host, port.value))
        }

    /** Splits userinfo into username and password at the FIRST `:` ([PARSE-45]); later `:` stay in the password. */
    private fun splitUserinfo(userinfo: String): Pair<String, String> {
        val colon = userinfo.indexOf(':')
        return when {
            colon < 0 -> Pair(userinfo, "")
            else -> Pair(userinfo.substring(0, colon), userinfo.substring(colon + 1))
        }
    }

    /** Separates host from an optional `:port`, honouring an IPv6 `[...]` literal's interior colons ([PARSE-29]). */
    private fun splitHostPort(hostPort: String): Pair<String, String?> =
        when {
            hostPort.startsWith("[") -> splitBracketedHostPort(hostPort)
            else -> splitPlainHostPort(hostPort)
        }

    /** A non-bracketed host ends at the first `:`, whose tail is the port; reg-name/IPv4 carry no inner colon. */
    private fun splitPlainHostPort(hostPort: String): Pair<String, String?> {
        val colon = hostPort.indexOf(':')
        return when {
            colon < 0 -> Pair(hostPort, null)
            else -> Pair(hostPort.substring(0, colon), hostPort.substring(colon + 1))
        }
    }

    /** A bracketed literal's port colon, if any, immediately follows the closing `]`. */
    private fun splitBracketedHostPort(hostPort: String): Pair<String, String?> {
        val close = hostPort.indexOf(']')
        return when {
            close < 0 -> Pair(hostPort, null)
            else -> bracketHostPort(hostPort, close)
        }
    }

    /** Splits a closed `[...]` literal from its trailing `:port`, or no port when nothing follows `]`. */
    private fun bracketHostPort(
        hostPort: String,
        close: Int,
    ): Pair<String, String?> {
        require(close in hostPort.indices) { "close bracket index out of range: $close" }
        val host = hostPort.substring(0, close + 1)
        val rest = hostPort.substring(close + 1)
        return when {
            rest.startsWith(":") -> Pair(host, rest.substring(1))
            else -> Pair(host, null)
        }
    }

    /**
     * Interprets the optional [text] as an RFC 3986 `*DIGIT` port ([PARSE-33]).
     *
     * `null`/empty denotes no explicit port. A non-digit code point, or an all-digit value that
     * overflows [Int], is fatal ([PARSE-32]); the `Uri` profile applies no 65535 range cap.
     */
    private fun parsePort(text: String?): ParseResult<Int?> =
        when {
            text.isNullOrEmpty() -> ParseResult.Ok(null)
            !text.all { it.isAsciiDigit() } -> ParseResult.Err(UriParseError.InvalidPort(text))
            else -> portFromDigits(text)
        }

    /** Converts an all-digit [text] to an [Int] port, reporting overflow as [UriParseError.InvalidPort]. */
    private fun portFromDigits(text: String): ParseResult<Int?> {
        require(text.isNotEmpty() && text.all { it.isAsciiDigit() }) { "portFromDigits needs digits: $text" }
        return when (val value = text.toIntOrNull()) {
            null -> ParseResult.Err(UriParseError.InvalidPort(text))
            else -> ParseResult.Ok(value)
        }
    }

    /** Validates the path, query, and fragment for forbidden units, then assembles the result. */
    private fun finishComponents(
        sections: Sections,
        authority: Authority?,
    ): ParseResult<ParsedComponents> =
        when (val error = validateText(sections)) {
            null -> ParseResult.Ok(assemble(sections, authority))
            else -> ParseResult.Err(error)
        }

    /** First forbidden unit across path, query, and fragment in input order, or `null` when all are clean. */
    private fun validateText(sections: Sections): UriParseError? =
        validateComponent(sections.path, sections.pathStart)
            ?: validateComponent(sections.query, sections.queryStart)
            ?: validateComponent(sections.fragment, sections.fragmentStart)

    /** Validates a present component's raw text; an absent (`null`) component contributes no error. */
    private fun validateComponent(
        text: String?,
        start: Int,
    ): UriParseError? =
        when (text) {
            null -> null
            else -> rawError(text, start)
        }

    /**
     * The first forbidden raw unit in [text] mapped to its original-input offset ([ERR-8]): a C0
     * control / DEL, or a malformed `%` triplet — both fatal in the strict `Uri` profile ([ERR-15]).
     */
    private fun rawError(
        text: String,
        start: Int,
    ): UriParseError? {
        val control = firstForbiddenIndex(text)
        val percent = firstBadPercentIndex(text)
        val at = earliest(control, percent)
        check(at == NOT_FOUND || at in text.indices) { "offense index out of range: $at" }
        return when (at) {
            NOT_FOUND -> null
            else -> UriParseError.InvalidPercentEncoding(at = start + at)
        }
    }

    /** Index of the first C0 control or DEL in [text] (RFC 3986 admits no raw control), or [NOT_FOUND]. */
    private fun firstForbiddenIndex(text: String): Int = text.indexOfFirst { it.isC0Control() || it == DEL }

    /** Index of the first `%` not introducing two ASCII hex digits in [text], or [NOT_FOUND]. */
    private fun firstBadPercentIndex(text: String): Int {
        var index = 0
        var bad = NOT_FOUND
        while (index < text.length && bad == NOT_FOUND) {
            if (text[index] == '%' && !isHexPairAt(text, index)) bad = index
            index++
        }
        check(bad == NOT_FOUND || bad in text.indices) { "bad percent index out of range: $bad" }
        return bad
    }

    /** True when a `%` at [index] is followed by two in-bounds ASCII hex digits. */
    private fun isHexPairAt(
        text: String,
        index: Int,
    ): Boolean = index + 2 < text.length && text[index + 1].isAsciiHexDigit() && text[index + 2].isAsciiHexDigit()

    /** The smaller non-negative index of [a] and [b], or the present one, or [NOT_FOUND] when both are absent. */
    private fun earliest(
        a: Int,
        b: Int,
    ): Int =
        when {
            a < 0 -> b
            b < 0 -> a
            else -> minOf(a, b)
        }

    /** Builds the immutable [ParsedComponents] from the validated structural [sections] and [authority]. */
    private fun assemble(
        sections: Sections,
        authority: Authority?,
    ): ParsedComponents =
        ParsedComponents(
            scheme = sections.scheme,
            username = authority?.username ?: "",
            password = authority?.password ?: "",
            host = authority?.host,
            port = authority?.port,
            path = pathToSegments(sections.path),
            query = sections.query,
            fragment = sections.fragment,
        )

    /**
     * Decomposes a raw path into [UrlPath.Segments] (SPEC §3.7, [MODEL-26]/[MODEL-27]).
     *
     * Empty path is `emptyList()`; an absolute path drops the leading empty element so root-only `/`
     * is `listOf("")`; a `Uri` rootless path keeps all segments. Trailing `/` is a trailing `""`.
     * No dot-segment removal is performed ([PARSE-39]). The `rooted` flag records the absolute
     * (leading `/`) versus rootless distinction so the serializer can round-trip it faithfully.
     */
    private fun pathToSegments(path: String): UrlPath.Segments =
        when {
            path.isEmpty() -> UrlPath.Segments(emptyList())
            path.startsWith("/") -> UrlPath.Segments(path.substring(1).split('/'), rooted = true)
            else -> UrlPath.Segments(path.split('/'), rooted = false)
        }

    // --- structural carriers (private, behaviour-free) -------------------------------------------

    /** The fragment-pruned [body] plus the captured [fragment] and its original-input [fragmentStart]. */
    private data class FragmentSplit(
        val body: String,
        val fragment: String?,
        val fragmentStart: Int,
    )

    /** The query-pruned [hier] prefix plus the captured [query] and its original-input [queryStart]. */
    private data class QuerySplit(
        val hier: String,
        val query: String?,
        val queryStart: Int,
    )

    /** The detected [scheme] (or `null`), the index where the remainder begins, and a fatal scheme [error]. */
    private data class SchemeSplit(
        val scheme: String?,
        val restStart: Int,
        val error: UriParseError?,
    )

    /** The authority substring (or `null`) and the path substring, with their original-input start offsets. */
    private data class AuthorityPath(
        val authority: String?,
        val authorityStart: Int,
        val path: String,
        val pathStart: Int,
    )

    /** The five structural components with the original-input offsets needed for error positioning. */
    private data class Sections(
        val scheme: String?,
        val authority: String?,
        val authorityStart: Int,
        val path: String,
        val pathStart: Int,
        val query: String?,
        val queryStart: Int,
        val fragment: String?,
        val fragmentStart: Int,
    )

    /** The parsed authority pieces: decoded-form credentials, the §7 [host], and the optional [port]. */
    private data class Authority(
        val username: String,
        val password: String,
        val host: Host,
        val port: Int?,
    )
}
