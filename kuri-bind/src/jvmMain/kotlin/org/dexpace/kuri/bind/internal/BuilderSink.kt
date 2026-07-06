/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.bind.internal

import org.dexpace.kuri.Uri
import org.dexpace.kuri.Url
import org.dexpace.kuri.bind.Profile
import org.dexpace.kuri.host.Host
import org.dexpace.kuri.percent.Percent

/**
 * The narrow set of builder operations the binder needs, abstracting the two profiles.
 *
 * All profile-specific differences — userinfo split vs. verbatim-join, fragment encoding,
 * and port range constraints — live in the concrete implementations rather than in the binder.
 * Callers pass decoded (raw) values; each implementation decides how to encode before forwarding
 * to the underlying builder.
 */
internal interface BuilderSink {
    /** The profile this sink targets. */
    val profile: Profile

    /** Sets the scheme component. [value] must not be empty. */
    fun scheme(value: String)

    /**
     * Sets the userinfo component from decoded [username] and optional decoded [password].
     *
     * Each implementation encodes the parts appropriately for its profile:
     * [UrlBuilderSink] calls split setters; [UriBuilderSink] encodes and joins around a literal `:`.
     */
    fun userInfo(
        username: String,
        password: String?,
    )

    /** Sets the host from a plain text (possibly IDNA) hostname string. */
    fun hostText(value: String)

    /** Sets the host from a pre-parsed structured [Host] value. */
    fun hostStructured(value: Host)

    /** Sets the port. Valid ranges differ by profile. */
    fun port(value: Int)

    /** Appends a single decoded path segment. The implementation percent-encodes it. */
    fun addPathSegment(decoded: String)

    /**
     * Appends all decoded path segments derived from splitting [decodedSlashPath] on `/`.
     *
     * Equivalent to calling [addPathSegment] for each slash-delimited token; the builder
     * normalises the result according to its profile.
     */
    fun addPathSegmentsRaw(decodedSlashPath: String)

    /** Appends a query parameter from decoded [name] and optional decoded [value]. */
    fun addQueryParameter(
        name: String,
        value: String?,
    )

    /** Sets the fragment from a decoded string. The implementation percent-encodes it. */
    fun fragmentDecoded(decoded: String)
}

/**
 * Projects decoded contributions onto a [Url.Builder]: split username/password setters, and a
 * percent-encoded fragment stored via the raw-fragment setter.
 *
 * Port range is validated against the WHATWG URL port ceiling of 65 535.
 */
internal class UrlBuilderSink(
    private val builder: Url.Builder,
) : BuilderSink {
    override val profile: Profile get() = Profile.URL

    override fun scheme(value: String) {
        require(value.isNotEmpty()) { "scheme must not be empty" }
        builder.scheme(value)
    }

    /**
     * Forwards decoded [username] and [password] directly to the URL builder, which encodes
     * each under the userinfo set internally. The `:` separator is managed by the builder.
     */
    override fun userInfo(
        username: String,
        password: String?,
    ) {
        require(username.isNotEmpty() || password != null) { "at least one of username or password must be present" }
        builder.username(username)
        if (password != null) builder.password(password)
    }

    override fun hostText(value: String) {
        builder.host(value)
    }

    override fun hostStructured(value: Host) {
        builder.host(value)
    }

    override fun port(value: Int) {
        require(value in 0..MAX_URL_PORT) { "url port out of range [$value]: must be 0–$MAX_URL_PORT" }
        builder.port(value)
    }

    override fun addPathSegment(decoded: String) {
        builder.addPathSegment(decoded)
    }

    override fun addPathSegmentsRaw(decodedSlashPath: String) {
        builder.addPathSegments(decodedSlashPath)
    }

    override fun addQueryParameter(
        name: String,
        value: String?,
    ) {
        builder.addQueryParameter(name, value)
    }

    /**
     * Percent-encodes [decoded] under the FRAGMENT encode set and forwards the raw encoded string
     * to the builder. The builder stores it without re-encoding.
     */
    override fun fragmentDecoded(decoded: String) {
        builder.fragment(Percent.encode(decoded, Percent.Component.FRAGMENT))
    }

    private companion object {
        const val MAX_URL_PORT = 65535
    }
}

/**
 * Projects decoded contributions onto a [Uri.Builder]: encodes username and password separately
 * under the USER_INFO set, then joins them around a literal `:` before passing the verbatim
 * userinfo string to the builder. Fragment is handled the same way as [UrlBuilderSink].
 *
 * [Uri.Builder.userInfo] stores the value verbatim (already-encoded), so encoding must happen
 * here before the call.
 */
internal class UriBuilderSink(
    private val builder: Uri.Builder,
) : BuilderSink {
    override val profile: Profile get() = Profile.URI

    override fun scheme(value: String) {
        require(value.isNotEmpty()) { "scheme must not be empty" }
        builder.scheme(value)
    }

    /**
     * Percent-encodes [username] and [password] under the USER_INFO set, then joins them with a
     * literal `:` separator. The joined string is stored verbatim by [Uri.Builder.userInfo].
     *
     * The `:` itself is NOT encoded because it acts as the structural delimiter between the two
     * userinfo sub-components, not as data.
     */
    override fun userInfo(
        username: String,
        password: String?,
    ) {
        require(username.isNotEmpty() || password != null) { "at least one of username or password must be present" }
        val encodedUser = Percent.encode(username, Percent.Component.USER_INFO)
        val joined =
            if (password == null) {
                encodedUser
            } else {
                "$encodedUser:${Percent.encode(password, Percent.Component.USER_INFO)}"
            }
        builder.userInfo(joined)
    }

    override fun hostText(value: String) {
        builder.host(value)
    }

    override fun hostStructured(value: Host) {
        builder.host(value)
    }

    override fun port(value: Int) {
        require(value >= 0) { "uri port must be non-negative: $value" }
        builder.port(value)
    }

    override fun addPathSegment(decoded: String) {
        builder.addPathSegment(decoded)
    }

    override fun addPathSegmentsRaw(decodedSlashPath: String) {
        builder.addPathSegments(decodedSlashPath)
    }

    override fun addQueryParameter(
        name: String,
        value: String?,
    ) {
        builder.addQueryParameter(name, value)
    }

    /**
     * Percent-encodes [decoded] under the FRAGMENT encode set and forwards the raw encoded string
     * to the builder.
     */
    override fun fragmentDecoded(decoded: String) {
        builder.fragment(Percent.encode(decoded, Percent.Component.FRAGMENT))
    }
}
