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
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import org.dexpace.kuri.query.QueryParameters
import org.dexpace.kuri.query.QueryParametersBuilder

/** Suffix appended to a map property's name for its per-entry key parameter (e.g. `meta.key`). */
internal const val MAP_KEY_SUFFIX: String = "key"

/** Suffix appended to a map property's name for its per-entry value parameter (e.g. `meta.value`). */
internal const val MAP_VALUE_SUFFIX: String = "value"

/**
 * Encodes one flat `@Serializable` class into a [QueryParameters]: each element name becomes a
 * parameter name, and its scalar/enum value the parameter value. A list element delegates to
 * [QueryListEncoder], which repeats the parameter. A map element delegates to [QueryMapEncoder],
 * which emits its keys and values as two same-length repeated parameters. Nested objects are
 * rejected.
 */
internal class QueryEncoder : AbstractEncoder() {
    override val serializersModule: SerializersModule = EmptySerializersModule()

    private val builder = QueryParametersBuilder()
    private var currentName: String? = null
    private var currentNeedsEmptyMarker = false
    private var entered = false

    fun build(): QueryParameters = builder.build()

    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
        if (entered) throw SerializationException(NESTED_OBJECTS_REJECTED_MESSAGE)
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
        currentNeedsEmptyMarker =
            descriptor.isElementOptional(index) ||
            descriptor.getElementDescriptor(index).isNullable
        return true
    }

    /**
     * Starts a list or map property. A non-empty collection is carried entirely by its repeated
     * `name=value` pairs (list) or `name.key` / `name.value` pairs (map), so no marker is needed. An
     * empty collection has no elements to repeat, which would otherwise make it indistinguishable on
     * the wire from the property being absent altogether (see [emptyCollectionMarkerName]) — so this
     * adds that marker up front, before [QueryListEncoder]/[QueryMapEncoder] contributes zero further
     * pairs, whenever that ambiguity is actually possible:
     * - an *optional* element (has a default) falls back to that default when absent
     *   (`QueryDecoder.decodeElementIndex`'s `isElementOptional` bypass), so an empty value that
     *   differs from a non-empty default would silently read back as the default without the marker.
     * - a *nullable* element — optional or not — decodes via `decodeNotNullMark()`, which reads
     *   presence off the very same wire signals `beginCollection` would otherwise leave empty, so an
     *   empty collection there is indistinguishable from `null` without the marker.
     *
     * Only a *required, non-nullable* element is exempt: it is always visited on decode regardless of
     * presence and never funnels through `decodeNotNullMark()`, so an empty collection there needs no
     * marker to disambiguate anything — emitting one would only be redundant noise on the wire.
     */
    override fun beginCollection(
        descriptor: SerialDescriptor,
        collectionSize: Int,
    ): CompositeEncoder {
        val name = requireName()
        val isMap = descriptor.kind == StructureKind.MAP
        val isCollectionKind = isMap || descriptor.kind == StructureKind.LIST
        if (collectionSize == 0 && currentNeedsEmptyMarker && isCollectionKind) {
            builder.add(emptyCollectionMarkerName(name), null)
        }
        return if (isMap) QueryMapEncoder(name, builder) else QueryListEncoder(name, builder)
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

    /**
     * A list element is always a scalar/enum in this format's scope, so any call here — a nested
     * `@Serializable` object or a nested list — is out of scope and rejected, mirroring
     * [QueryEncoder.beginStructure]'s top-level guard.
     */
    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder =
        throw SerializationException(NESTED_OBJECTS_REJECTED_MESSAGE)

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
 * Encodes each entry of a map property as a `<name>.key` / `<name>.value` pair of repeated
 * parameters — one occurrence of each per entry, emitted in iteration order — so [QueryMapDecoder]
 * can zip the two same-length repeated sequences back into entries positionally. Flattening keys
 * and values together under one repeated parameter, the way [QueryListEncoder] handles a list,
 * would destroy the pairing between a key and its value; that is exactly the bug this delegate
 * exists to avoid.
 */
internal class QueryMapEncoder(
    private val name: String,
    private val builder: QueryParametersBuilder,
) : AbstractEncoder() {
    override val serializersModule: SerializersModule = EmptySerializersModule()

    /**
     * `true` while the element currently being encoded is an entry's key, `false` while it is
     * the entry's value. kotlinx.serialization visits a map's elements as key, value, key,
     * value, ... at consecutive even/odd indices, so parity alone tells them apart.
     */
    private var encodingKey = true

    override fun encodeElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Boolean {
        encodingKey = index % 2 == 0
        return true
    }

    /**
     * A map entry's key or value is always a scalar/enum in this format's scope, so any call
     * here — a nested `@Serializable` object or a nested collection — is out of scope and
     * rejected, mirroring [QueryEncoder.beginStructure]'s top-level guard.
     */
    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder =
        throw SerializationException(NESTED_OBJECTS_REJECTED_MESSAGE)

    override fun encodeValue(value: Any) {
        builder.add(paramName(), value.toString())
    }

    override fun encodeEnum(
        enumDescriptor: SerialDescriptor,
        index: Int,
    ) {
        builder.add(paramName(), enumDescriptor.getElementName(index))
    }

    /**
     * A `null` map key or value is out of this format's scope (see [QueryParametersFormat]'s scope
     * KDoc): writing only the key (or only the value) of an entry would desync the "one key per one
     * value" invariant [QueryMapDecoder] depends on, and that mismatch would only surface later as a
     * confusing key/value-count-mismatch decode failure that gives no hint the real cause was a null
     * entry here. Failing fast with a dedicated message covers both cases, since [encodeElement]'s
     * parity check applies identically to a null key and a null value.
     */
    override fun encodeNull(): Unit =
        throw SerializationException("the query format does not support a null map key or value")

    /** The parameter name for the element currently being encoded: `<name>.key` or `<name>.value`. */
    private fun paramName(): String = "$name.${if (encodingKey) MAP_KEY_SUFFIX else MAP_VALUE_SUFFIX}"
}

/**
 * The nesting-rejection message shared by [QueryEncoder.beginStructure], [QueryListEncoder.beginStructure],
 * and [QueryMapEncoder.beginStructure].
 */
private const val NESTED_OBJECTS_REJECTED_MESSAGE: String = "nested objects are not supported by the query format"

/**
 * Suffix marking the wire-level "present but empty" sentinel for a list or map property, appended
 * to its declared name (e.g. `tags` -> `tags[]`). `[`/`]` are never percent-encoded by the query name
 * encode set, so the marker stays literal in the encoded string. A Kotlin property name cannot itself
 * contain `[`/`]`, so the marker cannot collide with a declared property's default serial name; a
 * property whose serial name is deliberately overridden via `@SerialName` to end in `[]` could still
 * collide — not a supported/tested shape.
 */
private const val EMPTY_COLLECTION_MARKER_SUFFIX: String = "[]"

/**
 * The wire name of the empty-collection marker for a list or map property declared as [name].
 *
 * A list property's non-empty state is fully carried by its repeated `name=value` pairs, and a map
 * property's by its repeated `name.key` / `name.value` pairs; an empty collection has none of those,
 * which is indistinguishable from the property being entirely absent (and would therefore fall back
 * to its declared default on decode instead of decoding to an empty collection). [QueryEncoder] emits
 * this marker as a bare (no `=`) pair when a list or map encodes to zero elements, and [QueryDecoder]
 * treats its presence as "present, zero elements" without contributing any element itself.
 *
 * @param name the property's declared (unsuffixed) name.
 * @return [name] with [EMPTY_COLLECTION_MARKER_SUFFIX] appended.
 */
internal fun emptyCollectionMarkerName(name: String): String = name + EMPTY_COLLECTION_MARKER_SUFFIX
