/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.error

/**
 * The catalog of *fatal* parse failures (SPEC §12.2).
 *
 * A fatal error aborts production of a value: it is reported as the single
 * [ParseResult.Err] error and no [ParseResult.Ok] is produced ([ERR-2]). Each
 * variant carries enough context to locate and explain the failure — at minimum
 * an `at` offset into the original input (in UTF-16 code units, before any
 * stripping/trimming, [ERR-8]) or an explanatory sub-value.
 *
 * This is a deliberately faithful *starter subset* of the §12.2 catalog: it
 * holds only the variants that do not yet depend on parser-internal types (host
 * pipeline, resource-limit registry). The remaining variants
 * (`InvalidAuthority`, `InvalidHost`, `ForbiddenHostCodePoint`, `LimitExceeded`)
 * land with the parser. The hierarchy is `sealed` so a `when` over it is
 * exhaustive without an `else` ([ERR-18]); adding a variant is an intentional,
 * API-visible change.
 */
public sealed interface UriParseError {
    /**
     * A scheme component is present but ill-formed ([ERR-9]): the first
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
     * No scheme was found where one is required ([ERR-9]): in the `Uri` profile
     * on input that is not a valid relative reference, or in the `Url` profile
     * when parsing with no scheme and no usable base.
     */
    public data object MissingScheme : UriParseError

    /**
     * A percent sequence is malformed (`%` not followed by two ASCII hex digits)
     * in a context where that is *fatal* ([ERR-15]) — e.g. `Uri` strict mode. In
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
     * exceeds the permitted maximum ([ERR-14]).
     *
     * @property text the port substring exactly as seen in the input.
     */
    public data class InvalidPort(
        public val text: String,
    ) : UriParseError

    /**
     * A host is empty in a context that forbids an empty host ([ERR-13]): in the
     * `Url` profile, a special scheme other than `file`. The `Uri` profile
     * permits an empty authority and never produces this.
     */
    public data object EmptyHost : UriParseError

    /**
     * A host is present but the §7 host pipeline rejected it ([ERR-11]). Carries
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
     * code point) was encountered in a host substring ([ERR-12]). Kept distinct
     * from a generic host failure so a caller can report the exact offending
     * code point and its location.
     *
     * @property codePoint the offending Unicode scalar value.
     * @property at the offset of the offending code unit in the original input.
     */
    public data class ForbiddenHostCodePoint(
        public val codePoint: Int,
        public val at: Int,
    ) : UriParseError

    /**
     * The input length exceeds the configured maximum ([ERR-16]). Also produced
     * when percent-decoding/IDNA expansion pushes the serialized length past the
     * same bound, carrying the post-expansion [length].
     *
     * @property length the observed length that triggered the failure.
     * @property max the configured maximum that was exceeded.
     */
    public data class InputTooLong(
        public val length: Int,
        public val max: Int,
    ) : UriParseError
}
