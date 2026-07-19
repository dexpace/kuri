/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.serde

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.serializer
import org.dexpace.kuri.query.QueryParameters

/**
 * A kotlinx.serialization format that maps a flat `@Serializable` class to and from [QueryParameters] —
 * the typed **decode** direction that `kuri-bind` (object → URL only) does not provide.
 *
 * ```
 * @Serializable data class Search(val q: String, val page: Int = 1, val tags: List<String> = emptyList())
 *
 * QueryParametersFormat.decodeFromQueryString<Search>("q=kotlin&page=2&tags=a&tags=b")
 * // Search(q = "kotlin", page = 2, tags = ["a", "b"])
 * QueryParametersFormat.encodeToQueryString(Search("kotlin", 2, listOf("a", "b")))
 * // "q=kotlin&page=2&tags=a&tags=b"
 * ```
 *
 * Scope: a single, flat class whose properties are `String`, a primitive, an enum, or a `List` of those.
 * Each scalar property is one parameter; a list property repeats the parameter (duplicate-preserving,
 * matching `QueryParameters`). Decoding and encoding are symmetric about defaults: an absent optional
 * property decodes to its declared default, and — the other direction — a property still at its declared
 * default is omitted from the encoded output, keeping the query string minimal; an absent required
 * property raises a [kotlinx.serialization.SerializationException]. Nested `@Serializable` objects are
 * rejected — model them at the call site or bind them separately.
 *
 * A list property that differs from its default by being explicitly empty has no element pairs to
 * repeat, which would otherwise read back as simply absent and fall to the default instead of decoding
 * to an empty list. That case is carried by a `name[]` marker pair (e.g. `tags[]`) instead — see
 * `emptyListMarkerName` — so "present but empty" and "absent" stay distinguishable on the wire.
 */
public object QueryParametersFormat {
    /**
     * Encodes [value] into a [QueryParameters] using the given [serializer].
     *
     * @return the encoded parameters, in property-declaration order.
     */
    public fun <T> encodeToQueryParameters(
        serializer: SerializationStrategy<T>,
        value: T,
    ): QueryParameters {
        val encoder = QueryEncoder()
        serializer.serialize(encoder, value)
        return encoder.build()
    }

    /**
     * Decodes a value of type `T` from [params] using the given [deserializer].
     *
     * @return the decoded value.
     */
    public fun <T> decodeFromQueryParameters(
        deserializer: DeserializationStrategy<T>,
        params: QueryParameters,
    ): T = deserializer.deserialize(QueryDecoder(params))

    /** Reified [encodeToQueryParameters]. */
    public inline fun <reified T> encodeToQueryParameters(value: T): QueryParameters =
        encodeToQueryParameters(serializer(), value)

    /** Reified [decodeFromQueryParameters]. */
    public inline fun <reified T> decodeFromQueryParameters(params: QueryParameters): T =
        decodeFromQueryParameters(serializer(), params)

    /** Encodes [value] straight to a `%20`-dialect query string. */
    public inline fun <reified T> encodeToQueryString(value: T): String = encodeToQueryParameters(value).toQueryString()

    /** Decodes a value of type `T` from a raw query string. */
    public inline fun <reified T> decodeFromQueryString(query: String): T =
        decodeFromQueryParameters(QueryParameters.parse(query))
}
