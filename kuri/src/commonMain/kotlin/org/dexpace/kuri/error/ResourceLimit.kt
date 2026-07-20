/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.error

/** [ResourceLimit.InputLength]/[ResourceLimit.ExpandedLength] default: 64 KiB of UTF-16 code units. */
private const val DEFAULT_INPUT_LENGTH: Int = 65_536

/** [ResourceLimit.PathSegments] default: a generous cap no well-formed path approaches. */
private const val DEFAULT_PATH_SEGMENTS: Int = 10_000

/** [ResourceLimit.HostLabelLength]: the RFC 5890 domain-label maximum; never configurable. */
private const val DNS_LABEL_LENGTH: Int = 63

/** [ResourceLimit.HostTotalLength]: the DNS total-host-length maximum. */
private const val DNS_HOST_LENGTH: Int = 253

/** [ResourceLimit.PortMax]: the largest value a 16-bit TCP/UDP port can hold. */
private const val MAX_PORT_VALUE: Int = 65_535

/** [ResourceLimit.ResolutionDepth] default: bound on reference-resolution / dot-segment work. */
private const val DEFAULT_RESOLUTION_DEPTH: Int = 256

/**
 * The canonical registry of bounded resource dimensions the parser enforces (SPEC §12.6).
 *
 * Every unbounded dimension of a URI-reference input is capped, so that well-formed-but-hostile
 * input can never trigger an unbounded allocation, a `StackOverflowError`, or a hang ([ERR-29]).
 * Each variant's [defaultMax] is the bound a parse applies when it does not override that limit
 * ([ERR-36]).
 *
 * Exceeding [InputLength] or [ExpandedLength] is reported as [UriParseError.InputTooLong];
 * exceeding any other variant is reported as [UriParseError.LimitExceeded] carrying that variant
 * ([ERR-29]).
 *
 * [InputLength], [ExpandedLength], [PathSegments], and [ResolutionDepth] are overridable per parse
 * through `ParseOptions.Builder` in the `Uri` profile (lowering one never changes the outcome for
 * input already within the lower bound, per [ERR-36]). The `Url` (WHATWG) profile takes no
 * `ParseOptions`, so it enforces [InputLength] and [PathSegments] at these fixed default bounds
 * instead; its [ExpandedLength]/[ResolutionDepth] are not separately configurable dimensions
 * (`Iri.toUri` reference-resolution dot-segment work belong to the `Uri` profile).
 *
 * [HostLabelLength], [HostTotalLength], and [PortMax] are fixed protocol maxima defined by DNS
 * (RFC 5890) and the 16-bit port range: they are never raised or lowered by configuration ([ERR-34],
 * [ERR-35]). Of the three, only [PortMax] is enforced today — by the `Url` profile via
 * [UriParseError.InvalidPort]; the `Uri` profile deliberately applies no port-value ceiling
 * (RFC 3986 Appendix B's unbounded `*DIGIT` port grammar), so [ERR-35]'s ceiling is `Url`-scoped.
 * [HostLabelLength] and [HostTotalLength] are registered so the public surface matches section
 * 12.6's declared shape, but neither profile constructs the corresponding `HostError` variant yet.
 * They appear here so the registry documents the complete set section 12.6 declares.
 *
 * @property defaultMax the documented default bound for this limit, applied when a parse does not
 *   override it.
 */
public enum class ResourceLimit(
    public val defaultMax: Int,
) {
    /**
     * The maximum length, in UTF-16 code units, of the original input string, checked before
     * substantive parsing begins ([ERR-30]). Default 64 KiB (65,536); overridable per parse.
     */
    InputLength(DEFAULT_INPUT_LENGTH),

    /**
     * The maximum serialized length, in UTF-16 code units, re-checked after an operation that can
     * lengthen the text: IDNA ToASCII/ToUnicode expansion, IRI→URI percent-encoding (`Iri.toUri`),
     * or RFC 3986 §5.2.3 path merging during reference resolution ([ERR-31]). Percent-*decoding* only
     * shrinks and never trips this. An independent default equal to [InputLength]'s (64 KiB) but not
     * tied to it; overridable per parse in the `Uri` profile.
     */
    ExpandedLength(DEFAULT_INPUT_LENGTH),

    /**
     * The maximum number of `/`-delimited segments a parsed path may carry ([ERR-33]). Default
     * 10,000; overridable per parse.
     */
    PathSegments(DEFAULT_PATH_SEGMENTS),

    /**
     * The maximum length of a single domain label: 63, fixed by RFC 5890. Not configurable in
     * either direction ([ERR-34]). Registered so the public surface matches section 12.6's
     * declared shape; not yet enforced by either profile — `HostError.LabelTooLong` exists but
     * nothing constructs it today, since the `Uri` profile has no `strict` DNS-length escalation
     * yet (SPEC [HOST-31], deferred).
     */
    HostLabelLength(DNS_LABEL_LENGTH),

    /**
     * The maximum total host length: 253, the DNS limit. Configuration MUST NOT raise it above
     * this protocol maximum ([ERR-34]). Registered so the public surface matches section 12.6's
     * declared shape; not yet enforced by either profile — `HostError.HostTooLong` exists but
     * nothing constructs it today, since the `Uri` profile has no `strict` DNS-length escalation
     * yet (SPEC [HOST-31], deferred).
     */
    HostTotalLength(DNS_HOST_LENGTH),

    /**
     * The maximum numeric port value: 65,535, the largest 16-bit port. Not configurable, and scoped
     * to the `Url` (WHATWG) profile, which rejects a port above it via [UriParseError.InvalidPort]
     * ([ERR-35]). The `Uri` profile deliberately applies **no** port-value ceiling beyond `Int`
     * overflow, per RFC 3986 Appendix B's unbounded `*DIGIT` port grammar — so this bound is not a
     * both-profile maximum.
     */
    PortMax(MAX_PORT_VALUE),

    /**
     * The bound on reference-resolution / dot-segment work ([ERR-33]). Default 256; overridable
     * per parse via `ParseOptions.Builder.resolutionDepth`.
     *
     * Enforced by every resolve path: the §5.2.4 dot-segment collapse counts its loop iterations
     * (one per consumed prefix or moved segment), and a reference whose collapse exceeds this bound
     * is rejected with [UriParseError.LimitExceeded] carrying this variant. The default is generous
     * enough that no well-formed reference approaches it; lowering it caps how much dot-segment
     * rewriting a single resolve will perform.
     */
    ResolutionDepth(DEFAULT_RESOLUTION_DEPTH),
}
