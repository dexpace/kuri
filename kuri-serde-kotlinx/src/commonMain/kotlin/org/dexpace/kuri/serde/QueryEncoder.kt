/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
@file:OptIn(ExperimentalSerializationApi::class)

package org.dexpace.kuri.serde

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import org.dexpace.kuri.query.QueryParameters
import org.dexpace.kuri.query.QueryParametersBuilder

/**
 * Encodes one flat `@Serializable` class into a [QueryParameters]: each element name becomes a
 * parameter name, and its scalar/enum value the parameter value. A list element delegates to
 * [QueryListEncoder], which repeats the parameter. Nested objects are rejected.
 */
internal class QueryEncoder : AbstractEncoder() {
    override val serializersModule: SerializersModule = EmptySerializersModule()

    private val builder = QueryParametersBuilder()
    private var currentName: String? = null
    private var entered = false

    fun build(): QueryParameters = builder.build()

    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
        if (entered) throw SerializationException("nested objects are not supported by the query format")
        entered = true
        return this
    }

    /**
     * Omits a property left at its declared default, mirroring the decode side's "absent falls back to
     * the default" contract and keeping the encoded query string minimal. `AbstractEncoder`'s inherited
     * default is `true` (always encode), which would otherwise spell out every default (e.g. `page=1`).
     */
    override fun shouldEncodeElementDefault(
        descriptor: SerialDescriptor,
        index: Int,
    ): Boolean = false

    override fun encodeElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Boolean {
        currentName = descriptor.getElementName(index)
        return true
    }

    /**
     * Starts a list property. A non-empty list is carried entirely by its repeated `name=value`
     * pairs, so no marker is needed. An empty list has no elements to repeat, which would otherwise
     * make it indistinguishable on the wire from the property being absent altogether (see
     * [emptyListMarkerName]) — so this adds that marker up front, before [QueryListEncoder] contributes
     * zero further pairs.
     */
    override fun beginCollection(
        descriptor: SerialDescriptor,
        collectionSize: Int,
    ): CompositeEncoder {
        val name = requireName()
        if (collectionSize == 0) builder.add(emptyListMarkerName(name), null)
        return QueryListEncoder(name, builder)
    }

    override fun encodeValue(value: Any) {
        builder.add(requireName(), value.toString())
    }

    override fun encodeEnum(
        enumDescriptor: SerialDescriptor,
        index: Int,
    ) {
        builder.add(requireName(), enumDescriptor.getElementName(index))
    }

    /** An absent optional/nullable value is simply omitted from the query. */
    override fun encodeNull() = Unit

    private fun requireName(): String =
        currentName ?: throw SerializationException("the query format supports only a flat @Serializable class")
}

/** Encodes each element of a list property as a repeated parameter under one [name]. */
internal class QueryListEncoder(
    private val name: String,
    private val builder: QueryParametersBuilder,
) : AbstractEncoder() {
    override val serializersModule: SerializersModule = EmptySerializersModule()

    override fun encodeValue(value: Any) {
        builder.add(name, value.toString())
    }

    override fun encodeEnum(
        enumDescriptor: SerialDescriptor,
        index: Int,
    ) {
        builder.add(name, enumDescriptor.getElementName(index))
    }
}

/**
 * Suffix marking the wire-level "present but empty" sentinel for a list property, appended to its
 * declared name (e.g. `tags` -> `tags[]`). `[`/`]` are never percent-encoded by the query name encode
 * set, so the marker stays literal in the encoded string. A Kotlin property name cannot itself contain
 * `[`/`]`, so the marker can never collide with a real element name.
 */
private const val EMPTY_LIST_MARKER_SUFFIX: String = "[]"

/**
 * The wire name of the empty-collection marker for a list property declared as [name].
 *
 * A list property's non-empty state is fully carried by its repeated `name=value` pairs; an empty list
 * has none, which is indistinguishable from the property being entirely absent (and would therefore
 * fall back to its declared default on decode instead of decoding to an empty list). [QueryEncoder]
 * emits this marker as a bare (no `=`) pair when a list encodes to zero elements, and [QueryDecoder]
 * treats its presence as "present, zero elements" without contributing any element itself.
 *
 * @param name the list property's declared (unsuffixed) name.
 * @return [name] with [EMPTY_LIST_MARKER_SUFFIX] appended.
 */
internal fun emptyListMarkerName(name: String): String = name + EMPTY_LIST_MARKER_SUFFIX
