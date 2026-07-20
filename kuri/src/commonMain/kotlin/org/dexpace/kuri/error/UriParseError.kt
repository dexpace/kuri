/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.error

/**
 * The catalog of *fatal* parse failures (SPEC §12.2).
 *
 * A fatal error aborts production of a value: it is reported as the single
 * [ParseResult.Err] error and no [ParseResult.Ok] is produced. Each
 * variant carries enough context to locate and explain the failure — at minimum
 * an `at` offset into the original input (in UTF-16 code units, before any
 * stripping/trimming) or an explanatory sub-value.
 *
 * The parser produces a fixed slice of this catalog: structural failures
 * ([InvalidScheme], [MissingScheme], [InvalidPercentEncoding], [InvalidPort]),
 * host failures ([EmptyHost], [InvalidHost] carrying a [HostError], and
 * [ForbiddenHostCodePoint]), the size failures [InputTooLong] and [LimitExceeded] (SPEC §12.6's
 * `ResourceLimit` registry), and the RFC 3987 IRI-conversion failures ([IriInvalidCodePoint],
 * [IriBidiFormattingCharacter]). The hierarchy is `sealed` so a `when` over it is exhaustive
 * without an `else`; adding a variant is an intentional, API-visible change.
 */
public sealed interface UriParseError {
    /**
     * A human-readable, non-blank description of this failure, suitable for logging or for a thrown
     * exception message.
     *
     * The rendering is stable per variant and includes the case's structured data (offsets, host
     * text, bounds); the variant's own properties remain the source of truth for programmatic use.
     */
    public val message: String
        get() =
            when (this) {
                is InvalidScheme -> "invalid scheme at offset $at: $detail"
                is MissingScheme -> "missing required scheme"
                is InvalidPercentEncoding -> "invalid percent-encoding at offset $at"
                is InvalidPort -> "invalid port: \"$text\""
                is EmptyHost -> "empty host is not permitted for this scheme"
                is InvalidHost -> "invalid host \"$host\": $reason"
                is ForbiddenHostCodePoint -> "forbidden host code point $codePoint at offset $at"
                is InputTooLong -> "input length $length exceeds maximum $max"
                is LimitExceeded -> "resource limit $limit exceeded: observed $observed exceeds maximum $max"
                is IriInvalidCodePoint -> "code point $codePoint at offset $at is outside the iri repertoire"
                is IriBidiFormattingCharacter -> "forbidden bidi formatting character $codePoint at offset $at"
            }

    /**
     * A scheme component is present but ill-formed: the first
     * character is not ALPHA, or a later character is outside
     * `ALPHA / DIGIT / "+" / "-" / "."`.
     *
     * @property at the offset of the offending code unit in the original input.
     * @property detail identifies the offending condition for diagnostics.
     */
    public data class InvalidScheme(
        public val at: Int,
        public val detail: String,
    ) : UriParseError

    /**
     * No scheme was found where one is required: in the `Uri` profile
     * on input that is not a valid relative reference, or in the `Url` profile
     * when parsing with no scheme and no usable base.
     */
    public data object MissingScheme : UriParseError

    /**
     * A percent sequence is malformed (`%` not followed by two ASCII hex digits)
     * in a context where that is *fatal* — e.g. `Uri` strict mode. In
     * lenient parsing the same condition is non-fatal and surfaces as a
     * [ValidationError] instead.
     *
     * @property at the offset of the offending `%`.
     */
    public data class InvalidPercentEncoding(
        public val at: Int,
    ) : UriParseError

    /**
     * A port is present but is not a run of ASCII digits, or its numeric value
     * exceeds the permitted maximum.
     *
     * @property text the port substring exactly as seen in the input.
     */
    public data class InvalidPort(
        public val text: String,
    ) : UriParseError

    /**
     * A host is empty in a context that forbids an empty host: in the
     * `Url` profile, a special scheme other than `file`. The `Uri` profile
     * permits an empty authority and never produces this.
     */
    public data object EmptyHost : UriParseError

    /**
     * A host is present but the §7 host pipeline rejected it. Carries
     * the host substring exactly as seen (post-strip, pre-IDNA) and a [HostError]
     * discriminating the cause (e.g. IPv4 width overflow, an invalid numeric part,
     * a malformed IPv6 literal). Forbidden-code-point failures are reported by the
     * dedicated [ForbiddenHostCodePoint] variant instead, so callers can surface
     * the exact code point.
     *
     * @property host the offending host text as seen in the input.
     * @property reason the specific host-pipeline cause of the failure.
     */
    public data class InvalidHost(
        public val host: String,
        public val reason: HostError,
    ) : UriParseError

    /**
     * A forbidden host code point (or, for a domain host, a forbidden-domain
     * code point) was encountered in a host substring. Kept distinct
     * from a generic host failure so a caller can report the exact offending
     * code point and its location.
     *
     * @property codePoint the offending Unicode scalar value, exactly as it appears in the original
     *   input at [at] — for a domain host this is the pre-IDNA code point, which may differ from
     *   what a WHATWG ToASCII implementation would itself report after mapping (e.g. NBSP maps to
     *   SPACE): a caller highlighting [at] in the text they were given needs the code point actually
     *   present there.
     * @property at the offset of the offending code unit in the original input.
     */
    public data class ForbiddenHostCodePoint(
        public val codePoint: Int,
        public val at: Int,
    ) : UriParseError

    /**
     * The input length exceeds the configured maximum. Also produced when a lengthening operation —
     * IRI→URI percent-encoding, IDNA ToASCII/ToUnicode expansion, or RFC 3986 §5.2.3 path merging —
     * pushes the serialized length past the `ExpandedLength` bound, carrying the post-expansion
     * [length]. Percent-*decoding* only shrinks and never triggers this.
     *
     * @property length the observed length that triggered the failure.
     * @property max the configured maximum that was exceeded.
     */
    public data class InputTooLong(
        public val length: Int,
        public val max: Int,
    ) : UriParseError

    /**
     * A configured [ResourceLimit] other than [ResourceLimit.InputLength] or
     * [ResourceLimit.ExpandedLength] was exceeded (SPEC §12.6, [ERR-17]). Those two length limits
     * are reported through [InputTooLong] instead; every other entry in the [ResourceLimit]
     * registry — e.g. [ResourceLimit.PathSegments] — is reported here, carrying which limit was
     * hit, the observed count, and the configured maximum.
     *
     * @property limit the [ResourceLimit] that was exceeded.
     * @property observed the observed count that triggered the failure.
     * @property max the configured maximum that was exceeded.
     */
    public data class LimitExceeded(
        public val limit: ResourceLimit,
        public val observed: Int,
        public val max: Int,
    ) : UriParseError

    /**
     * A non-ASCII code point in an IRI component falls outside the RFC 3987 §2.2 repertoire that
     * component permits: outside `ucschar` for userinfo, path, and fragment; outside `ucschar` and
     * `iprivate` for the query (the only component `iprivate` is legal in). The host is excluded from
     * this check — it is independently validated by the IDNA/UTS-46 pipeline.
     *
     * @property codePoint the offending Unicode scalar value.
     * @property at the offset of the offending code unit in the original input.
     */
    public data class IriInvalidCodePoint(
        public val codePoint: Int,
        public val at: Int,
    ) : UriParseError

    /**
     * An IRI contains one of the seven bidirectional formatting characters (LRM, RLM, LRE, RLE, PDF,
     * LRO, RLO) that RFC 3987 §4.1 forbids anywhere in an IRI's logical (stored/transmitted) form —
     * they affect visual rendering only and are never themselves part of the IRI.
     *
     * @property codePoint the offending bidi formatting code point.
     * @property at the offset of the offending code unit in the original input.
     */
    public data class IriBidiFormattingCharacter(
        public val codePoint: Int,
        public val at: Int,
    ) : UriParseError
}
