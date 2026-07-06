/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.host

/** RFC 6874 zone-id introducer prefixed to an IPv6 `zoneId` when one is present (`%25<id>`). */
private const val ZONE_PREFIX: String = "%25"

/**
 * Serializes a [Host] to its authority text, applying the §11.2 / RFC 3986 §3.2.2 bracketing rules
 * (WHATWG URL serializer "host serializer").
 *
 * The stored host never carries brackets; they are a serialization concern reapplied
 * here. The mapping is total over the sealed [Host] surface:
 *
 * - [Host.RegName] / [Host.Opaque] — the stored value verbatim (already canonical per §7).
 * - [Host.Ipv4] — the canonical dotted-decimal form from [Ipv4.serialize].
 * - [Host.Ipv6] — the RFC 5952 form from [Ipv6.serialize], wrapped in `[`…`]`, with an optional
 *   RFC 6874 `%25<zoneId>` suffix emitted only when a zone id is set.
 * - [Host.IpFuture] — the stored `vN.…` payload wrapped in `[`…`]`.
 * - [Host.Empty] — the empty string (an authority with an empty host, e.g. `file:///x`).
 *
 * @param host the parsed host to render; carries no surrounding brackets.
 * @return the host's authority text, bracketed for IPv6 / IP-future literals.
 */
internal fun serializeHost(host: Host): String =
    when (host) {
        is Host.RegName -> host.value
        is Host.Opaque -> host.value
        is Host.Ipv4 -> Ipv4.serialize(host.value)
        is Host.Ipv6 -> "[${Ipv6.serialize(host.pieces)}${zoneSuffix(host.zoneId)}]"
        is Host.IpFuture -> "[${host.value}]"
        Host.Empty -> ""
    }

/**
 * Renders this [Host] to its canonical authority text, bracketing IPv6 / IP-future literals.
 *
 * An internal extension alias onto [serializeHost], kept as an ergonomic receiver form for the
 * `Uri`/`Url` serializers; the public renderer is the [Host.asText] member. It applies the §11.2 /
 * RFC 3986 §3.2.2 bracketing rules, so the result carries brackets for [Host.Ipv6] / [Host.IpFuture]
 * and the value verbatim otherwise.
 *
 * @return the host's canonical authority text.
 * @see Host.asText
 */
internal fun Host.serialize(): String = serializeHost(this)

/** Renders an optional IPv6 zone id as the RFC 6874 `%25<id>` suffix, or `""` when absent. */
private fun zoneSuffix(zoneId: String?): String = if (zoneId == null) "" else "$ZONE_PREFIX$zoneId"
