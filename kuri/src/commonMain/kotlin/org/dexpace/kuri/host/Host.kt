/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.host

/**
 * The addressable-name portion of an authority, modelled as a sealed type so the
 * host *kind* is part of the type, exhaustively matchable, and able to drive
 * serialization — notably IPv6/IP-future bracketing (SPEC §3.5, §7.9, [MODEL-14],
 * [HOST-45]).
 *
 * This is the stored shape only. Host parsing, validation, IDNA/UTS-46 processing,
 * IPv4 interpretation, zone-id handling, and canonical serialization are normative
 * in §7 and live in the dedicated host parser/serializer modules; the variants here
 * carry no behaviour. Stored IPv6/IP-future values never include the surrounding
 * `[`/`]` brackets — bracketing is a serialization concern applied on output
 * ([MODEL-18], [HOST-46]).
 *
 * Because data classes cannot reject malformed arguments inline without leaking a
 * throwing constructor into the (future) public surface, the structural invariants
 * of each variant are documented here and MUST be enforced by the parser modules
 * that construct these values.
 *
 * Distinguish `host == null` (no authority component; there was no `//`) from
 * [Empty] (an authority is present but the host is the empty string, e.g.
 * `file:///path`). The two MUST NOT be conflated ([MODEL-15]).
 */
internal sealed interface Host {
    /**
     * A registered name or domain stored in already-canonical form ([MODEL-16]).
     *
     * For the `Url` profile this is an IDNA/UTS-46 ToASCII-processed, lowercased
     * ASCII domain; for the `Uri` profile it is an RFC 3986 reg-name preserved
     * under the profile's percent-encoding policy. It MUST NOT hold raw Unicode
     * awaiting later processing — canonicalization happens at production time.
     *
     * @property value the canonical registered-name text (no brackets).
     */
    data class RegName(
        val value: String,
    ) : Host

    /**
     * An IPv4 address held as a single 32-bit quantity in a Kotlin [Int],
     * interpreted as unsigned ([MODEL-17]).
     *
     * The original textual form (dotted, hex, octal, shorthand) is intentionally
     * not stored; the canonical dotted-decimal serialization is computed from
     * [value] by the IPv4 module.
     *
     * @property value the address as 32 unsigned bits packed into a signed [Int].
     */
    data class Ipv4(
        val value: Int,
    ) : Host

    /**
     * An IPv6 address held as its eight 16-bit groups ([MODEL-18], [MODEL-19]).
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
    data class Ipv6(
        val pieces: List<Int>,
        val zoneId: String? = null,
    ) : Host

    /**
     * An RFC 3986 `IPvFuture` literal, reachable only in the `Uri` profile
     * ([MODEL-20], [HOST-42]).
     *
     * @property value the `vN.…` bracket contents (version, `.`, and payload),
     *   stored verbatim without the surrounding `[`/`]`.
     */
    data class IpFuture(
        val value: String,
    ) : Host

    /**
     * A WHATWG opaque host (non-special `Url` scheme) or an RFC reg-name preserved
     * verbatim under the `Uri` PRESERVE policy ([MODEL-21]).
     *
     * Only forbidden-host code-point and C0 percent-encoding is applied; no domain
     * lowercasing or IDNA is performed.
     *
     * @property value the percent-encoded opaque host text (no brackets).
     */
    data class Opaque(
        val value: String,
    ) : Host

    /**
     * An authority component whose host is the empty string ([MODEL-15],
     * [HOST-46]). Distinct from `host == null`; serializes to the empty string.
     */
    data object Empty : Host
}
