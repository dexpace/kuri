/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.bind.internal

import org.dexpace.kuri.Uri
import org.dexpace.kuri.Url
import org.dexpace.kuri.percent.Percent

/**
 * The narrow set of builder operations the binder needs, abstracting the two profiles.
 *
 * All profile-specific differences — userinfo split vs. verbatim-join, and fragment encoding — live
 * in the concrete implementations rather than in the binder. Callers pass decoded (raw) values; each
 * implementation decides how to encode before forwarding to the underlying builder.
 */
internal interface BuilderSink {
    /** Sets the scheme component. The caller guarantees [value] is non-empty (validated upstream). */
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

    /** Sets the port. The caller guarantees [value] is within 0–65535 (validated in `convertPort`). */
    fun port(value: Int)

    /** Appends a single decoded path segment. The implementation percent-encodes it. */
    fun addPathSegment(decoded: String)

    /** Appends a query parameter from decoded [name] and optional decoded [value]. */
    fun addQueryParameter(
        name: String,
        value: String?,
    )

    /**
     * Percent-encodes [decoded] under the FRAGMENT set, then stores it. Both profiles' fragment
     * setters take an already-encoded string, so the encode is shared here and each sink only supplies
     * the raw store via [setEncodedFragment].
     */
    fun fragmentDecoded(decoded: String) {
        setEncodedFragment(Percent.encode(decoded, Percent.Component.FRAGMENT))
    }

    /** Stores an already-encoded fragment verbatim on the underlying builder. */
    fun setEncodedFragment(encoded: String)
}

/**
 * Projects decoded contributions onto a [Url.Builder]: split username/password setters, and a
 * percent-encoded fragment stored via the raw-fragment setter.
 */
internal class UrlBuilderSink(
    private val builder: Url.Builder,
) : BuilderSink {
    override fun scheme(value: String) {
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

    override fun port(value: Int) {
        builder.port(value)
    }

    override fun addPathSegment(decoded: String) {
        builder.addPathSegment(decoded)
    }

    override fun addQueryParameter(
        name: String,
        value: String?,
    ) {
        builder.addQueryParameter(name, value)
    }

    override fun setEncodedFragment(encoded: String) {
        builder.fragment(encoded)
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
    override fun scheme(value: String) {
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

    override fun port(value: Int) {
        builder.port(value)
    }

    override fun addPathSegment(decoded: String) {
        builder.addPathSegment(decoded)
    }

    override fun addQueryParameter(
        name: String,
        value: String?,
    ) {
        builder.addQueryParameter(name, value)
    }

    override fun setEncodedFragment(encoded: String) {
        builder.fragment(encoded)
    }
}
