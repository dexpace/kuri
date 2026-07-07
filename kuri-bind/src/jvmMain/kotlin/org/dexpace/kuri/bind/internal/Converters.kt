/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.bind.internal

import org.dexpace.kuri.bind.KuriBindException
import org.dexpace.kuri.host.Host

/** Renders a scalar to text: enums by `name`, everything else by `toString`. */
internal fun scalarText(value: Any): String = if (value is Enum<*>) value.name else value.toString()

/**
 * Coerces a value to a non-negative port number, accepting integral numbers and numeric strings. A
 * non-numeric value or a negative number raises [KuriBindException] carrying [path], so a misconfigured
 * port surfaces with the member it came from.
 *
 * Only the profile-agnostic floor (`>= 0`, required by both profiles) is enforced here; the upper bound
 * is deliberately left to each profile's builder at projection — `Url.Builder.port` caps at 65535 while
 * `Uri.Builder.port` applies no cap (RFC 3986 `port = *DIGIT`). Enforcing a shared 65535 ceiling here
 * would wrongly reject `Uri` ports the core accepts, and would duplicate a range the builders already
 * own; keeping this converter profile-agnostic matches the rest of the accumulation stage.
 */
internal fun convertPort(
    value: Any,
    path: String,
): Int {
    require(path.isNotEmpty()) { "path context required" }
    val port: Int? =
        when (value) {
            is Int -> value
            is Short -> value.toInt()
            is Byte -> value.toInt()
            is Long -> if (value in Int.MIN_VALUE..Int.MAX_VALUE) value.toInt() else null
            is String -> value.toIntOrNull()
            else -> null
        }
    val resolved = port ?: throw KuriBindException("not a valid port: '$value' (${value::class.simpleName})", path)
    if (resolved < 0) throw KuriBindException("port must be non-negative [$resolved]", path)
    return resolved
}

/** Splits a decoded userinfo token on the first `:` into username and optional password. */
internal fun splitUserInfo(token: String): UserInfoValue {
    val i = token.indexOf(':')
    return if (i < 0) UserInfoValue(token, null) else UserInfoValue(token.substring(0, i), token.substring(i + 1))
}

/** Renders a host contribution to text: a structured `Host` via [Host.asText], anything else via toString. */
internal fun hostValueOf(value: Any): String = if (value is Host) value.asText() else scalarText(value)

/**
 * Views arrays and `Iterable`s as `Iterable<Any?>`; returns null for non-collections. Every array
 * kind is covered so the view stays aligned with `isCollectionType` (which treats all arrays as
 * collections) — otherwise a primitive array would slip through and render as a single `"[B@…"`.
 */
internal fun asIterableOrNull(value: Any): Iterable<Any?>? =
    when (value) {
        is Iterable<*> -> value
        is Array<*> -> value.asList()
        is IntArray -> value.asList()
        is LongArray -> value.asList()
        is ByteArray -> value.asList()
        is ShortArray -> value.asList()
        is CharArray -> value.asList()
        is BooleanArray -> value.asList()
        is FloatArray -> value.asList()
        is DoubleArray -> value.asList()
        else -> null
    }

/** Views a `Map`; returns null otherwise. */
internal fun asMapOrNull(value: Any): Map<*, *>? = value as? Map<*, *>
