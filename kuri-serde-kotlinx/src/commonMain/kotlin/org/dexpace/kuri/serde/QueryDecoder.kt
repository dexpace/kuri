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
 * [QueryListDecoder], reading every repeated value for the name via `getAll`. A list property present
 * but empty carries no repeated values of its own, so it is recognized instead via
 * [emptyListMarkerName] — see [isPresentEmptyList].
 */
@Suppress("TooManyFunctions") // One decode method per primitive kind, mandated by the AbstractDecoder contract.
internal class QueryDecoder(
    private val params: QueryParameters,
) : AbstractDecoder() {
    override val serializersModule: SerializersModule = EmptySerializersModule()

    private var index = -1
    private var currentName: String = ""
    private var currentIsEmptyListMarker: Boolean = false
    private var entered = false

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        while (++index < descriptor.elementsCount) {
            val name = descriptor.getElementName(index)
            val emptyListMarker = isPresentEmptyList(descriptor, index, name)
            val present = params.has(name) || emptyListMarker
            if (present || !descriptor.isElementOptional(index)) {
                currentName = name
                currentIsEmptyListMarker = emptyListMarker && !params.has(name)
                return index
            }
        }
        return CompositeDecoder.DECODE_DONE
    }

    /**
     * True when the element at [index] is a list property and its [emptyListMarkerName] marker is
     * present, i.e. it was explicitly encoded as present-but-empty rather than omitted. Scoped to list
     * elements only, so a foreign query string that happens to contain a `<scalarField>[]` pair cannot
     * spuriously mark a scalar property present. [decodeElementIndex] also uses this (via
     * [currentIsEmptyListMarker]) to make [decodeNotNullMark] report a marker-only match as not-null, so
     * a nullable list property reaches [beginStructure] instead of `NullableSerializer` short-circuiting
     * it to `null`.
     */
    private fun isPresentEmptyList(
        descriptor: SerialDescriptor,
        index: Int,
        name: String,
    ): Boolean {
        val isList = descriptor.getElementDescriptor(index).kind == StructureKind.LIST
        return isList && params.has(emptyListMarkerName(name))
    }

    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder {
        if (descriptor.kind == StructureKind.LIST) return QueryListDecoder(params.getAll(currentName))
        if (entered) throw SerializationException(NESTED_OBJECTS_REJECTED_MESSAGE)
        entered = true
        return this
    }

    /**
     * `false` only when the current element is genuinely absent. A list property present solely via its
     * [emptyListMarkerName] marker (no `name=value` pairs of its own) still counts as not-null: without
     * this, `kotlinx.serialization`'s `NullableSerializer` would short-circuit a nullable list straight
     * to `null` on seeing `params.has(currentName)` fail, never reaching [beginStructure] to decode the
     * empty [QueryListDecoder] the marker represents.
     */
    override fun decodeNotNullMark(): Boolean = params.has(currentName) || currentIsEmptyListMarker

    override fun decodeString(): String =
        params[currentName] ?: throw SerializationException("missing query parameter '$currentName'")

    override fun decodeBoolean(): Boolean = parseStrictBoolean(decodeString())

    override fun decodeInt(): Int = scalarOrFail("Int", decodeString(), "value for '$currentName'", String::toIntOrNull)

    override fun decodeLong(): Long =
        scalarOrFail("Long", decodeString(), "value for '$currentName'", String::toLongOrNull)

    override fun decodeShort(): Short =
        scalarOrFail("Short", decodeString(), "value for '$currentName'", String::toShortOrNull)

    override fun decodeByte(): Byte =
        scalarOrFail("Byte", decodeString(), "value for '$currentName'", String::toByteOrNull)

    override fun decodeDouble(): Double =
        scalarOrFail("Double", decodeString(), "value for '$currentName'", String::toDoubleOrNull)

    override fun decodeFloat(): Float =
        scalarOrFail("Float", decodeString(), "value for '$currentName'", String::toFloatOrNull)

    override fun decodeChar(): Char =
        scalarOrFail("Char", decodeString(), "value for '$currentName'") { it.singleOrNull() }

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

    /**
     * A list element is always a scalar/enum in this format's scope, so any call here — a nested
     * `@Serializable` object or a nested list — is out of scope and rejected, mirroring
     * [QueryDecoder.beginStructure]'s top-level guard.
     */
    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder =
        throw SerializationException(NESTED_OBJECTS_REJECTED_MESSAGE)

    private fun next(): String = values[cursor++] ?: throw SerializationException("null list element")

    override fun decodeString(): String = next()

    override fun decodeBoolean(): Boolean = parseStrictBoolean(next())

    override fun decodeInt(): Int = scalarOrFail("Int", next(), "list element", String::toIntOrNull)

    override fun decodeLong(): Long = scalarOrFail("Long", next(), "list element", String::toLongOrNull)

    override fun decodeShort(): Short = scalarOrFail("Short", next(), "list element", String::toShortOrNull)

    override fun decodeByte(): Byte = scalarOrFail("Byte", next(), "list element", String::toByteOrNull)

    override fun decodeDouble(): Double = scalarOrFail("Double", next(), "list element", String::toDoubleOrNull)

    override fun decodeFloat(): Float = scalarOrFail("Float", next(), "list element", String::toFloatOrNull)

    override fun decodeChar(): Char = scalarOrFail("Char", next(), "list element") { it.singleOrNull() }

    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int = enumIndex(enumDescriptor, next())
}

/** Shared by [QueryDecoder.beginStructure] and [QueryListDecoder.beginStructure]'s nesting rejection. */
private const val NESTED_OBJECTS_REJECTED_MESSAGE: String = "nested objects are not supported by the query format"

/** Converts [raw] with [convert], failing with a [SerializationException] describing [kind] and [context] on error. */
private fun <T : Any> scalarOrFail(
    kind: String,
    raw: String,
    context: String,
    convert: (String) -> T?,
): T = convert(raw) ?: throw SerializationException("invalid $kind $context: '$raw'")

/**
 * Parses a query value as a boolean, accepting only `"true"`/`"false"` case-insensitively.
 *
 * Kotlin's [String.toBoolean] treats every non-`"true"` value (including typos like `"ture"` or
 * numeric flags like `"1"`) as `false` without ever failing, which would silently corrupt untrusted
 * query input. This mirrors how the enum and numeric decoders reject unrecognized values instead.
 */
private fun parseStrictBoolean(value: String): Boolean =
    when {
        value.equals("true", ignoreCase = true) -> true
        value.equals("false", ignoreCase = true) -> false
        else -> throw SerializationException("invalid boolean value '$value', expected 'true' or 'false'")
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
