/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.host

import org.dexpace.kuri.error.ParseResult
import org.dexpace.kuri.error.UriParseError
import org.dexpace.kuri.idna.Idna
import org.dexpace.kuri.percent.PercentCodec

/** Sentinel for "no offending index found" returned by the host scanners. */
private const val NOT_FOUND: Int = -1

/**
 * The WHATWG host parser of SPEC §7.2/§7.4/§7.5/§7.7 ([HOST-3] rows 1–4).
 *
 * Composes the already-implemented host pieces — [Ipv6], [Ipv4], [OpaqueHost], [Idna], the
 * forbidden-code-point tables, and [PercentCodec] — into the ordered `Url` branch selection: a
 * `[`-prefixed IPv6 literal, then an opaque host for non-special schemes, an empty (`file`) host,
 * or the special domain/IPv4 pipeline. No piece is reimplemented here; this layer only selects and
 * validates.
 */
internal object UrlHostParser {
    /**
     * Parses [input] (the host substring, brackets retained) into a [Host] under the WHATWG rules.
     *
     * The branch is selected by [HOST-3]: a `[`-prefixed literal first, then opaque/empty/domain by
     * specialness. Failures are returned as [ParseResult.Err] rather than thrown ([ERR-1]).
     *
     * @param input the host text isolated by the authority splitter (§8), `[`/`]` still present.
     * @param isSpecial whether the scheme is special; gates the domain/IPv4 pipeline ([HOST-3]).
     * @param isFile whether the scheme is `file`; an empty `file` host is permitted ([HOST-39]).
     * @return the parsed [Host], or a [UriParseError]-tagged failure.
     */
    internal fun parse(
        input: String,
        isSpecial: Boolean,
        isFile: Boolean = false,
    ): ParseResult<Host> =
        when {
            input.firstOrNull() == BRACKET_OPEN -> parseBracketedUrl(input)
            !isSpecial -> OpaqueHost.parse(input)
            input.isEmpty() -> if (isFile) ParseResult.Ok(Host.Empty) else ParseResult.Err(UriParseError.EmptyHost)
            else -> parseSpecialDomain(input)
        }

    /** Parses a `Url` `[`…`]` literal strictly as IPv6, rejecting any zone id; a missing `]` is fatal ([HOST-4]). */
    private fun parseBracketedUrl(input: String): ParseResult<Host> =
        if (input.endsWith(BRACKET_CLOSE)) {
            Ipv6.parse(bracketInterior(input))
        } else {
            bracketError(input)
        }

    /**
     * The special-scheme domain pipeline (§7.4): UTF-8 percent-decode, run the WHATWG "domain to
     * ASCII" wrapper ([Idna.domainToAsciiForUrl] — UTS-46 with the ASCII fast-path and empty-result
     * rule), then classify the ASCII domain. A domain-to-ASCII failure propagates unchanged ([HOST-26]).
     */
    private fun parseSpecialDomain(input: String): ParseResult<Host> {
        require(input.isNotEmpty()) { "empty domain reached the IDNA pipeline" }
        val decoded = PercentCodec.decode(input)
        return when (val ascii = Idna.domainToAsciiForUrl(decoded)) {
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
            Ipv4.endsInANumber(asciiDomain) -> Ipv4Whatwg.parse(asciiDomain)
            else -> ParseResult.Ok(Host.RegName(asciiDomain))
        }
    }

    /** Index of the first forbidden-domain code point in [domain] (§7.6 [HOST-37]), or [NOT_FOUND] (`-1`). */
    private fun firstForbiddenDomainIndex(domain: String): Int = domain.indexOfFirst { isForbiddenDomainCodePoint(it) }
}
