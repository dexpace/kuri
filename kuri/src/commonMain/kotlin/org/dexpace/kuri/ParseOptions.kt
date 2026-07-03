/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri

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
 * Every option defaults to **off**, so [DEFAULT] reproduces the library's standards-baseline
 * behaviour and enabling an option never changes the result of an input that does not exercise it.
 * A `ParseOptions` is a value type — equality and hashing are structural over its
 * single field — so it is a safe `Map`/`Set` key and can be shared freely across threads.
 *
 * Construct one with [Builder] (`new ParseOptions.Builder().allowIpv6ZoneId(true).build()` from
 * Java) or take the off value from [DEFAULT]; [newBuilder] produces a pre-filled builder for a
 * modified copy.
 *
 * The chosen options are not stored on the produced [Uri]. Any feature they unlock (an RFC 6874
 * zone id, for instance) is preserved verbatim in the value's canonical serialization, so a value
 * produced under one set of options must be re-parsed under the **same** options to round trip
 * through `toString` then `parse`.
 */
public class ParseOptions private constructor(
    allowIpv6ZoneId: Boolean,
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
     * Returns a [Builder] pre-filled with these options, for producing a modified copy.
     *
     * `options.newBuilder().build()` reproduces an equal `ParseOptions`.
     *
     * @return a builder seeded with every option of this value.
     */
    public fun newBuilder(): Builder = Builder(this)

    /** Structural equality over the single [allowIpv6ZoneId] field; consistent with [hashCode]. */
    override fun equals(other: Any?): Boolean = other is ParseOptions && other.allowIpv6ZoneId == allowIpv6ZoneId

    /** The hash of the single [allowIpv6ZoneId] field, consistent with [equals]. */
    override fun hashCode(): Int = allowIpv6ZoneId.hashCode()

    /** A debug rendering listing the single [allowIpv6ZoneId] field. */
    override fun toString(): String = "ParseOptions(allowIpv6ZoneId=$allowIpv6ZoneId)"

    /** The shared off value and the entry point for the value type. */
    public companion object {
        /** The default configuration with every opt-in off; the standards-baseline behaviour. */
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

        /** Creates a builder with every option at its default (off) value. */
        public constructor()

        /** Creates a builder pre-filled from [source] so `source.newBuilder().build()` reproduces it. */
        internal constructor(source: ParseOptions) {
            allowIpv6ZoneId = source.allowIpv6ZoneId
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
         * Snapshots the accumulated options into an immutable [ParseOptions].
         *
         * @return the [ParseOptions] for the assembled state.
         */
        public fun build(): ParseOptions = ParseOptions(allowIpv6ZoneId)
    }
}
