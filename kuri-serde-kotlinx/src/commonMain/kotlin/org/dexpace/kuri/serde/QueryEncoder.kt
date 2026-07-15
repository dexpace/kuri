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

    override fun encodeElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Boolean {
        currentName = descriptor.getElementName(index)
        return true
    }

    override fun beginCollection(
        descriptor: SerialDescriptor,
        collectionSize: Int,
    ): CompositeEncoder = QueryListEncoder(requireName(), builder)

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
