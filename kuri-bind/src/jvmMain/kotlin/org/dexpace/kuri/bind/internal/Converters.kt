/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.bind.internal

import org.dexpace.kuri.bind.KuriBindException
import org.dexpace.kuri.host.Host

/** Renders a scalar to text: enums by `name`, everything else by `toString`. */
internal fun scalarText(value: Any): String = if (value is Enum<*>) value.name else value.toString()

/** Converts a value to a port, accepting integral numbers and numeric strings. */
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
    return port ?: throw KuriBindException("not a valid port: '$value' (${value::class.simpleName})", path)
}

/** Splits a decoded userinfo token on the first `:` into username and optional password. */
internal fun splitUserInfo(token: String): UserInfoValue {
    val i = token.indexOf(':')
    return if (i < 0) UserInfoValue(token, null) else UserInfoValue(token.substring(0, i), token.substring(i + 1))
}

/** A `Host` stays structured; anything else becomes host text. */
internal fun hostValueOf(value: Any): HostValue =
    if (value is Host) HostValue.Structured(value) else HostValue.Text(scalarText(value))

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
