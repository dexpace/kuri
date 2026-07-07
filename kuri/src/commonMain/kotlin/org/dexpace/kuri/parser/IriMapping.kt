/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.parser

import org.dexpace.kuri.Uri
import org.dexpace.kuri.error.ParseResult
import org.dexpace.kuri.error.map
import org.dexpace.kuri.idna.Idna
import org.dexpace.kuri.percent.PercentCodec
import org.dexpace.kuri.percent.PercentEncodeSets
import org.dexpace.kuri.scheme.Scheme
import org.dexpace.kuri.scheme.schemeColonIndex
import org.dexpace.kuri.text.isAllAscii

/** The authority-introducing `//` and the number of code units it spans (RFC 3986 §3.2). */
private const val DOUBLE_SLASH: String = "//"

/** Length of [DOUBLE_SLASH]; the authority text begins immediately after it. */
private const val DOUBLE_SLASH_LENGTH: Int = 2

/**
 * The RFC 3987 §3.1 IRI-to-URI mapping, layered on the unchanged strict [UriParser] engine.
 *
 * The mapping is deliberately *coarse then strict*: this object locates components at their ASCII
 * structural delimiters only (`:`, `//`, `/`, `?`, `#`, `@`, `[`…`]`), applies the §3.1 transform to
 * each (host via IDNA/UTS-46 ToASCII, every other component via UTF-8 percent-encoding of its
 * non-ASCII code points), reassembles a fully-ASCII URI string, and hands that to [UriParser] which
 * re-validates and stores it. Because a non-ASCII code point can never masquerade as one of those
 * ASCII delimiters, and the final parse is authoritative, the coarse locator does not need to be a
 * full grammar.
 *
 * Percent-encoding grows the input, so the engine's `MAX_INPUT_LENGTH` bound applies to the
 * *expanded* ASCII string; a very long all-non-ASCII IRI may exceed it after encoding and be rejected.
 */
@Suppress("TooManyFunctions") // One cohesive coarse splitter decomposed into single-purpose helpers,
// each well under the 60-line cap; mirrors UriParser's structural decomposition.
internal object IriMapping {
    /**
     * Maps [iri] to a [Uri] under RFC 3987 §3.1, or returns the first fatal failure.
     *
     * @param iri the internationalized reference to convert; non-ASCII input is accepted.
     * @return [ParseResult.Ok] with the mapped [Uri], or [ParseResult.Err] on an IDNA host failure
     *   or a strict-engine rejection of the expanded ASCII form.
     */
    internal fun toUri(iri: String): ParseResult<Uri> {
        val parts = decompose(iri)
        check(parts.host == null || parts.hasAuthority) { "a host requires an authority" }
        val mappedHost =
            when (parts.host) {
                null -> null
                else ->
                    when (val host = mapHost(parts.host)) {
                        is ParseResult.Err -> return host
                        is ParseResult.Ok -> host.value
                    }
            }
        return UriParser.parse(reassemble(parts, mappedHost)).map { Uri(it) }
    }

    /**
     * Maps a raw host: a bracketed literal or an already-ASCII reg-name passes through verbatim (so a
     * valid-but-non-IDNA ASCII reg-name such as `foo_bar` survives); a non-ASCII host runs the pure
     * UTS-46 [Idna.domainToAscii], whose failure becomes the conversion failure.
     */
    private fun mapHost(host: String): ParseResult<String> {
        val verbatim = host.startsWith('[') || host.isAllAscii()
        return when {
            verbatim -> ParseResult.Ok(host)
            else -> Idna.domainToAscii(host)
        }
    }

    /** Reassembles a fully-ASCII URI string from the located [parts] and the already-mapped [mappedHost]. */
    private fun reassemble(
        parts: IriParts,
        mappedHost: String?,
    ): String {
        val out = StringBuilder()
        parts.scheme?.let { out.append(it).append(':') }
        if (parts.hasAuthority) appendAuthority(out, parts, mappedHost)
        out.append(encodeComponent(parts.path))
        parts.query?.let { out.append('?').append(encodeComponent(it)) }
        parts.fragment?.let { out.append('#').append(encodeComponent(it)) }
        return out.toString()
    }

    /** Appends `//[userinfo@]host[:port]`; userinfo is UTF-8 %-encoded, host is the mapped ASCII form. */
    private fun appendAuthority(
        out: StringBuilder,
        parts: IriParts,
        mappedHost: String?,
    ) {
        require(parts.hasAuthority) { "authority reassembly requires an authority" }
        val host = requireNotNull(mappedHost) { "a present authority requires a mapped host" }
        out.append(DOUBLE_SLASH)
        parts.userInfo?.let { out.append(encodeComponent(it)).append('@') }
        out.append(host)
        parts.port?.let { out.append(':').append(it) }
    }

    /**
     * The §3.1 component transform: UTF-8 percent-encode only the non-ASCII code points (plus C0/DEL),
     * leaving every printable-ASCII delimiter byte-identical and every existing `%XX` triplet intact.
     */
    private fun encodeComponent(component: String): String =
        PercentCodec.encode(component, PercentEncodeSets.C0_CONTROL)

    /** Splits [iri] at its ASCII structural delimiters into the components the transform maps. */
    private fun decompose(iri: String): IriParts {
        val hash = iri.indexOf('#')
        val fragment = if (hash < 0) null else iri.substring(hash + 1)
        val body = if (hash < 0) iri else iri.substring(0, hash)
        val mark = body.indexOf('?')
        val query = if (mark < 0) null else body.substring(mark + 1)
        val hier = if (mark < 0) body else body.substring(0, mark)
        val scheme = detectScheme(hier)
        check(scheme == null || hier.length > scheme.length) { "a scheme requires a trailing colon" }
        val rest = if (scheme == null) hier else hier.substring(scheme.length + 1)
        return splitAuthorityPath(scheme, rest, query, fragment)
    }

    /** A scheme is the prefix before a `:` that precedes any `/`, and only when it is a valid scheme. */
    private fun detectScheme(hier: String): String? {
        val colon = schemeColonIndex(hier)
        if (colon < 0) return null
        val candidate = hier.substring(0, colon)
        return if (Scheme.isValidScheme(candidate)) candidate else null
    }

    /** Routes [rest] to an authority split when it opens with `//`, else treats all of it as the path. */
    private fun splitAuthorityPath(
        scheme: String?,
        rest: String,
        query: String?,
        fragment: String?,
    ): IriParts {
        if (!rest.startsWith(DOUBLE_SLASH)) {
            return IriParts(scheme, hasAuthority = false, path = rest, query = query, fragment = fragment)
        }
        val slash = rest.indexOf('/', DOUBLE_SLASH_LENGTH)
        val end = if (slash < 0) rest.length else slash
        val authority = splitAuthority(rest.substring(DOUBLE_SLASH_LENGTH, end))
        val path = if (slash < 0) "" else rest.substring(slash)
        return IriParts(
            scheme = scheme,
            hasAuthority = true,
            userInfo = authority.userInfo,
            host = authority.host,
            port = authority.port,
            path = path,
            query = query,
            fragment = fragment,
        )
    }

    /** Splits authority text into userinfo (before the LAST `@`) and host[:port]. */
    private fun splitAuthority(authority: String): Authority {
        val at = authority.lastIndexOf('@')
        val userInfo = if (at < 0) null else authority.substring(0, at)
        val hostPort = if (at < 0) authority else authority.substring(at + 1)
        return if (hostPort.startsWith('[')) splitBracketed(userInfo, hostPort) else splitPlain(userInfo, hostPort)
    }

    /** A non-bracketed host ends at the first `:`, whose tail is the port. */
    private fun splitPlain(
        userInfo: String?,
        hostPort: String,
    ): Authority {
        val colon = hostPort.indexOf(':')
        return when {
            colon < 0 -> Authority(userInfo, hostPort, port = null)
            else -> Authority(userInfo, hostPort.substring(0, colon), hostPort.substring(colon + 1))
        }
    }

    /** A bracketed literal's port colon, if any, immediately follows the closing `]`. */
    private fun splitBracketed(
        userInfo: String?,
        hostPort: String,
    ): Authority {
        val close = hostPort.indexOf(']')
        if (close < 0) return Authority(userInfo, hostPort, port = null)
        val host = hostPort.substring(0, close + 1)
        val rest = hostPort.substring(close + 1)
        val port = if (rest.startsWith(':')) rest.substring(1) else null
        return Authority(userInfo, host, port)
    }

    /** The located structural components of an IRI, each carried raw (pre-transform). */
    private data class IriParts(
        val scheme: String?,
        val hasAuthority: Boolean,
        val userInfo: String? = null,
        val host: String? = null,
        val port: String? = null,
        val path: String,
        val query: String?,
        val fragment: String?,
    )

    /** The located authority sub-components: raw userinfo (or `null`), raw host, and raw port (or `null`). */
    private data class Authority(
        val userInfo: String?,
        val host: String,
        val port: String?,
    )
}
