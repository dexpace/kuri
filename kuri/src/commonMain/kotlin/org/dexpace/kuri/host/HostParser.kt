/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.host

import org.dexpace.kuri.ParseProfile
import org.dexpace.kuri.error.HostError
import org.dexpace.kuri.error.ParseResult
import org.dexpace.kuri.error.UriParseError
import org.dexpace.kuri.error.isOk
import org.dexpace.kuri.idna.Idna
import org.dexpace.kuri.percent.PercentCodec
import org.dexpace.kuri.text.isAsciiAlphanumeric
import org.dexpace.kuri.text.isAsciiHexDigit

/** Opening delimiter of an IP-literal; a host beginning with it dispatches to §7.2/§7.8. */
private const val BRACKET_OPEN: Char = '['

/** Closing delimiter an IP-literal MUST carry ([HOST-4]/[HOST-5]). */
private const val BRACKET_CLOSE: Char = ']'

/** Sentinel for "no offending index found" returned by the host scanners. */
private const val NOT_FOUND: Int = -1

/** Length of a percent-encoded triplet, `%XX`, consumed whole by the reg-name scanner. */
private const val PCT_TRIPLET_LENGTH: Int = 3

/** Smallest index the IPvFuture `.` may occupy: after `v` and at least one HEXDIG ([HOST-42]). */
private const val MIN_IP_FUTURE_DOT_INDEX: Int = 2

/** The `.` separating the IPvFuture version digits from their payload. */
private const val IP_FUTURE_DOT: Char = '.'

/** RFC 3986 `unreserved` symbol characters beyond ALPHA/DIGIT (`ALPHA / DIGIT / "-._~"`). */
private const val UNRESERVED_SYMBOLS: String = "-._~"

/** RFC 3986 `sub-delims` set (`"!" / "$" / "&" / "'" / "(" / ")" / "*" / "+" / "," / ";" / "="`). */
private const val SUB_DELIMS: String = "!\$&'()*+,;="

/**
 * The profile-aware host-parser dispatch of SPEC §7.1 ([HOST-3]).
 *
 * Composes the already-implemented host pieces — [Ipv6], [Ipv4], [OpaqueHost],
 * [Idna], the forbidden-code-point tables, and [PercentCodec] — into the single
 * ordered branch selection of [HOST-3], parameterized by [ParseProfile]. The `Url`
 * profile runs the WHATWG host parser (IPv6 literal, opaque host, or the special
 * domain/IPv4 pipeline); the `Uri` profile runs the RFC 3986 §3.2.2 grammar
 * (`IP-literal` / `IPvFuture` / `IPv4address` / `reg-name`). No piece is
 * reimplemented here; this layer only selects and validates.
 *
 * The two profile pipelines are each a short ordered procedure; they are decomposed
 * into single-purpose helpers (each well under the line/return budgets), which is the
 * intent of the "small functions" rule and legitimately exceeds the per-object count.
 */
@Suppress("TooManyFunctions")
internal object HostParser {
    /**
     * Parses [input] (the host substring, brackets retained) into a [Host] under [profile] (§7.1).
     *
     * The branch is selected by [HOST-3]: a `[`-prefixed literal first, then empty/opaque/domain
     * per profile and specialness. Failures are returned as [ParseResult.Err] rather than thrown
     * ([ERR-1]).
     *
     * @param input the host text isolated by the authority splitter (§8), `[`/`]` still present.
     * @param profile selects the WHATWG (`Url`) or RFC 3986 (`Uri`) acceptance rules.
     * @param isSpecial whether the scheme is special; gates the `Url` domain/IPv4 pipeline ([HOST-3]).
     * @param isFile whether the scheme is `file`; an empty `file` host is permitted ([HOST-39]).
     * @return the parsed [Host], or a [UriParseError]-tagged failure.
     */
    internal fun parse(
        input: String,
        profile: ParseProfile,
        isSpecial: Boolean,
        isFile: Boolean = false,
    ): ParseResult<Host> = if (profile.isWhatwg) parseUrlHost(input, isSpecial, isFile) else parseUriHost(input)

    // --- Url profile (WHATWG host parser, §7.2/§7.4/§7.5/§7.7) ------------------------

    /** Ordered `Url`-profile dispatch ([HOST-3] rows 1–4): literal, opaque, empty, then domain. */
    private fun parseUrlHost(
        input: String,
        isSpecial: Boolean,
        isFile: Boolean,
    ): ParseResult<Host> =
        when {
            input.firstOrNull() == BRACKET_OPEN -> parseBracketedUrl(input)
            !isSpecial -> OpaqueHost.parse(input)
            input.isEmpty() -> if (isFile) ParseResult.Ok(Host.Empty) else ParseResult.Err(UriParseError.EmptyHost)
            else -> parseSpecialDomain(input)
        }

    /** Parses a `Url` `[`…`]` literal strictly as IPv6: a missing `]` is fatal ([HOST-4]). */
    private fun parseBracketedUrl(input: String): ParseResult<Host> =
        if (input.endsWith(BRACKET_CLOSE)) {
            Ipv6.parse(bracketInterior(input))
        } else {
            bracketError(input)
        }

    /**
     * The special-scheme domain pipeline (§7.4): UTF-8 percent-decode, run UTS-46 ToASCII, then
     * classify the ASCII domain. A ToASCII failure propagates unchanged ([HOST-26]).
     */
    private fun parseSpecialDomain(input: String): ParseResult<Host> {
        require(input.isNotEmpty()) { "empty domain reached the IDNA pipeline" }
        val decoded = PercentCodec.decode(input)
        return when (val ascii = Idna.domainToAscii(decoded)) {
            is ParseResult.Err -> ascii
            is ParseResult.Ok -> classifyAsciiDomain(ascii.value)
        }
    }

    /**
     * Classifies the produced ASCII domain ([HOST-29]/[HOST-30]): a forbidden-domain code point is
     * fatal, an ends-in-a-number host parses as IPv4, otherwise it is a lowercase-ASCII [Host.RegName].
     */
    private fun classifyAsciiDomain(asciiDomain: String): ParseResult<Host> {
        val forbiddenAt = firstForbiddenDomainIndex(asciiDomain)
        check(forbiddenAt == NOT_FOUND || forbiddenAt in asciiDomain.indices) { "bad index: $forbiddenAt" }
        return when {
            forbiddenAt != NOT_FOUND ->
                ParseResult.Err(UriParseError.ForbiddenHostCodePoint(asciiDomain[forbiddenAt].code, forbiddenAt))
            Ipv4.endsInANumber(asciiDomain) -> Ipv4.parse(asciiDomain, ParseProfile.URL)
            else -> ParseResult.Ok(Host.RegName(asciiDomain))
        }
    }

    /** Index of the first forbidden-domain code point in [domain] (§7.6 [HOST-37]), or [NOT_FOUND]. */
    private fun firstForbiddenDomainIndex(domain: String): Int {
        var index = 0
        var found = NOT_FOUND
        while (index < domain.length && found == NOT_FOUND) {
            if (isForbiddenDomainCodePoint(domain[index])) found = index
            index++
        }
        check(found == NOT_FOUND || found in domain.indices) { "found out of bounds: $found" }
        return found
    }

    // --- Uri profile (RFC 3986 §3.2.2 host grammar, §7.2/§7.5/§7.8) -------------------

    /** Ordered `Uri`-profile dispatch ([HOST-3] row 5): IP-literal, empty host, then IPv4/reg-name. */
    private fun parseUriHost(input: String): ParseResult<Host> =
        when {
            input.firstOrNull() == BRACKET_OPEN -> parseBracketedUri(input)
            input.isEmpty() -> ParseResult.Ok(Host.Empty)
            else -> parseUriHostNonBracket(input)
        }

    /** A `Uri` non-bracket host is an `IPv4address` when it parses as one, else an RFC reg-name. */
    private fun parseUriHostNonBracket(input: String): ParseResult<Host> {
        require(input.isNotEmpty()) { "empty host reached the IPv4/reg-name split" }
        val asIpv4 = Ipv4.parse(input, ParseProfile.URI)
        return if (asIpv4.isOk()) asIpv4 else validateRegNameRfc(input)
    }

    /** Parses a `Uri` `[`…`]` literal: IPvFuture when version-prefixed, else IPv6 ([HOST-5]/[HOST-42]). */
    private fun parseBracketedUri(input: String): ParseResult<Host> {
        require(input.firstOrNull() == BRACKET_OPEN) { "bracketed parser needs a leading '['" }
        return if (input.endsWith(BRACKET_CLOSE)) {
            classifyIpLiteral(bracketInterior(input), input)
        } else {
            bracketError(input)
        }
    }

    /** Routes the bracket [interior] to IPvFuture or IPv6 by its leading code point ([HOST-42]). */
    private fun classifyIpLiteral(
        interior: String,
        input: String,
    ): ParseResult<Host> = if (isVersionPrefixed(interior)) parseIpFuture(interior, input) else Ipv6.parse(interior)

    /** Validates the IPvFuture ABNF and produces a [Host.IpFuture], or a malformed-literal failure. */
    private fun parseIpFuture(
        interior: String,
        input: String,
    ): ParseResult<Host> =
        if (isValidIpFuture(interior)) {
            ParseResult.Ok(Host.IpFuture(interior))
        } else {
            ParseResult.Err(UriParseError.InvalidHost(input, HostError.Ipv6Malformed))
        }

    /**
     * Tests the RFC 3986 `IPvFuture` production `"v" 1*HEXDIG "." 1*( unreserved / sub-delims / ":" )`
     * over the bracket [interior]; the leading `v`/`V` is guaranteed by the caller ([HOST-42]).
     */
    private fun isValidIpFuture(interior: String): Boolean {
        require(isVersionPrefixed(interior)) { "IPvFuture must start with 'v'/'V'" }
        val dot = interior.indexOf(IP_FUTURE_DOT)
        val version = if (dot >= 0) interior.substring(1, dot) else ""
        val payload = if (dot >= 0) interior.substring(dot + 1) else ""
        return dot >= MIN_IP_FUTURE_DOT_INDEX &&
            version.all { it.isAsciiHexDigit() } &&
            payload.isNotEmpty() &&
            payload.all { isIpFuturePayloadChar(it) }
    }

    /** True when [interior] opens with the IPvFuture version marker `v`/`V` ([HOST-42]). */
    private fun isVersionPrefixed(interior: String): Boolean =
        interior.firstOrNull() == 'v' || interior.firstOrNull() == 'V'

    /**
     * Validates an RFC 3986 `reg-name` `*( unreserved / pct-encoded / sub-delims )` and preserves it
     * verbatim ([HOST-33], no IDNA/lowercasing); the first invalid code point is fatal.
     */
    private fun validateRegNameRfc(input: String): ParseResult<Host> {
        val badAt = firstInvalidRegNameIndex(input)
        check(badAt == NOT_FOUND || badAt in input.indices) { "bad index: $badAt" }
        return if (badAt == NOT_FOUND) {
            ParseResult.Ok(Host.RegName(input))
        } else {
            ParseResult.Err(UriParseError.ForbiddenHostCodePoint(input[badAt].code, badAt))
        }
    }

    /** Index of the first code point not admitted by the RFC reg-name grammar, or [NOT_FOUND]. */
    private fun firstInvalidRegNameIndex(input: String): Int {
        var index = 0
        var bad = NOT_FOUND
        while (index < input.length && bad == NOT_FOUND) {
            val step = regNameUnitLength(input, index)
            if (step == 0) bad = index else index += step
        }
        check(bad == NOT_FOUND || bad in input.indices) { "bad out of bounds: $bad" }
        return bad
    }

    /** Length of the reg-name unit at [index]: `3` for a triplet, `1` for unreserved/sub-delim, `0` if invalid. */
    private fun regNameUnitLength(
        input: String,
        index: Int,
    ): Int {
        require(index in input.indices) { "index out of range: $index" }
        val c = input[index]
        return when {
            c == '%' && isPctEncodedAt(input, index) -> PCT_TRIPLET_LENGTH
            isUnreserved(c) || isSubDelim(c) -> 1
            else -> 0
        }
    }

    // --- shared helpers --------------------------------------------------------------

    /** Strips the surrounding `[`/`]`, returning the bracket contents ([HOST-4]/[HOST-5]). */
    private fun bracketInterior(input: String): String {
        require(input.length >= 2) { "bracketed literal too short: $input" }
        return input.substring(1, input.length - 1)
    }

    /** The malformed-literal failure for a bracketed host that does not close ([HOST-4]/[HOST-5]). */
    private fun bracketError(input: String): ParseResult<Host> =
        ParseResult.Err(UriParseError.InvalidHost(input, HostError.Ipv6Malformed))

    /** True when a `%` at [index] introduces a valid `pct-encoded` triplet (`%` then two HEXDIG). */
    private fun isPctEncodedAt(
        input: String,
        index: Int,
    ): Boolean = index + 2 < input.length && input[index + 1].isAsciiHexDigit() && input[index + 2].isAsciiHexDigit()

    /** True when [c] is an RFC 3986 `unreserved` code point. */
    private fun isUnreserved(c: Char): Boolean = c.isAsciiAlphanumeric() || c in UNRESERVED_SYMBOLS

    /** True when [c] is an RFC 3986 `sub-delims` code point. */
    private fun isSubDelim(c: Char): Boolean = c in SUB_DELIMS

    /** True when [c] is admissible in an IPvFuture payload (`unreserved / sub-delims / ":"`). */
    private fun isIpFuturePayloadChar(c: Char): Boolean = isUnreserved(c) || isSubDelim(c) || c == ':'
}
