/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.error

/**
 * The discriminated cause of a host-pipeline failure (SPEC §12.2).
 *
 * Carried by [UriParseError.InvalidHost] so callers can distinguish *why* a host
 * was rejected without parsing free-form text. The §7 host failures map onto these
 * variants exactly; distinct causes MUST NOT be collapsed into one opaque value.
 * The set is `sealed`/closed so a `when` over it is exhaustive without
 * an `else` and adding a cause is an intentional, reviewable change.
 */
public enum class HostError {
    /** UTS-46 ToASCII/ToUnicode produced an error (§7.4 IDNA pipeline). */
    IdnaFailed,

    /** A numeric host or octet exceeds its width-bounded maximum (§7.3). */
    Ipv4Overflow,

    /** An ends-in-a-number host carried an invalid numeric part (`Url` profile, §7.3.1). */
    Ipv4NonNumeric,

    /** Bad group count, misplaced `::`, bad embedded IPv4, or stray characters (§7.2). */
    Ipv6Malformed,

    /** An RFC 6874 `%25` zone is present but zone-id support was not opted in (§7.2). */
    ZoneIdRejected,

    /** A domain label exceeds the DNS label limit under strict validation (§7.4). */
    LabelTooLong,

    /** The total host length exceeds the DNS limit under strict validation (§7.4). */
    HostTooLong,

    /** An empty label appears where not permitted, e.g. `a..b` (§7.4/§7.5). */
    EmptyLabel,
}
