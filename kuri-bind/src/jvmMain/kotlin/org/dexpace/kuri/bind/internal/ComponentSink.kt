/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.bind.internal

import org.dexpace.kuri.bind.KuriBindException
import org.dexpace.kuri.host.Host

/** A host contribution: raw text (validated at build) or an already-structured `Host`. */
internal sealed interface HostValue {
    data class Text(
        val value: String,
    ) : HostValue

    data class Structured(
        val value: Host,
    ) : HostValue
}

/** A decoded userinfo pair. */
internal data class UserInfoValue(
    val username: String,
    val password: String?,
)

/**
 * Accumulates decoded component contributions under the collision policy, then projects once onto a
 * [BuilderSink]. Single-valued slots are first-writer-wins; a conflicting later write is ignored,
 * unless [strict], where it is a [KuriBindException]. Multi-valued path/query accumulate in order.
 */
internal class ComponentSink(
    private val strict: Boolean,
) {
    private var scheme: String? = null
    private var userInfo: UserInfoValue? = null
    private var host: HostValue? = null
    private var port: Int? = null
    private var fragment: String? = null
    private val pathSegments: MutableList<String> = ArrayList()
    private val queryParams: MutableList<Pair<String, String?>> = ArrayList()

    fun setScheme(
        value: String,
        path: String,
    ) {
        if (value.isEmpty()) throw KuriBindException("scheme must not be empty", path)
        scheme = firstWins(scheme, value, "scheme", path)
    }

    fun setUserInfo(
        username: String,
        password: String?,
        path: String,
    ) {
        userInfo = firstWins(userInfo, UserInfoValue(username, password), "userinfo", path)
    }

    fun setHost(
        value: HostValue,
        path: String,
    ) {
        host = firstWins(host, value, "host", path)
    }

    fun setPort(
        value: Int,
        path: String,
    ) {
        port = firstWins(port, value, "port", path)
    }

    fun setFragment(
        decoded: String,
        path: String,
    ) {
        fragment = firstWins(fragment, decoded, "fragment", path)
    }

    fun addPathSegment(decoded: String) {
        pathSegments.add(decoded)
    }

    fun addQueryParameter(
        name: String,
        value: String?,
    ) {
        // Backstop for an empty parameter name; the reachable @QueryMap "" key is rejected earlier with
        // the offending member's path (see LeafBinder.applyQueryMapEntry).
        if (name.isEmpty()) throw KuriBindException("query parameter name must not be empty")
        queryParams.add(name to value)
    }

    fun projectInto(sink: BuilderSink) {
        scheme?.let(sink::scheme)
        userInfo?.let { sink.userInfo(it.username, it.password) }
        when (val h = host) {
            is HostValue.Text -> sink.hostText(h.value)
            is HostValue.Structured -> sink.hostStructured(h.value)
            null -> Unit
        }
        port?.let(sink::port)
        for (segment in pathSegments) sink.addPathSegment(segment)
        for ((name, value) in queryParams) sink.addQueryParameter(name, value)
        fragment?.let(sink::fragmentDecoded)
    }

    private fun <T : Any> firstWins(
        current: T?,
        incoming: T,
        component: String,
        path: String,
    ): T {
        if (current == null) return incoming
        if (strict && current != incoming) {
            throw KuriBindException("conflicting $component: '$current' vs '$incoming'", path)
        }
        return current
    }
}
