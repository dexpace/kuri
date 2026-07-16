/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.bind.internal

import org.dexpace.kuri.Uri
import org.dexpace.kuri.Url
import org.dexpace.kuri.percent.Percent

/**
 * Escapes a literal `%` to `%25` so it survives every downstream encode set unambiguously (none of
 * which reserve `%` — see [Percent.Component]) as the literal character the caller supplied, rather
 * than being read back as (or colliding with) a percent-encoded escape.
 */
private fun escapeLiteralPercent(text: String): String = text.replace("%", "%25")

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
     * Both [UrlBuilderSink] and [UriBuilderSink] delegate to their underlying builder's split
     * `username`/`password` setters, which each encode their own part appropriately for the profile.
     */
    fun userInfo(
        username: String,
        password: String?,
    )

    /** Sets the host from a plain text (possibly IDNA) hostname string. */
    fun hostText(value: String)

    /**
     * Sets the port. The caller guarantees [value] is non-negative (validated in `convertPort`); each
     * profile's builder then applies its own upper bound — `Url` caps at 65535, `Uri` applies no cap.
     */
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
     *
     * A literal `%` in [decoded] is escaped to `%25` first: the FRAGMENT set doesn't reserve `%`
     * itself, so an un-escaped `%` would pass through unambiguously as data on the way in but read
     * back as (or collide with) a percent-encoded escape on the way out.
     */
    fun fragmentDecoded(decoded: String) {
        setEncodedFragment(Percent.encode(escapeLiteralPercent(decoded), Percent.Component.FRAGMENT))
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
     * Fully replaces the userinfo slot from decoded [username] and [password] (an empty or absent
     * password clears the slot), so an object's userinfo never leaks a base builder's existing
     * password. [Url.Builder.username]/[Url.Builder.password] each percent-encode under the userinfo
     * set and escape a literal `%` themselves, so no manual escape is needed here.
     */
    override fun userInfo(
        username: String,
        password: String?,
    ) {
        require(username.isNotEmpty() || password != null) { "at least one of username or password must be present" }
        builder.username(username)
        // Always set the password (empty clears it) so the object's userinfo FULLY replaces a base
        // builder's slot — otherwise a username-only object would leak a base URL's existing password.
        builder.password(password ?: "")
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

    /**
     * A literal `%` in [name] or [value] is escaped to `%25` first: the query encode sets don't
     * reserve `%` itself, so an un-escaped `%` would read back as (or collide with) a
     * percent-encoded escape.
     */
    override fun addQueryParameter(
        name: String,
        value: String?,
    ) {
        builder.addQueryParameter(escapeLiteralPercent(name), value?.let { escapeLiteralPercent(it) })
    }

    override fun setEncodedFragment(encoded: String) {
        builder.fragment(encoded)
    }
}

/**
 * Projects decoded contributions onto a [Uri.Builder]: split username/password setters, and a
 * percent-encoded fragment stored via the raw-fragment setter. Fragment is handled the same way
 * as [UrlBuilderSink].
 */
internal class UriBuilderSink(
    private val builder: Uri.Builder,
) : BuilderSink {
    override fun scheme(value: String) {
        builder.scheme(value)
    }

    /**
     * Fully replaces the userinfo slot from decoded [username] and [password] (an absent password
     * clears the slot), so an object's userinfo never leaks a base builder's existing password.
     * [Uri.Builder.username]/[Uri.Builder.password] each percent-encode under the USER_INFO set and
     * escape a literal `%` themselves, join with `:`, and switch the builder to split userinfo mode
     * (discarding any verbatim [Uri.Builder.userInfo] value the base builder carried), so no manual
     * encode/join is needed here.
     */
    override fun userInfo(
        username: String,
        password: String?,
    ) {
        require(username.isNotEmpty() || password != null) { "at least one of username or password must be present" }
        builder.username(username)
        // Always set the password (empty clears it) so the object's userinfo FULLY replaces a base
        // builder's slot — otherwise a username-only object would leak a base URI's existing password.
        builder.password(password ?: "")
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

    /**
     * A literal `%` in [name] or [value] is escaped to `%25` first: the query encode sets don't
     * reserve `%` itself, so an un-escaped `%` would read back as (or collide with) a
     * percent-encoded escape.
     */
    override fun addQueryParameter(
        name: String,
        value: String?,
    ) {
        builder.addQueryParameter(escapeLiteralPercent(name), value?.let { escapeLiteralPercent(it) })
    }

    override fun setEncodedFragment(encoded: String) {
        builder.fragment(encoded)
    }
}
