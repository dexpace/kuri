/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.serialize

import org.dexpace.kuri.host.Host
import org.dexpace.kuri.parser.ComponentPath
import org.dexpace.kuri.parser.ParsedComponents
import org.dexpace.kuri.parser.Resolver
import org.dexpace.kuri.parser.splitUriPath
import org.dexpace.kuri.parser.toUriPathString
import org.dexpace.kuri.scheme.Scheme
import org.dexpace.kuri.text.asciiLowercased
import org.dexpace.kuri.text.hasPercentHexPairAt
import org.dexpace.kuri.text.isUnreserved
import org.dexpace.kuri.text.percentByteAt

/** Length of a percent-encoded triplet `%XY`; hoisted so the triplet scanners carry no bare `3`. */
private const val TRIPLET_LENGTH: Int = 3

/**
 * The RFC 3986 §6.2 syntax-based and scheme-based normalizations for the `Uri` profile (SPEC §11.1
 * [NORM-3]..[NORM-10]; RFC 3986 §6.2.2, §6.2.3).
 *
 * Each rule is an independent, composable transform; [normalize] applies the full opt-in set in the
 * canonical [NORM-3] order — scheme-case → host-case → percent-triplet-case → unreserved-decoding →
 * dot-segment-removal → default-port-elision — and returns a new [ParsedComponents] (the input is
 * unchanged). The transforms are pure and order-independent in their observable result, so the
 * result is idempotent ([NORM-26]). This object is decomposed into single-purpose per-rule helpers,
 * legitimately exceeding the per-object function count.
 */
@Suppress("TooManyFunctions")
internal object UriNormalizer {
    /**
     * Applies the full RFC 3986 §6.2 normalization set to [c] (SPEC [NORM-3]).
     *
     * Lowercases the scheme ([NORM-4], §6.2.2.1) and reg-name host letters ([NORM-5]), uppercases
     * every percent triplet ([NORM-6], §6.2.2.1), decodes percent-encoded `unreserved` octets
     * ([NORM-8], §6.2.2.2), removes dot-segments from the path ([NORM-9], §6.2.2.3), elides a
     * default port ([NORM-10], §6.2.3), and renders an empty authority path as `/`.
     *
     * @param c the preserved components to normalize.
     * @return a new fully normalized [ParsedComponents]; [c] is left untouched.
     */
    internal fun normalize(c: ParsedComponents): ParsedComponents {
        val scheme = c.scheme?.let { Scheme.normalize(it) }
        val host = c.host?.let { normalizeHost(it) }
        check(scheme == null || scheme.none { it in 'A'..'Z' }) { "scheme normalization left upper-case" }
        return c.copy(
            scheme = scheme,
            username = normalizeText(c.username),
            password = normalizeText(c.password),
            host = host,
            port = normalizePort(scheme, c.port),
            path = normalizePath(c.path, hasAuthority = c.host != null),
            query = c.query?.let { normalizeText(it) },
            fragment = c.fragment?.let { normalizeText(it) },
        )
    }

    // --- §6.2.2.1 host case ------------------------------------------------------------------------

    /** §6.2.2.1 / [NORM-5]: lowercases reg-name letters; other host kinds are already canonical. */
    private fun normalizeHost(host: Host): Host =
        when (host) {
            is Host.RegName -> Host.RegName(normalizeRegName(host.value))
            is Host.Ipv4 -> host
            is Host.Ipv6 -> host
            is Host.IpFuture -> host
            is Host.Opaque -> host
            Host.Empty -> host
        }

    /** Lowercases reg-name letters ([NORM-5]) then applies the shared triplet rules ([NORM-7]). */
    private fun normalizeRegName(value: String): String = lowercaseRegNameLetters(normalizeText(value))

    /** Lowercases ASCII letters outside percent triplets; a triplet's hex digits are left to [NORM-6]. */
    private fun lowercaseRegNameLetters(text: String): String {
        if (text.none { it in 'A'..'Z' }) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            i = appendLowercasedHostChar(out, text, i)
        }
        return out.toString()
    }

    /** Copies a triplet verbatim (case handled later) or lowercases a single ASCII host letter. */
    private fun appendLowercasedHostChar(
        out: StringBuilder,
        text: String,
        i: Int,
    ): Int {
        if (text[i] == '%' && hasPercentHexPairAt(text, i)) {
            out.append(text.substring(i, i + TRIPLET_LENGTH))
            return i + TRIPLET_LENGTH
        }
        out.append(text[i].asciiLowercased())
        return i + 1
    }

    // --- §6.2.2.1 triplet case + §6.2.2.2 unreserved decoding --------------------------------------

    /** Shared component pipeline ([NORM-3] order): uppercase triplets, then decode `unreserved`. */
    private fun normalizeText(text: String): String = decodeUnreserved(uppercaseTriplets(text))

    /** §6.2.2.1 / [NORM-6]: uppercases the two hex digits of every percent triplet. */
    private fun uppercaseTriplets(text: String): String {
        if (!text.contains('%')) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            i = appendUppercasedTriplet(out, text, i)
        }
        return out.toString()
    }

    /** Emits an uppercased triplet at [i], or the single non-triplet code unit, returning the next index. */
    private fun appendUppercasedTriplet(
        out: StringBuilder,
        text: String,
        i: Int,
    ): Int {
        if (text[i] != '%' || !hasPercentHexPairAt(text, i)) {
            out.append(text[i])
            return i + 1
        }
        out.append('%').append(text[i + 1].uppercaseChar()).append(text[i + 2].uppercaseChar())
        return i + TRIPLET_LENGTH
    }

    /** §6.2.2.2 / [NORM-8]: replaces a triplet whose octet is `unreserved` with that literal octet. */
    private fun decodeUnreserved(text: String): String {
        if (!text.contains('%')) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            i = appendDecodedUnreserved(out, text, i)
        }
        return out.toString()
    }

    /** Decodes an `unreserved` triplet at [i], else keeps the triplet (or code unit) verbatim. */
    private fun appendDecodedUnreserved(
        out: StringBuilder,
        text: String,
        i: Int,
    ): Int {
        if (text[i] != '%' || !hasPercentHexPairAt(text, i)) {
            out.append(text[i])
            return i + 1
        }
        val octet = percentByteAt(text, i)
        val decoded = octet.toChar()
        if (decoded.isUnreserved()) out.append(decoded) else out.append(text.substring(i, i + TRIPLET_LENGTH))
        return i + TRIPLET_LENGTH
    }

    // --- §6.2.2.3 dot-segment removal --------------------------------------------------------------

    /** §6.2.2.3 / [NORM-9]: normalizes a hierarchical path; an opaque path is never dot-collapsed. */
    private fun normalizePath(
        path: ComponentPath,
        hasAuthority: Boolean,
    ): ComponentPath =
        when (path) {
            is ComponentPath.Opaque -> path
            is ComponentPath.Segments -> normalizeSegments(path, hasAuthority)
        }

    /** Normalizes the path string (triplets, then [Resolver.removeDotSegments]) and re-splits it. */
    private fun normalizeSegments(
        path: ComponentPath.Segments,
        hasAuthority: Boolean,
    ): ComponentPath {
        val cleaned = Resolver.removeDotSegments(normalizeText(path.toUriPathString()))
        val rendered = if (cleaned.isEmpty() && hasAuthority) SLASH else cleaned
        check(!(hasAuthority && rendered.isEmpty())) { "an authority path must render as at least /" }
        return splitUriPath(rendered)
    }

    // --- §6.2.3 default-port elision ---------------------------------------------------------------

    /** §6.2.3 / [NORM-10]: drops a port equal to the (already-lowercased) scheme's default port. */
    private fun normalizePort(
        scheme: String?,
        port: Int?,
    ): Int? =
        when {
            scheme == null || port == null -> port
            port == Scheme.defaultPort(scheme) -> null
            else -> port
        }
}
