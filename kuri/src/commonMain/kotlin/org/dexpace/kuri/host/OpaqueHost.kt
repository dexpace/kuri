/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.host

import org.dexpace.kuri.error.ParseResult
import org.dexpace.kuri.error.UriParseError
import org.dexpace.kuri.percent.PercentCodec
import org.dexpace.kuri.percent.PercentEncodeSets

/** Sentinel for "no forbidden host code point found" returned by [firstForbiddenHostIndex]. */
private const val NOT_FOUND: Int = -1

/**
 * The WHATWG **opaque-host** parser (SPEC §7.5, [HOST-33]/[HOST-34]).
 *
 * Used for the host of a non-special `Url` scheme: the input is validated against the
 * forbidden-host code-point table (§7.6 [HOST-36]) and then UTF-8 percent-encoded with the
 * C0-control percent-encode set ([PCT-5]). No IDNA, lowercasing, or IPv4 interpretation is
 * applied — this is the verbatim, encode-only pipeline that produces a [Host.Opaque] (or
 * [Host.Empty] for empty input, [HOST-40]).
 */
internal object OpaqueHost {
    /**
     * Parses [input] as an opaque host (§7.5).
     *
     * Empty input yields [Host.Empty] ([HOST-40]). Otherwise the input is scanned for any
     * forbidden host code point *other than* `%` (which is permitted so percent-encoded octets
     * pass through, [HOST-33]); the first such code point fails with
     * [UriParseError.ForbiddenHostCodePoint] ([ERR-12]). A clean input is C0-control
     * percent-encoded ([HOST-34], [PCT-5]) and returned as [Host.Opaque].
     *
     * @param input the host substring, brackets already excluded by the authority splitter.
     * @return [Host.Empty] for empty input, [Host.Opaque] for a valid host, or an [ParseResult.Err]
     *   naming the first forbidden code point.
     */
    internal fun parse(input: String): ParseResult<Host> {
        val forbiddenAt = firstForbiddenHostIndex(input)
        check(forbiddenAt == NOT_FOUND || forbiddenAt in input.indices) {
            "forbidden index out of bounds: $forbiddenAt"
        }
        val result =
            when {
                input.isEmpty() -> ParseResult.Ok(Host.Empty)
                forbiddenAt != NOT_FOUND ->
                    ParseResult.Err(UriParseError.ForbiddenHostCodePoint(input[forbiddenAt].code, forbiddenAt))
                else -> ParseResult.Ok(Host.Opaque(PercentCodec.encode(input, PercentEncodeSets.C0_CONTROL)))
            }
        check(result is ParseResult.Ok || forbiddenAt != NOT_FOUND) {
            "Err must be produced only when a forbidden code point was found"
        }
        return result
    }

    /**
     * Returns the index of the first forbidden host code point in [input] that is not `%`, or
     * [NOT_FOUND] when none is present.
     *
     * `%` is excluded from the scan because it is permitted in an opaque host ([HOST-33]); all
     * forbidden host code points are in the Basic Multilingual Plane, so a per-`Char` scan is exact.
     */
    private fun firstForbiddenHostIndex(input: String): Int {
        var index = 0
        var found = NOT_FOUND
        while (index < input.length && found == NOT_FOUND) {
            val cp = input[index]
            if (cp != '%' && isForbiddenHostCodePoint(cp)) {
                found = index
            }
            index++
        }
        check(found == NOT_FOUND || found in input.indices) { "found index out of bounds: $found" }
        check(found == NOT_FOUND || input[found] != '%') { "'%' must not be flagged forbidden" }
        return found
    }
}
