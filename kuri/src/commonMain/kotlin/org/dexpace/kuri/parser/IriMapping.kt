/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.parser

import org.dexpace.kuri.Uri
import org.dexpace.kuri.error.ParseResult
import org.dexpace.kuri.error.UriParseError
import org.dexpace.kuri.error.map
import org.dexpace.kuri.idna.Idna
import org.dexpace.kuri.percent.PercentCodec
import org.dexpace.kuri.percent.PercentEncodeSets
import org.dexpace.kuri.scheme.Scheme
import org.dexpace.kuri.scheme.schemeColonIndex
import org.dexpace.kuri.text.NON_ASCII_MIN
import org.dexpace.kuri.text.charCount
import org.dexpace.kuri.text.codePointAt
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
 * Percent-encoding grows the input, so the parser's input-length bound (`ResourceLimit.InputLength`,
 * applied by [UriParser] to the re-assembled ASCII string) governs the *expanded* text; a very long
 * all-non-ASCII IRI may exceed it after encoding and be rejected.
 *
 * Before any mapping happens, [precheckError] runs two RFC 3987 checks over the raw text:
 * [findBidiFormattingCharacter] rejects a bidi formatting character (LRM, RLM, LRE, RLE, PDF, LRO,
 * RLO) anywhere in [toUri]'s input (§4.1's MUST-NOT), and [repertoireError] rejects a non-ASCII code
 * point outside `ucschar`/`iprivate` in userinfo, path, query, or fragment (§2.2's grammar) —
 * `iprivate` is accepted only in the query, per the ABNF. The host is exempt from the repertoire
 * check: it is independently validated by IDNA/UTS-46.
 */
@Suppress("TooManyFunctions") // One cohesive coarse splitter decomposed into single-purpose helpers,
// each well under the 60-line cap; mirrors UriParser's structural decomposition.
internal object IriMapping {
    /**
     * Maps [iri] to a [Uri] under RFC 3987 §3.1, or returns the first fatal failure.
     *
     * @param iri the internationalized reference to convert; non-ASCII input is accepted.
     * @return [ParseResult.Ok] with the mapped [Uri], or [ParseResult.Err] on a §4.1 bidi-formatting
     *   violation, a §2.2 repertoire violation, an IDNA host failure, or a strict-engine rejection of
     *   the expanded ASCII form.
     */
    internal fun toUri(iri: String): ParseResult<Uri> {
        val parts = decompose(iri)
        check(parts.host == null || parts.hasAuthority) { "a host requires an authority" }
        val hostResult = resolveHost(precheckError(iri, parts), parts)
        return when (hostResult) {
            is ParseResult.Err -> hostResult
            is ParseResult.Ok -> UriParser.parse(reassemble(parts, hostResult.value)).map { Uri(it) }
        }
    }

    /**
     * The first §4.1 bidi-formatting or §2.2 repertoire violation in [iri], or `null` when both checks
     * pass. Runs before any mapping so a rejected IRI never reaches IDNA or percent-encoding.
     */
    private fun precheckError(
        iri: String,
        parts: IriParts,
    ): UriParseError? = findBidiFormattingCharacter(iri) ?: repertoireError(parts)

    /**
     * Resolves [parts]'s host to its mapped ASCII form, short-circuiting to [precheck] first: a
     * precheck failure (bidi or repertoire) must win over a host failure so the earliest structural
     * problem in [iri]'s own text is what gets reported, not one downstream of it. `null` means "no
     * host" (a hostless reference), distinct from a mapping failure.
     */
    private fun resolveHost(
        precheck: UriParseError?,
        parts: IriParts,
    ): ParseResult<String?> =
        when {
            precheck != null -> ParseResult.Err(precheck)
            parts.host == null -> ParseResult.Ok(null)
            else -> mapHost(parts.host)
        }

    /**
     * The first of RFC 3987 §4.1's seven forbidden bidi formatting characters in [iri], or `null`.
     * The rule is IRI-wide, not scoped to a single component, so this scans the raw, undecomposed text.
     */
    private fun findBidiFormattingCharacter(iri: String): UriParseError? {
        for (index in iri.indices) {
            val codePoint = iri[index].code
            if (IriRepertoire.isBidiFormattingCharacter(codePoint)) {
                return UriParseError.IriBidiFormattingCharacter(codePoint, index)
            }
        }
        return null
    }

    /**
     * The first raw component (userinfo, path, query, then fragment) that carries a non-ASCII code
     * point outside its RFC 3987 §2.2 repertoire, or `null`. The host is exempt: it is validated
     * separately by the IDNA/UTS-46 pipeline in [mapHost].
     */
    private fun repertoireError(parts: IriParts): UriParseError? =
        parts.userInfo?.let { validateRepertoire(it, allowIprivate = false) }
            ?: validateRepertoire(parts.path, allowIprivate = false)
            ?: parts.query?.let { validateRepertoire(it, allowIprivate = true) }
            ?: parts.fragment?.let { validateRepertoire(it, allowIprivate = false) }

    /**
     * Scans [located]'s text code point by code point, failing on the first non-ASCII value outside
     * `ucschar` (and, when [allowIprivate], also outside `iprivate`) — see RFC 3987 §2.2. [Located.offset]
     * is the component's start in the original IRI, so the reported failure locates the exact input index.
     */
    private fun validateRepertoire(
        located: Located,
        allowIprivate: Boolean,
    ): UriParseError? {
        var index = 0
        while (index < located.text.length) {
            val codePoint = codePointAt(located.text, index)
            val legal =
                codePoint < NON_ASCII_MIN ||
                    IriRepertoire.isUcschar(codePoint) ||
                    (allowIprivate && IriRepertoire.isIprivate(codePoint))
            if (!legal) {
                return UriParseError.IriInvalidCodePoint(codePoint, located.offset + index)
            }
            index += charCount(codePoint)
        }
        return null
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
        out.append(encodeComponent(parts.path.text))
        parts.query?.let { out.append('?').append(encodeComponent(it.text)) }
        parts.fragment?.let { out.append('#').append(encodeComponent(it.text)) }
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
        parts.userInfo?.let { out.append(encodeComponent(it.text)).append('@') }
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
        val fragment = if (hash < 0) null else Located(iri.substring(hash + 1), hash + 1)
        val body = if (hash < 0) iri else iri.substring(0, hash)
        val mark = body.indexOf('?')
        val query = if (mark < 0) null else Located(body.substring(mark + 1), mark + 1)
        val hier = if (mark < 0) body else body.substring(0, mark)
        val scheme = detectScheme(hier)
        check(scheme == null || hier.length > scheme.length) { "a scheme requires a trailing colon" }
        val restOffset = if (scheme == null) 0 else scheme.length + 1
        val rest = if (scheme == null) hier else hier.substring(scheme.length + 1)
        return splitAuthorityPath(scheme, Located(rest, restOffset), query, fragment)
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
        rest: Located,
        query: Located?,
        fragment: Located?,
    ): IriParts {
        if (!rest.text.startsWith(DOUBLE_SLASH)) {
            return IriParts(scheme, hasAuthority = false, path = rest, query = query, fragment = fragment)
        }
        val slash = rest.text.indexOf('/', DOUBLE_SLASH_LENGTH)
        val end = if (slash < 0) rest.text.length else slash
        val authorityText = rest.text.substring(DOUBLE_SLASH_LENGTH, end)
        val authority = splitAuthority(Located(authorityText, rest.offset + DOUBLE_SLASH_LENGTH))
        val path =
            if (slash < 0) {
                Located("", rest.offset + rest.text.length)
            } else {
                Located(rest.text.substring(slash), rest.offset + slash)
            }
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
    private fun splitAuthority(authority: Located): Authority {
        val at = authority.text.lastIndexOf('@')
        val userInfo = if (at < 0) null else Located(authority.text.substring(0, at), authority.offset)
        val hostPort = if (at < 0) authority.text else authority.text.substring(at + 1)
        return if (hostPort.startsWith('[')) splitBracketed(userInfo, hostPort) else splitPlain(userInfo, hostPort)
    }

    /** A non-bracketed host ends at the first `:`, whose tail is the port. */
    private fun splitPlain(
        userInfo: Located?,
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
        userInfo: Located?,
        hostPort: String,
    ): Authority {
        val close = hostPort.indexOf(']')
        if (close < 0) return Authority(userInfo, hostPort, port = null)
        val host = hostPort.substring(0, close + 1)
        val rest = hostPort.substring(close + 1)
        val port = if (rest.startsWith(':')) rest.substring(1) else null
        return Authority(userInfo, host, port)
    }

    /**
     * A raw substring of the original IRI together with its start offset, so a later validation pass
     * (the RFC 3987 §2.2 repertoire check) can report the exact input index of a rejected code point.
     */
    private data class Located(
        val text: String,
        val offset: Int,
    )

    /** The located structural components of an IRI, each carried raw (pre-transform). */
    private data class IriParts(
        val scheme: String?,
        val hasAuthority: Boolean,
        val userInfo: Located? = null,
        val host: String? = null,
        val port: String? = null,
        val path: Located,
        val query: Located? = null,
        val fragment: Located? = null,
    )

    /** The located authority sub-components: raw userinfo (or `null`), raw host, and raw port (or `null`). */
    private data class Authority(
        val userInfo: Located?,
        val host: String,
        val port: String?,
    )
}
