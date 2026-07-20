/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri

import org.dexpace.kuri.error.ParseResult
import org.dexpace.kuri.host.Host
import org.dexpace.kuri.idna.Idna
import org.dexpace.kuri.parser.IriMapping
import org.dexpace.kuri.percent.PercentCodec
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

/**
 * The RFC 3987 IRI conversion facility layered on the unchanged strict [Uri] engine.
 *
 * An IRI (Internationalized Resource Identifier) is *not* a [Uri]; RFC 3987 §3.1 instead defines a
 * one-way mapping from an IRI to a URI. This facility implements that mapping ([toUri]) and its
 * best-effort inverse display transform of §3.2 ([toUnicode]), so the strict [Uri] parser keeps its
 * "no RFC 3986 deviations" invariant (Appendix B): `Uri.parse` still *rejects* raw non-ASCII, and
 * only this facility accepts it.
 *
 * The mapping is a §3.1 transform layered on two RFC 3987 validation passes: the host is processed
 * by IDNA/UTS-46 (via the engine's ToASCII), and every other component has its non-ASCII code points
 * percent-encoded as UTF-8 octets — but only once that code point has cleared §2.2's `ucschar`/
 * `iprivate` repertoire (`iprivate` legal only in the query) and §4.1's ban on the seven bidi
 * formatting characters (LRM, RLM, LRE, RLE, PDF, LRO, RLO) anywhere in the IRI. §4.2's per-component
 * directionality restriction is intentionally not enforced: the RFC states it as a SHOULD, not a
 * MUST, and it is not relevant to every use of an IRI (e.g. one never presented visually).
 *
 * @see Uri
 */
public object Iri {
    /**
     * Maps [iri] to a [Uri] under the RFC 3987 §3.1 IRI-to-URI transform.
     *
     * The host is run through IDNA/UTS-46 ToASCII (a non-IDNA-valid *ASCII* reg-name such as
     * `foo_bar`, and any bracketed IP literal, is preserved verbatim); every other component has its
     * non-ASCII code points percent-encoded as UTF-8 octets. Existing `%XX` triplets are left intact
     * (never double-encoded). The fully-ASCII result is then validated and stored by the strict [Uri]
     * engine, so the returned [Uri] holds only RFC 3986-valid text.
     *
     * The transform can fail: an IDNA-invalid non-ASCII host is a [ParseResult.Err], as is a non-ASCII
     * code point outside its component's §2.2 repertoire, a §4.1 bidi formatting character anywhere
     * in [iri], or a mapping whose expanded ASCII form is rejected by the strict engine — note that
     * percent-encoding and IDNA ToASCII both grow the input, so a very long IRI that fits
     * [ParseOptions.inputLength] may still exceed [ParseOptions.expandedLength] after mapping and be
     * rejected with `UriParseError.InputTooLong`.
     *
     * @param iri the internationalized reference to convert; non-ASCII input is accepted.
     * @param options the parse configuration whose [ParseOptions.inputLength]/[ParseOptions.expandedLength]
     *   bound the raw IRI and its expanded ASCII form respectively; defaults to [ParseOptions.DEFAULT].
     * @return [ParseResult.Ok] with the mapped [Uri], or [ParseResult.Err] when the IRI cannot be
     *   mapped to a valid URI (e.g. an IDNA failure in the host, or a limit overflow).
     */
    @JvmStatic
    @JvmOverloads
    public fun toUri(
        iri: String,
        options: ParseOptions = ParseOptions.DEFAULT,
    ): ParseResult<Uri> = IriMapping.toUri(iri, options)

    /**
     * Renders [uri] back to a best-effort display IRI under the RFC 3987 §3.2 transform.
     *
     * The host of a [Host.RegName] is run through IDNA ToUnicode (so an `xn--` label becomes its
     * Unicode form); an IP-literal or opaque host is emitted verbatim. Every other component has its
     * percent-encoded non-ASCII runs decoded back to text, while ASCII triplets (`%2F`, `%20`) and
     * runs that are not valid UTF-8 are preserved literally, so structural delimiters survive.
     *
     * This transform is *display-only* and total (it never fails): the produced string is intended
     * for presentation and is **not** guaranteed to re-parse as the original [uri].
     *
     * @param uri the URI whose components to render for display.
     * @return the best-effort display IRI string.
     */
    @JvmStatic
    public fun toUnicode(uri: Uri): String {
        val out = StringBuilder()
        uri.scheme?.let { out.append(it).append(':') }
        appendAuthorityDisplay(out, uri)
        out.append(PercentCodec.decodeNonAscii(uri.encodedPath))
        uri.query?.let { out.append('?').append(PercentCodec.decodeNonAscii(it)) }
        uri.fragment?.let { out.append('#').append(PercentCodec.decodeNonAscii(it)) }
        return out.toString()
    }

    /** Appends `//[userinfo@]host[:port]` with the host shown as Unicode and the userinfo decoded. */
    private fun appendAuthorityDisplay(
        out: StringBuilder,
        uri: Uri,
    ) {
        val host = uri.host ?: return
        val hostName = requireNotNull(uri.hostName) { "a present host must serialize to a name" }
        out.append("//")
        uri.userInfo?.let { out.append(PercentCodec.decodeNonAscii(it)).append('@') }
        out.append(hostDisplay(host, hostName))
        uri.port?.let { out.append(':').append(it) }
    }

    /** The display form of [host]: IDNA ToUnicode for a reg-name, else the serialized [hostName]. */
    private fun hostDisplay(
        host: Host,
        hostName: String,
    ): String =
        when (host) {
            is Host.RegName -> Idna.domainToUnicode(host.value)
            is Host.Ipv4, is Host.Ipv6, is Host.IpFuture, is Host.Opaque, Host.Empty -> hostName
        }
}
