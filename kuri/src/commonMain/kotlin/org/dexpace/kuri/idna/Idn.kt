/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.idna

import org.dexpace.kuri.error.ParseResult
import kotlin.jvm.JvmStatic

/**
 * Internationalized Domain Name conversion (UTS-46 ToASCII / ToUnicode, SPEC §7.4).
 *
 * The public face of the internal [Idna] engine, exposing the two directions of the WHATWG
 * `Url`-profile transform so callers can convert a domain independently of parsing a whole URL:
 * [toAscii] produces the Punycode (`xn--`) A-label form used on the wire, and [toUnicode] produces
 * the human-readable U-label form used for display. Both use the bundled UTS-46 mapping tables, so
 * results do not depend on any platform IDN library.
 *
 * The two directions differ in how they treat failure, per the standard: [toAscii] is fallible and
 * returns a [ParseResult] (a domain that cannot be encoded is a genuine error), whereas [toUnicode]
 * is best-effort and *total* (an undecodable label is simply passed through, never rejected).
 *
 * @see org.dexpace.kuri.Url
 */
public object Idn {
    /**
     * Converts [domain] to its ASCII (Punycode) form under UTS-46 ToASCII (SPEC §7.4, [HOST-26]).
     *
     * Runs the WHATWG `Url`-profile pipeline: an all-ASCII domain is lower-cased and returned as-is
     * (its Unicode validity failures are non-fatal for web compatibility), while a domain carrying any
     * non-ASCII code point is mapped, NFC-normalized, and Punycode-encoded label by label. Failure is
     * a *value*, not an exception: a disallowed code point, an undecodable `xn--` label, or a result
     * that collapses to the empty string yields a [ParseResult.Err] rather than throwing.
     *
     * @param domain the domain to encode; may mix scripts and casing.
     * @return [ParseResult.Ok] with the ASCII domain, or [ParseResult.Err] on a ToASCII failure.
     */
    @JvmStatic
    public fun toAscii(domain: String): ParseResult<String> = Idna.domainToAsciiForUrl(domain)

    /**
     * Converts [domain] to its Unicode (display) form under UTS-46 ToUnicode (SPEC §7.4).
     *
     * Maps and NFC-normalizes the domain, then Punycode-decodes each `xn--` label back to its
     * U-label. Best-effort and *total*: a label whose payload is not valid Punycode, or a code point
     * that fails validation, is left unchanged instead of raising an error, so this never throws and
     * always returns a string. It is the inverse-for-display of [toAscii], not a strict round-trip.
     *
     * @param domain the domain to decode; typically an ASCII domain containing `xn--` labels.
     * @return the domain with every decodable `xn--` label rendered as Unicode; other text unchanged.
     */
    @JvmStatic
    public fun toUnicode(domain: String): String = Idna.domainToUnicode(domain)
}
