/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
@file:OptIn(ExperimentalSerializationApi::class)

package org.dexpace.kuri.serde

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import org.dexpace.kuri.query.QueryParameters

/**
 * Decodes one flat `@Serializable` class from a [QueryParameters]. Absent optional elements are skipped
 * (so their declared default applies); an absent required element fails. A list element delegates to
 * [QueryListDecoder], reading every repeated value for the name via `getAll`.
 */
@Suppress("TooManyFunctions") // One decode method per primitive kind, mandated by the AbstractDecoder contract.
internal class QueryDecoder(
    private val params: QueryParameters,
) : AbstractDecoder() {
    override val serializersModule: SerializersModule = EmptySerializersModule()

    private var index = -1
    private var currentName: String = ""
    private var entered = false

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        while (++index < descriptor.elementsCount) {
            val name = descriptor.getElementName(index)
            if (params.has(name) || !descriptor.isElementOptional(index)) {
                currentName = name
                return index
            }
        }
        return CompositeDecoder.DECODE_DONE
    }

    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder {
        if (descriptor.kind == StructureKind.LIST) return QueryListDecoder(params.getAll(currentName))
        if (entered) throw SerializationException("nested objects are not supported by the query format")
        entered = true
        return this
    }

    override fun decodeNotNullMark(): Boolean = params.has(currentName)

    override fun decodeString(): String =
        params[currentName] ?: throw SerializationException("missing query parameter '$currentName'")

    override fun decodeBoolean(): Boolean = decodeString().toBoolean()

    override fun decodeInt(): Int = decodeString().toInt()

    override fun decodeLong(): Long = decodeString().toLong()

    override fun decodeShort(): Short = decodeString().toShort()

    override fun decodeByte(): Byte = decodeString().toByte()

    override fun decodeDouble(): Double = decodeString().toDouble()

    override fun decodeFloat(): Float = decodeString().toFloat()

    override fun decodeChar(): Char = decodeString().single()

    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int = enumIndex(enumDescriptor, decodeString())
}

/** Decodes a list property sequentially from the repeated values captured for one parameter name. */
@Suppress("TooManyFunctions") // One decode method per primitive kind, mandated by the AbstractDecoder contract.
internal class QueryListDecoder(
    private val values: List<String?>,
) : AbstractDecoder() {
    override val serializersModule: SerializersModule = EmptySerializersModule()

    private var cursor = 0

    override fun decodeSequentially(): Boolean = true

    override fun decodeCollectionSize(descriptor: SerialDescriptor): Int = values.size

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int =
        if (cursor < values.size) cursor else CompositeDecoder.DECODE_DONE

    private fun next(): String = values[cursor++] ?: throw SerializationException("null list element")

    override fun decodeString(): String = next()

    override fun decodeBoolean(): Boolean = next().toBoolean()

    override fun decodeInt(): Int = next().toInt()

    override fun decodeLong(): Long = next().toLong()

    override fun decodeShort(): Short = next().toShort()

    override fun decodeByte(): Byte = next().toByte()

    override fun decodeDouble(): Double = next().toDouble()

    override fun decodeFloat(): Float = next().toFloat()

    override fun decodeChar(): Char = next().single()

    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int = enumIndex(enumDescriptor, next())
}

/** Resolves an enum constant name to its index, failing on an unknown value. */
private fun enumIndex(
    enumDescriptor: SerialDescriptor,
    name: String,
): Int {
    val i = enumDescriptor.getElementIndex(name)
    if (i == CompositeDecoder.UNKNOWN_NAME) {
        throw SerializationException("unknown enum value '$name' for ${enumDescriptor.serialName}")
    }
    return i
}
