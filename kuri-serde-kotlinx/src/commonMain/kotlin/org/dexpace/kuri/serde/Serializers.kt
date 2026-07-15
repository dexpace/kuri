/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.serde

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.dexpace.kuri.Uri
import org.dexpace.kuri.Url

/**
 * A kotlinx.serialization [KSerializer] that represents a [Url] as its canonical string form — so a
 * `Url` field round-trips through JSON, protobuf, CBOR, and any other format transparently.
 *
 * Apply it per property (`@Serializable(with = UrlSerializer::class) val endpoint: Url`), per file
 * (`@file:UseSerializers(UrlSerializer::class)`), or contextually via a `SerializersModule`.
 *
 * ```
 * @Serializable
 * data class Config(@Serializable(with = UrlSerializer::class) val endpoint: Url)
 * Json.encodeToString(Config(Url.parseOrThrow("https://example.com/")))  // {"endpoint":"https://example.com/"}
 * ```
 *
 * Deserialization parses with the WHATWG profile and raises a [SerializationException] on malformed
 * input, keeping failures inside the serialization framework's own error channel.
 */
public object UrlSerializer : KSerializer<Url> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("org.dexpace.kuri.Url", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: Url,
    ) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Url {
        val text = decoder.decodeString()
        return Url.parseOrNull(text) ?: throw SerializationException("not a valid URL: '$text'")
    }
}

/**
 * A kotlinx.serialization [KSerializer] that represents a generic RFC 3986 [Uri] as its canonical
 * string form. Apply it the same way as [UrlSerializer].
 *
 * Deserialization parses with the RFC 3986 profile and raises a [SerializationException] on malformed
 * input.
 */
public object UriSerializer : KSerializer<Uri> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("org.dexpace.kuri.Uri", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: Uri,
    ) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Uri {
        val text = decoder.decodeString()
        return Uri.parseOrNull(text) ?: throw SerializationException("not a valid URI: '$text'")
    }
}
