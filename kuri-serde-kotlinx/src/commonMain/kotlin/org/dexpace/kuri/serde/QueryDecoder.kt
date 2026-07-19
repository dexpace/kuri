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
 * [QueryListDecoder], reading every repeated value for the name via `getAll`. A map element delegates
 * to [QueryMapDecoder], reading its `<name>.key` / `<name>.value` repeated parameters and zipping them
 * back into entries positionally — the mirror of how [QueryMapEncoder] emits them.
 */
@Suppress("TooManyFunctions") // One decode method per primitive kind, mandated by the AbstractDecoder contract.
internal class QueryDecoder(
    private val params: QueryParameters,
) : AbstractDecoder() {
    override val serializersModule: SerializersModule = EmptySerializersModule()

    private var index = -1
    private var currentName: String = ""
    private var currentIsMap = false
    private var entered = false

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        while (++index < descriptor.elementsCount) {
            val name = descriptor.getElementName(index)
            val isMap = descriptor.getElementDescriptor(index).kind == StructureKind.MAP
            if (hasValueFor(name, isMap) || !descriptor.isElementOptional(index)) {
                currentName = name
                currentIsMap = isMap
                return index
            }
        }
        return CompositeDecoder.DECODE_DONE
    }

    /**
     * Whether the query carries a value for an element. A map element is stored under its derived
     * `<name>.key` / `<name>.value` parameters, never under the bare property name, so its presence
     * has to be probed there — otherwise an *optional* or *nullable* map whose data is actually
     * present would be wrongly treated as absent and fall back to its default (or to `null`).
     */
    private fun hasValueFor(
        name: String,
        isMap: Boolean,
    ): Boolean =
        if (isMap) {
            params.has("$name.$MAP_KEY_SUFFIX") || params.has("$name.$MAP_VALUE_SUFFIX")
        } else {
            params.has(name)
        }

    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder =
        when (descriptor.kind) {
            StructureKind.LIST -> QueryListDecoder(params.getAll(currentName))
            StructureKind.MAP -> mapDecoderFor(currentName)
            else -> enterTopLevelStructure()
        }

    /** Enters the (single, top-level) flat class structure, rejecting a second nested attempt. */
    private fun enterTopLevelStructure(): CompositeDecoder {
        if (entered) throw SerializationException("nested objects are not supported by the query format")
        entered = true
        return this
    }

    /**
     * Reads the `<name>.key` / `<name>.value` repeated parameters captured for a map element and
     * pairs them positionally into a [QueryMapDecoder].
     *
     * @throws SerializationException when the two repeated sequences differ in length, which can
     *   only happen from a hand-edited or otherwise malformed query string — [QueryMapEncoder]
     *   always emits one key and one value per entry.
     */
    private fun mapDecoderFor(name: String): QueryMapDecoder {
        val keys = params.getAll("$name.$MAP_KEY_SUFFIX")
        val values = params.getAll("$name.$MAP_VALUE_SUFFIX")
        if (keys.size != values.size) {
            throw SerializationException(
                "map '$name' has ${keys.size} key(s) but ${values.size} value(s)",
            )
        }
        return QueryMapDecoder(keys, values)
    }

    override fun decodeNotNullMark(): Boolean = hasValueFor(currentName, currentIsMap)

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

/**
 * Decodes a map property sequentially from the two same-length [keys]/[values] parameter lists
 * captured for its `<name>.key` / `<name>.value` names, zipping them back into entries by
 * position — the mirror of how [QueryMapEncoder] emits them. The caller ([QueryDecoder]) has
 * already verified `keys.size == values.size`.
 */
@Suppress("TooManyFunctions") // One decode method per primitive kind, mandated by the AbstractDecoder contract.
internal class QueryMapDecoder(
    private val keys: List<String?>,
    private val values: List<String?>,
) : AbstractDecoder() {
    override val serializersModule: SerializersModule = EmptySerializersModule()

    /** Position in the virtual key/value/key/value/... sequence of length `2 * keys.size`. */
    private var cursor = 0

    override fun decodeSequentially(): Boolean = true

    override fun decodeCollectionSize(descriptor: SerialDescriptor): Int = keys.size

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int =
        if (cursor < keys.size + values.size) cursor else CompositeDecoder.DECODE_DONE

    /**
     * A map entry's key or value is always a scalar/enum in this format's scope, so any call
     * here — a nested `@Serializable` object or a nested collection — is out of scope and
     * rejected, mirroring [QueryDecoder.beginStructure]'s top-level guard.
     */
    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder =
        throw SerializationException("nested objects are not supported by the query format")

    /** Reads the next key (even cursor position) or value (odd), advancing the cursor. */
    private fun next(): String {
        val fromKey = cursor % 2 == 0
        val entryIndex = cursor / 2
        cursor++
        val raw = if (fromKey) keys[entryIndex] else values[entryIndex]
        return raw ?: throw SerializationException("null map ${if (fromKey) "key" else "value"}")
    }

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
