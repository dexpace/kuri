/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.host

import org.dexpace.kuri.error.HostError
import org.dexpace.kuri.error.ParseResult
import org.dexpace.kuri.error.UriParseError
import org.dexpace.kuri.text.hasPercentHexPairAt
import org.dexpace.kuri.text.isAsciiHexDigit
import org.dexpace.kuri.text.isUnreserved

/** Sentinel for "no offending index found" returned by the host scanners. */
private const val NOT_FOUND: Int = -1

/** Length of a percent-encoded triplet, `%XX`, consumed whole by the reg-name scanner. */
private const val PCT_TRIPLET_LENGTH: Int = 3

/** Smallest index the IPvFuture `.` may occupy: after `v` and at least one HEXDIG ([HOST-42]). */
private const val MIN_IP_FUTURE_DOT_INDEX: Int = 2

/** The `.` separating the IPvFuture version digits from their payload. */
private const val IP_FUTURE_DOT: Char = '.'

/** RFC 3986 `sub-delims` set (`"!" / "$" / "&" / "'" / "(" / ")" / "*" / "+" / "," / ";" / "="`). */
private const val SUB_DELIMS: String = "!\$&'()*+,;="

/**
 * The RFC 3986 §3.2.2 host parser (SPEC §7.2/§7.5/§7.8, [HOST-3] row 5).
 *
 * Composes the already-implemented host pieces — [Ipv6], [Ipv4], the forbidden-code-point tables —
 * into the RFC grammar (`IP-literal` / `IPvFuture` / `IPv4address` / `reg-name`): a `[`-prefixed
 * literal first, then an empty host, then the IPv4/reg-name split. No piece is reimplemented here;
 * this layer only selects and validates.
 *
 * The pipeline is a short ordered procedure decomposed into single-purpose helpers (each well under
 * the line/return budgets), which is the intent of the "small functions" rule and legitimately
 * exceeds the per-object count.
 */
@Suppress("TooManyFunctions")
internal object UriHostParser {
    /**
     * Parses [input] (the host substring, brackets retained) into a [Host] under the RFC 3986 rules.
     *
     * The branch is selected by [HOST-3]: a `[`-prefixed literal first, then empty, then the
     * IPv4/reg-name split. Failures are returned as [ParseResult.Err] rather than thrown ([ERR-1]).
     *
     * @param input the host text isolated by the authority splitter (§8), `[`/`]` still present.
     * @param allowIpv6ZoneId whether an RFC 6874 `%25` IPv6 zone id is accepted (default off, [HOST-18]).
     * @return the parsed [Host], or a [UriParseError]-tagged failure.
     */
    internal fun parse(
        input: String,
        allowIpv6ZoneId: Boolean = false,
    ): ParseResult<Host> =
        when {
            input.firstOrNull() == BRACKET_OPEN -> parseBracketedUri(input, allowIpv6ZoneId)
            input.isEmpty() -> ParseResult.Ok(Host.Empty)
            else -> parseUriHostNonBracket(input)
        }

    /** A `Uri` non-bracket host is an `IPv4address` when it parses as one, else an RFC reg-name. */
    private fun parseUriHostNonBracket(input: String): ParseResult<Host> {
        require(input.isNotEmpty()) { "empty host reached the IPv4/reg-name split" }
        val asIpv4 = Ipv4Rfc3986.parse(input)
        return if (asIpv4.isOk()) asIpv4 else validateRegNameRfc(input)
    }

    /** Parses a `Uri` `[`…`]` literal: IPvFuture when version-prefixed, else IPv6 ([HOST-5]/[HOST-42]). */
    private fun parseBracketedUri(
        input: String,
        allowIpv6ZoneId: Boolean,
    ): ParseResult<Host> {
        require(input.firstOrNull() == BRACKET_OPEN) { "bracketed parser needs a leading '['" }
        return if (input.endsWith(BRACKET_CLOSE)) {
            classifyIpLiteral(bracketInterior(input), input, allowIpv6ZoneId)
        } else {
            bracketError(input)
        }
    }

    /** Routes the bracket [interior] to IPvFuture or IPv6 by its leading code point ([HOST-42]). */
    private fun classifyIpLiteral(
        interior: String,
        input: String,
        allowIpv6ZoneId: Boolean,
    ): ParseResult<Host> =
        if (isVersionPrefixed(interior)) {
            parseIpFuture(interior, input)
        } else {
            Ipv6.parse(interior, allowIpv6ZoneId)
        }

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
            c == '%' && hasPercentHexPairAt(input, index) -> PCT_TRIPLET_LENGTH
            c.isUnreserved() || isSubDelim(c) -> 1
            else -> 0
        }
    }

    /** True when [c] is an RFC 3986 `sub-delims` code point. */
    private fun isSubDelim(c: Char): Boolean = c in SUB_DELIMS

    /** True when [c] is admissible in an IPvFuture payload (`unreserved / sub-delims / ":"`). */
    private fun isIpFuturePayloadChar(c: Char): Boolean = c.isUnreserved() || isSubDelim(c) || c == ':'
}
