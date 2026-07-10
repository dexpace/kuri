/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.host

import org.dexpace.kuri.error.HostError
import org.dexpace.kuri.error.ParseResult
import org.dexpace.kuri.error.UriParseError

/**
 * Strips the surrounding `[`/`]`, returning the bracket contents ([HOST-4]/[HOST-5]).
 *
 * Shared by both the WHATWG ([UrlHostParser]) and RFC 3986 ([UriHostParser]) bracketed-literal
 * paths, which both isolate the interior before delegating to the IPv6/IPvFuture grammars.
 */
internal fun bracketInterior(input: String): String {
    require(input.length >= 2) { "bracketed literal too short: $input" }
    return input.substring(1, input.length - 1)
}

/**
 * The malformed-literal failure for a bracketed host that does not close ([HOST-4]/[HOST-5]).
 *
 * Shared by both host parsers so an unterminated `[`…` reports the same [HostError.Ipv6Malformed].
 */
internal fun bracketError(input: String): ParseResult<Host> =
    ParseResult.Err(UriParseError.InvalidHost(input, HostError.Ipv6Malformed))
