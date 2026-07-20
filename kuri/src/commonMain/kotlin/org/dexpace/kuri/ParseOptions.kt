/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri

import org.dexpace.kuri.error.ResourceLimit
import org.dexpace.kuri.host.Host
import kotlin.jvm.JvmField
import kotlin.jvm.JvmName

/** A `ParseOptions` with the RFC 6874 zone-id opt-in enabled; the options a zoned value round-trips under. */
internal val ZONE_ID_ENABLED: ParseOptions = ParseOptions.Builder().allowIpv6ZoneId(true).build()

/** True when [this] host carries an RFC 6874 zone id — the sole feature whose re-parse needs an opt-in. */
internal fun Host?.carriesZoneId(): Boolean = this is Host.Ipv6 && zoneId != null

/**
 * The [ParseOptions] a value whose authority is [host] needs to round-trip through serialize-then-parse.
 *
 * A value's opt-in features are fully reflected in its stored components (today only a zone id on an
 * IPv6 host), so the round-trip options are derived from [host] rather than stored on the value.
 * Every future opt-in feature MUST likewise be reflected in the stored components for
 * this derivation to remain complete.
 */
internal fun roundTripOptions(host: Host?): ParseOptions =
    if (host.carriesZoneId()) ZONE_ID_ENABLED else ParseOptions.DEFAULT

/**
 * Immutable, opt-in parsing configuration accepted by the [Uri] parse and resolve factories
 * (SPEC §7.2.2).
 *
 * These options are honored by the **`Uri` profile only** (`Uri.parse(input, options)`). The [Url]
 * profile is the WHATWG profile and does **not** accept `ParseOptions`: the WHATWG URL parser has no
 * zone-id production and no configuration, so a `Url` unconditionally rejects an IPv6 zone identifier
 * regardless of any option. This keeps the `Url` profile unconditionally
 * WHATWG-conformant.
 *
 * Every opt-in flag defaults to **off**, and every resource limit defaults to the bound documented
 * on [ResourceLimit] (SPEC §12.6), so [DEFAULT] reproduces the library's standards-baseline
 * behaviour: enabling an option, or lowering a limit, never changes the result of an input that
 * does not exercise it. A `ParseOptions` is a value type — equality and hashing are structural over
 * its fields — so it is a safe `Map`/`Set` key and can be shared freely across threads.
 *
 * Construct one with [Builder] (`new ParseOptions.Builder().allowIpv6ZoneId(true).build()` from
 * Java) or take the off value from [DEFAULT]; [newBuilder] produces a pre-filled builder for a
 * modified copy.
 *
 * Every field is independent: setting one on the [Builder] never shifts another. In particular
 * [expandedLength] does not track [inputLength] — each defaults to its own [ResourceLimit] bound and
 * is carried and compared on its own.
 *
 * The chosen options are stored on the produced [Uri] so it round-trips through
 * `newBuilder`/`resolve`/`relativize` under the limits it was parsed with, but they are **not** part
 * of the value's identity: two `Uri` values compare equal on their serialization alone, regardless
 * of the options that produced them. Any feature the options unlock (an RFC 6874 zone id, for
 * instance) is also preserved verbatim in the value's canonical serialization.
 */
public class ParseOptions private constructor(
    allowIpv6ZoneId: Boolean,
    inputLength: Int,
    expandedLength: Int,
    pathSegments: Int,
    resolutionDepth: Int,
) {
    /**
     * Whether an RFC 6874 IPv6 zone identifier (`[` IPv6address `%25` ZoneID `]`) is accepted;
     * honored by the **`Uri` profile only**.
     *
     * `false` by default: with the opt-in off, an IPv6 literal carrying a `%` is rejected.
     * When `true`, the `%25`-introduced zone id is parsed by `Uri.parse(input, options)`, stored raw
     * on the host, and re-serialized after `%25`. The `Url` profile never accepts a zone id: it takes
     * no `ParseOptions` and unconditionally rejects a `%` in an IPv6 literal, because the WHATWG URL
     * parser has no zone-id production.
     */
    @get:JvmName("allowIpv6ZoneId")
    public val allowIpv6ZoneId: Boolean = allowIpv6ZoneId

    /**
     * The maximum accepted length, in UTF-16 code units, of the raw input string
     * ([ResourceLimit.InputLength], SPEC §12.6, [ERR-30]).
     *
     * Defaults to [ResourceLimit.InputLength]'s documented bound. Exceeding it is reported as
     * `UriParseError.InputTooLong`.
     */
    public val inputLength: Int = inputLength

    /**
     * The maximum accepted length, in UTF-16 code units, of text derived from the input by an
     * operation that can lengthen it — IDNA ToASCII/ToUnicode expansion, IRI→URI percent-encoding,
     * or §5.2.3 path merging during reference resolution ([ResourceLimit.ExpandedLength], SPEC §12.6,
     * [ERR-31]).
     *
     * An independent field defaulting to [ResourceLimit.ExpandedLength]'s documented bound; it does
     * **not** track [inputLength]. Exceeding it is reported as `UriParseError.InputTooLong`.
     */
    public val expandedLength: Int = expandedLength

    /**
     * The maximum number of `/`-delimited segments a parsed path may carry
     * ([ResourceLimit.PathSegments], SPEC §12.6, [ERR-33]).
     *
     * Defaults to [ResourceLimit.PathSegments]'s documented bound. Exceeding it is reported as
     * `UriParseError.LimitExceeded`.
     */
    public val pathSegments: Int = pathSegments

    /**
     * The bound on reference-resolution / dot-segment work ([ResourceLimit.ResolutionDepth], SPEC
     * §12.6, [ERR-33]).
     *
     * Defaults to [ResourceLimit.ResolutionDepth]'s documented bound. Every resolve bounds the
     * §5.2.4 dot-segment collapse by this many iterations; a reference exceeding it is reported as
     * `UriParseError.LimitExceeded` carrying [ResourceLimit.ResolutionDepth].
     */
    public val resolutionDepth: Int = resolutionDepth

    /**
     * Returns a [Builder] pre-filled with these options, for producing a modified copy.
     *
     * `options.newBuilder().build()` reproduces an equal `ParseOptions`.
     *
     * @return a builder seeded with every option of this value.
     */
    public fun newBuilder(): Builder = Builder(this)

    /** Structural equality over every field, consistent with [hashCode]. */
    override fun equals(other: Any?): Boolean =
        other is ParseOptions &&
            other.allowIpv6ZoneId == allowIpv6ZoneId &&
            other.inputLength == inputLength &&
            other.expandedLength == expandedLength &&
            other.pathSegments == pathSegments &&
            other.resolutionDepth == resolutionDepth

    /** The combined hash of every field, consistent with [equals]. */
    override fun hashCode(): Int {
        var result = allowIpv6ZoneId.hashCode()
        result = HASH_MULTIPLIER * result + inputLength
        result = HASH_MULTIPLIER * result + expandedLength
        result = HASH_MULTIPLIER * result + pathSegments
        result = HASH_MULTIPLIER * result + resolutionDepth
        return result
    }

    /** A debug rendering listing every field. */
    override fun toString(): String =
        "ParseOptions(allowIpv6ZoneId=$allowIpv6ZoneId, inputLength=$inputLength, " +
            "expandedLength=$expandedLength, pathSegments=$pathSegments, resolutionDepth=$resolutionDepth)"

    /** The shared off value and the entry point for the value type. */
    public companion object {
        /** The base multiplier folded per field into [hashCode]; standard odd-prime choice. */
        private const val HASH_MULTIPLIER: Int = 31

        /** The default configuration with every opt-in off and every limit at its [ResourceLimit] default. */
        @JvmField
        public val DEFAULT: ParseOptions = Builder().build()
    }

    /**
     * A mutable, Java-constructible (`new ParseOptions.Builder()`) assembler for a [ParseOptions].
     *
     * Setters are fluent and accumulate option state; [build] snapshots it into an immutable
     * [ParseOptions]. Use [ParseOptions.newBuilder] for a pre-filled builder.
     */
    public class Builder {
        private var allowIpv6ZoneId: Boolean = false
        private var inputLength: Int = ResourceLimit.InputLength.defaultMax
        private var expandedLength: Int = ResourceLimit.ExpandedLength.defaultMax
        private var pathSegments: Int = ResourceLimit.PathSegments.defaultMax
        private var resolutionDepth: Int = ResourceLimit.ResolutionDepth.defaultMax

        /** Creates a builder with every option at its default value ([ResourceLimit] for the limits). */
        public constructor()

        /** Creates a builder pre-filled from [source] so `source.newBuilder().build()` reproduces it. */
        internal constructor(source: ParseOptions) {
            allowIpv6ZoneId = source.allowIpv6ZoneId
            inputLength = source.inputLength
            expandedLength = source.expandedLength
            pathSegments = source.pathSegments
            resolutionDepth = source.resolutionDepth
        }

        /**
         * Enables or disables RFC 6874 IPv6 zone-id parsing; off by default.
         *
         * @param allow `true` to accept a `%25`-introduced zone id, `false` to reject it.
         * @return this builder, for chaining.
         */
        public fun allowIpv6ZoneId(allow: Boolean): Builder {
            allowIpv6ZoneId = allow
            return this
        }

        /**
         * Overrides [ResourceLimit.InputLength] for this parse; defaults to its documented bound
         * (64 KiB) when not called.
         *
         * @param max the maximum accepted raw-input length, in UTF-16 code units.
         * @return this builder, for chaining.
         * @throws IllegalArgumentException if [max] is not positive.
         */
        public fun inputLength(max: Int): Builder {
            require(max > 0) { "inputLength must be positive: $max" }
            inputLength = max
            return this
        }

        /**
         * Overrides [ResourceLimit.ExpandedLength] for this parse; defaults to its documented bound
         * (64 KiB) when not called, independently of [inputLength].
         *
         * @param max the maximum accepted post-expansion length, in UTF-16 code units.
         * @return this builder, for chaining.
         * @throws IllegalArgumentException if [max] is not positive.
         */
        public fun expandedLength(max: Int): Builder {
            require(max > 0) { "expandedLength must be positive: $max" }
            expandedLength = max
            return this
        }

        /**
         * Overrides [ResourceLimit.PathSegments] for this parse; defaults to its documented bound
         * (10,000) when not called.
         *
         * @param max the maximum accepted number of `/`-delimited path segments.
         * @return this builder, for chaining.
         * @throws IllegalArgumentException if [max] is not positive.
         */
        public fun pathSegments(max: Int): Builder {
            require(max > 0) { "pathSegments must be positive: $max" }
            pathSegments = max
            return this
        }

        /**
         * Overrides [ResourceLimit.ResolutionDepth] for this parse; defaults to its documented
         * bound (256) when not called. Every resolve bounds its §5.2.4 dot-segment collapse by this
         * many iterations (see [ResourceLimit.ResolutionDepth]).
         *
         * @param max the maximum bound on reference-resolution / dot-segment work.
         * @return this builder, for chaining.
         * @throws IllegalArgumentException if [max] is not positive.
         */
        public fun resolutionDepth(max: Int): Builder {
            require(max > 0) { "resolutionDepth must be positive: $max" }
            resolutionDepth = max
            return this
        }

        /**
         * Snapshots the accumulated options into an immutable [ParseOptions].
         *
         * @return the [ParseOptions] for the assembled state.
         */
        public fun build(): ParseOptions =
            ParseOptions(allowIpv6ZoneId, inputLength, expandedLength, pathSegments, resolutionDepth)
    }
}
