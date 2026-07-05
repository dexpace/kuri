/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.scheme

import kotlin.jvm.JvmStatic

/**
 * Facts about a URI/URL scheme: validity, special-ness, and default port (SPEC §6).
 *
 * The public face of the internal [Scheme] engine, exposing the profile-independent scheme lookups
 * so callers can query a scheme without parsing a whole reference: whether it is syntactically valid
 * ([isValid]), whether it is one of the six WHATWG *special* schemes ([isSpecial]), and its default
 * port ([defaultPort]). The special-scheme registry — `ftp`, `file`, `http`, `https`, `ws`, `wss` —
 * is fixed and closed (SPEC §6.1, Table 6-1); no other scheme is special.
 *
 * Every function is case-insensitive (the scheme is lower-cased before lookup) and *total* — none
 * ever throws, for any input, including the empty string.
 *
 * @see org.dexpace.kuri.Url
 * @see org.dexpace.kuri.Uri
 */
public object Schemes {
    /**
     * Returns the default port of [scheme], or `null` when it has none (SPEC §6.4; [SCH-18]).
     *
     * `null` covers both a non-special scheme (which never has a default port) and `file` (which is
     * special yet portless); use [isSpecial] to tell those two `null` cases apart when it matters. The
     * scheme is normalized (lower-cased) before the Table 6-1 lookup, so casing is irrelevant.
     *
     * @param scheme the scheme text, in any case, without a trailing `:`.
     * @return the default port, or `null` for `file` and every non-special scheme.
     */
    @JvmStatic
    public fun defaultPort(scheme: String): Int? = Scheme.defaultPort(scheme)

    /**
     * Reports whether [scheme] is one of the six WHATWG special schemes (SPEC §6.1.1; [SCH-3]).
     *
     * The special schemes are `ftp`, `file`, `http`, `https`, `ws`, and `wss`; the match is
     * case-insensitive but otherwise exact, so a near-miss such as `http2` is not special. Special-ness
     * governs WHATWG behaviours like default-port elision and backslash handling.
     *
     * @param scheme the scheme text, in any case, without a trailing `:`.
     * @return `true` iff the normalized scheme is in the closed special-scheme registry.
     */
    @JvmStatic
    public fun isSpecial(scheme: String): Boolean = Scheme.isSpecial(scheme)

    /**
     * Reports whether [scheme] is a syntactically valid scheme (SPEC §6.2; [SCH-7], [SCH-8]).
     *
     * A valid scheme is non-empty, begins with an ASCII letter, and continues with ASCII letters,
     * digits, `+`, `-`, or `.`. Validity is independent of case and of whether the scheme is special,
     * so `1x` and the empty string are invalid while `http` and `a+b.c-1` are valid.
     *
     * @param scheme the candidate scheme text, without a trailing `:`.
     * @return `true` iff [scheme] satisfies the RFC 3986 §3.1 scheme production.
     */
    @JvmStatic
    public fun isValid(scheme: String): Boolean = Scheme.isValidScheme(scheme)
}
