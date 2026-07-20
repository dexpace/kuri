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
 * back into entries positionally — the mirror of how [QueryMapEncoder] emits them. A list or map
 * property present but explicitly empty carries none of those repeated values, so it is recognized
 * instead via [emptyCollectionMarkerName] — see [hasValueFor].
 */
@Suppress("TooManyFunctions") // One decode method per primitive kind, mandated by the AbstractDecoder contract.
internal class QueryDecoder(
    private val params: QueryParameters,
) : AbstractDecoder() {
    override val serializersModule: SerializersModule = EmptySerializersModule()

    private var index = -1
    private var currentName: String = ""
    private var currentIsMap = false
    private var currentIsList = false
    private var entered = false

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        while (++index < descriptor.elementsCount) {
            val name = descriptor.getElementName(index)
            val kind = descriptor.getElementDescriptor(index).kind
            val isMap = kind == StructureKind.MAP
            val isList = kind == StructureKind.LIST
            if (hasValueFor(name, isMap, isList) || !descriptor.isElementOptional(index)) {
                currentName = name
                currentIsMap = isMap
                currentIsList = isList
                return index
            }
        }
        return CompositeDecoder.DECODE_DONE
    }

    /**
     * Whether the query carries a value for an element.
     *
     * A map element is stored under its derived `<name>.key` / `<name>.value` parameters, never under
     * the bare property name, so its presence has to be probed there — otherwise an *optional* or
     * *nullable* map whose data is actually present would be wrongly treated as absent and fall back to
     * its default (or to `null`). A list or map property present but explicitly empty carries none of
     * those repeated pairs either, which is indistinguishable from the property being entirely absent —
     * that case is instead signaled by [emptyCollectionMarkerName]. Scoped to list/map elements only, so
     * a foreign query string that happens to contain a `<scalarField>[]` pair cannot spuriously mark a
     * scalar property present.
     */
    private fun hasValueFor(
        name: String,
        isMap: Boolean,
        isList: Boolean,
    ): Boolean =
        when {
            isMap ->
                params.has("$name.$MAP_KEY_SUFFIX") ||
                    params.has("$name.$MAP_VALUE_SUFFIX") ||
                    params.has(emptyCollectionMarkerName(name))
            isList -> params.has(name) || params.has(emptyCollectionMarkerName(name))
            else -> params.has(name)
        }

    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder =
        when (descriptor.kind) {
            StructureKind.LIST -> listDecoderFor(currentName)
            StructureKind.MAP -> mapDecoderFor(currentName)
            else -> enterTopLevelStructure()
        }

    /** Enters the (single, top-level) flat class structure, rejecting a second nested attempt. */
    private fun enterTopLevelStructure(): CompositeDecoder {
        if (entered) throw SerializationException(NESTED_OBJECTS_REJECTED_MESSAGE)
        entered = true
        return this
    }

    /**
     * Reads the repeated values captured for a list element under [name] into a [QueryListDecoder].
     *
     * @throws SerializationException when the query also carries [name]'s empty-collection marker
     *   alongside those real values — see [rejectConflictingEmptyMarker].
     */
    private fun listDecoderFor(name: String): QueryListDecoder {
        val values = params.getAll(name)
        rejectConflictingEmptyMarker(name, hasRealValues = values.isNotEmpty())
        return QueryListDecoder(values)
    }

    /**
     * Reads the `<name>.key` / `<name>.value` repeated parameters captured for a map element and
     * pairs them positionally into a [QueryMapDecoder].
     *
     * @throws SerializationException when the query also carries [name]'s empty-collection marker
     *   alongside real key/value pairs (see [rejectConflictingEmptyMarker]), or when the two repeated
     *   sequences differ in length, which can only happen from a hand-edited or otherwise malformed
     *   query string — [QueryMapEncoder] always emits one key and one value per entry.
     */
    private fun mapDecoderFor(name: String): QueryMapDecoder {
        val keys = params.getAll("$name.$MAP_KEY_SUFFIX")
        val values = params.getAll("$name.$MAP_VALUE_SUFFIX")
        rejectConflictingEmptyMarker(name, hasRealValues = keys.isNotEmpty() || values.isNotEmpty())
        if (keys.size != values.size) {
            throw SerializationException(
                "map '$name' has ${keys.size} key(s) but ${values.size} value(s)",
            )
        }
        return QueryMapDecoder(keys, values)
    }

    /**
     * Rejects a self-contradictory query string that carries both real values for [name] (per
     * [hasRealValues]) and [name]'s [emptyCollectionMarkerName] marker. That shape can only come
     * from a hand-edited or otherwise foreign/malformed query string — [QueryEncoder] never emits
     * both for the same property — so it is treated the same way this format treats any other
     * malformed wire shape (an unparsable scalar, a key/value count mismatch): rejected outright
     * rather than silently resolved by preferring the real values and ignoring the marker. Applies
     * regardless of whether the element is required or optional, since this is a wire-shape validity
     * problem rather than a presence/absence one.
     *
     * @throws SerializationException when both are present.
     */
    private fun rejectConflictingEmptyMarker(
        name: String,
        hasRealValues: Boolean,
    ) {
        val markerName = emptyCollectionMarkerName(name)
        if (hasRealValues && params.has(markerName)) {
            throw SerializationException(
                "parameter '$name' has both a value and an empty-collection marker '$markerName'",
            )
        }
    }

    /**
     * `false` only when the current element is genuinely absent. A list or map property present solely
     * via its [emptyCollectionMarkerName] marker (no repeated pairs of its own) still counts as
     * not-null: without this, `kotlinx.serialization`'s `NullableSerializer` would short-circuit a
     * nullable collection straight to `null` on seeing no repeated pairs, never reaching [beginStructure]
     * to decode the empty [QueryListDecoder]/[QueryMapDecoder] the marker represents.
     */
    override fun decodeNotNullMark(): Boolean = hasValueFor(currentName, currentIsMap, currentIsList)

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
        throw SerializationException(NESTED_OBJECTS_REJECTED_MESSAGE)

    /** Reads the next key (even cursor position) or value (odd), advancing the cursor. */
    private fun next(): String {
        val fromKey = cursor % 2 == 0
        val entryIndex = cursor / 2
        cursor++
        val raw = if (fromKey) keys[entryIndex] else values[entryIndex]
        return raw ?: throw SerializationException("null map ${if (fromKey) "key" else "value"}")
    }

    /**
     * Reads the next scalar via [next] and converts it with [convert], routed through [scalarOrFail]
     * so a malformed map key or value fails with a [SerializationException] instead of a foreign
     * exception type (`NumberFormatException`, etc.) — mirrors how [QueryDecoder]/[QueryListDecoder]
     * decode their own scalars. The key/value label is read from `cursor` *before* [next] advances it.
     */
    private fun <T : Any> nextScalar(
        kind: String,
        convert: (String) -> T?,
    ): T {
        val fromKey = cursor % 2 == 0
        return scalarOrFail(kind, next(), "map ${if (fromKey) "key" else "value"}", convert)
    }

    override fun decodeString(): String = next()

    override fun decodeBoolean(): Boolean = parseStrictBoolean(next())

    override fun decodeInt(): Int = nextScalar("Int", String::toIntOrNull)

    override fun decodeLong(): Long = nextScalar("Long", String::toLongOrNull)

    override fun decodeShort(): Short = nextScalar("Short", String::toShortOrNull)

    override fun decodeByte(): Byte = nextScalar("Byte", String::toByteOrNull)

    override fun decodeDouble(): Double = nextScalar("Double", String::toDoubleOrNull)

    override fun decodeFloat(): Float = nextScalar("Float", String::toFloatOrNull)

    override fun decodeChar(): Char = nextScalar("Char") { it.singleOrNull() }

    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int = enumIndex(enumDescriptor, next())
}

/**
 * The nesting-rejection message shared by [QueryDecoder.beginStructure], [QueryListDecoder.beginStructure],
 * and [QueryMapDecoder.beginStructure].
 */
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
