/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.host

/** The fixed octet count of an IPv4 address: four 8-bit groups. */
private const val IPV4_OCTET_COUNT: Int = 4

/** Bit width of one octet; the shift between adjacent octets in the packed value. */
private const val IPV4_BITS_PER_OCTET: Int = 8

/** Index of the most-significant octet, i.e. `IPV4_OCTET_COUNT - 1`. */
private const val IPV4_HIGH_OCTET_INDEX: Int = 3

/** Largest value a single octet may hold, also the low-byte mask (`0xFF`). */
private const val IPV4_OCTET_MASK: Int = 0xFF

/**
 * The addressable-name portion of an authority, modelled as a sealed type so the
 * host *kind* is part of the type, exhaustively matchable, and able to drive
 * serialization — notably IPv6/IP-future bracketing (SPEC §3.5, §7.9).
 *
 * This is the stored shape only. Host parsing, validation, IDNA/UTS-46 processing,
 * IPv4 interpretation, zone-id handling, and canonical serialization are normative
 * in §7 and live in the dedicated host parser/serializer modules; the variants here
 * carry no behaviour. Stored IPv6/IP-future values never include the surrounding
 * `[`/`]` brackets — bracketing is a serialization concern applied on output.
 *
 * Because data classes cannot reject malformed arguments inline without leaking a
 * throwing constructor into the (future) public surface, the structural invariants
 * of each variant are documented here and MUST be enforced by the parser modules
 * that construct these values.
 *
 * Distinguish `host == null` (no authority component; there was no `//`) from
 * [Empty] (an authority is present but the host is the empty string, e.g.
 * `file:///path`). The two MUST NOT be conflated.
 */
public sealed interface Host {
    /**
     * Renders this host to its canonical authority text, bracketing IPv6 / IP-future
     * literals.
     *
     * The result reapplies the RFC 3986 §3.2.2 / WHATWG §11.2 bracketing rules that the
     * stored variants deliberately omit: `[`…`]` for [Ipv6] and [IpFuture], and the value
     * verbatim otherwise. Equivalent to the [Host.serialize] extension, but exposed as a
     * member so it is discoverable directly on a [Host] and ergonomic from Java
     * (`host.asText()`).
     *
     * @return the host's canonical authority text.
     */
    public fun asText(): String = serializeHost(this)

    /**
     * A registered name or domain stored in already-canonical form.
     *
     * For the `Url` profile this is an IDNA/UTS-46 ToASCII-processed, lowercased
     * ASCII domain; for the `Uri` profile it is an RFC 3986 reg-name preserved
     * under the profile's percent-encoding policy. It MUST NOT hold raw Unicode
     * awaiting later processing — canonicalization happens at production time.
     *
     * @property value the canonical registered-name text (no brackets).
     */
    public data class RegName(
        public val value: String,
    ) : Host

    /**
     * An IPv4 address held as a single 32-bit quantity in a Kotlin [Int],
     * interpreted as unsigned.
     *
     * The original textual form (dotted, hex, octal, shorthand) is intentionally
     * not stored; the canonical dotted-decimal serialization is computed from
     * [value] by the IPv4 module.
     *
     * @property value the address as 32 unsigned bits packed into a signed [Int].
     */
    public data class Ipv4(
        public val value: Int,
    ) : Host {
        /**
         * Unpacks the stored 32-bit [value] into its four octets, most-significant first.
         *
         * The structured counterpart to [asText]: where `asText()` yields the canonical
         * dotted-decimal string, this returns the raw bytes for callers that need the
         * numeric form. Each octet is in `0..255` and the result always has length four;
         * the stored [value] is unchanged.
         *
         * @return a fresh four-element array of octets, high-order octet first, each `0..255`.
         */
        public fun octets(): IntArray {
            val result =
                IntArray(IPV4_OCTET_COUNT) { index ->
                    (value ushr (IPV4_BITS_PER_OCTET * (IPV4_HIGH_OCTET_INDEX - index))) and IPV4_OCTET_MASK
                }
            check(result.size == IPV4_OCTET_COUNT) { "expected $IPV4_OCTET_COUNT octets, got ${result.size}" }
            check(result.all { it in 0..IPV4_OCTET_MASK }) { "octet out of range in ${result.toList()}" }
            return result
        }
    }

    /**
     * An IPv6 address held as its eight 16-bit groups.
     *
     * Invariants (enforced by the IPv6 parser, not constructible inline here):
     * [pieces] MUST contain exactly eight elements, each in `0..65535`. Any
     * embedded-IPv4 tail (`::ffff:1.2.3.4`) MUST already be folded into the eight
     * groups. The stored value excludes the surrounding `[`/`]` brackets. A
     * [List] is used (rather than `IntArray`) so structural equality holds.
     *
     * @property pieces the eight 16-bit groups, high-order group first.
     * @property zoneId an optional RFC 6874 zone identifier (the text after `%25`,
     *   without that prefix), or `null` for none — rejected by default in both
     *   profiles and only populated when zone ids are opted in.
     */
    public data class Ipv6(
        public val pieces: List<Int>,
        public val zoneId: String? = null,
    ) : Host

    /**
     * An RFC 3986 `IPvFuture` literal, reachable only in the `Uri` profile.
     *
     * @property value the `vN.…` bracket contents (version, `.`, and payload),
     *   stored verbatim without the surrounding `[`/`]`.
     */
    public data class IpFuture(
        public val value: String,
    ) : Host

    /**
     * A WHATWG opaque host (non-special `Url` scheme) or an RFC reg-name preserved
     * verbatim under the `Uri` PRESERVE policy.
     *
     * Only forbidden-host code-point and C0 percent-encoding is applied; no domain
     * lowercasing or IDNA is performed.
     *
     * @property value the percent-encoded opaque host text (no brackets).
     */
    public data class Opaque(
        public val value: String,
    ) : Host

    /**
     * An authority component whose host is the empty string. Distinct from
     * `host == null`; serializes to the empty string.
     */
    public data object Empty : Host
}
