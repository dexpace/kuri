/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.idna

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the RFC 3492 Punycode codec backing IDNA/UTS-46 (SPEC §7.4).
 *
 * The `unicode to punycode` table pairs a Unicode label's content with its ACE payload
 * (i.e. the bytes that would follow the `xn--` prefix). It mixes RFC 3492 §7.1 example
 * strings with the IDNA-relevant German cases so both encode and decode are exercised on
 * the same data. The non-Latin labels are spelled out as explicit code points so the
 * fixtures cannot drift on source-glyph or escape-rendering ambiguity.
 */
class PunycodeTest {
    // RFC 3492 §7.1 (A): Arabic "Why can't they just speak Arabic?".
    private val arabicLabel: String =
        fromCodePoints(
            0x0644,
            0x064A,
            0x0647,
            0x0645,
            0x0627,
            0x0628,
            0x062A,
            0x0643,
            0x0644,
            0x0645,
            0x0648,
            0x0634,
            0x0639,
            0x0631,
            0x0628,
            0x064A,
            0x061F,
        )

    // RFC 3492 §7.1 (D): Chinese (simplified) "Why can't they just speak Chinese?".
    private val chineseLabel: String =
        fromCodePoints(0x4ED6, 0x4EEC, 0x4E3A, 0x4EC0, 0x4E48, 0x4E0D, 0x8BF4, 0x4E2D, 0x6587)

    private val unicodeToPunycode: List<Pair<String, String>> =
        listOf(
            // German "bücher" (books) — basic code points then the ACE delimiter.
            "bücher" to "bcher-kva",
            // German "faß" content — UTS-46 non-transitional keeps ß, so it encodes.
            "faß" to "fa-hia",
            // Single non-basic code point: no basic prefix, hence no delimiter.
            "ü" to "tda",
            arabicLabel to "egbpdaj6bu4bxfgehfvwxn",
            chineseLabel to "ihqwcrb4cv8a8dqg056pqjye",
        )

    @Test
    fun `encode produces the RFC 3492 ACE payload for each example label`() {
        for ((unicode, punycode) in unicodeToPunycode) {
            assertEquals(punycode, Punycode.encode(unicode), "encode($punycode)")
        }
    }

    @Test
    fun `decode reverses the RFC 3492 ACE payload for each example label`() {
        for ((unicode, punycode) in unicodeToPunycode) {
            assertEquals(unicode, Punycode.decode(punycode), "decode($punycode)")
        }
    }

    @Test
    fun `encode then decode round-trips every example label`() {
        for ((unicode, _) in unicodeToPunycode) {
            val encoded = assertNotNull(Punycode.encode(unicode))
            assertEquals(unicode, Punycode.decode(encoded), "round-trip $encoded")
        }
    }

    @Test
    fun `encode leaves the faß-mapped ASCII form fass unchanged`() {
        // "fass" is already ASCII (the transitional mapping of "faß"); it stays verbatim,
        // whereas the non-transitional "faß" content is what actually needs encoding.
        assertEquals("fass", Punycode.encode("fass"))
        assertEquals("fa-hia", Punycode.encode("faß"))
    }

    @Test
    fun `encode returns an ASCII-only label unchanged with no xn-- prefix`() {
        val label = "plain-label-123"
        val encoded = assertNotNull(Punycode.encode(label))
        assertEquals(label, encoded)
        assertFalse(encoded.startsWith("xn--"), "ASCII label must not gain an ACE prefix")
    }

    @Test
    fun `encode emits a payload without the xn-- prefix for a non-ASCII label`() {
        val encoded = assertNotNull(Punycode.encode("bücher"))
        assertFalse(encoded.startsWith("xn--"), "encode must omit the ACE prefix")
    }

    @Test
    fun `encode and decode round-trip a supplementary-plane code point`() {
        val grinningFace = fromCodePoints(0x1F600) // Encoded as a surrogate pair.
        val encoded = assertNotNull(Punycode.encode(grinningFace))
        assertEquals("e28h", encoded)
        assertEquals(grinningFace, Punycode.decode(encoded))
    }

    @Test
    fun `encode returns the empty string for empty input`() {
        assertEquals("", Punycode.encode(""))
    }

    @Test
    fun `decode returns null for a non-LDH code point in the payload`() {
        // A space is not a Punycode digit; IgnoreInvalidPunycode is false, so decode fails.
        assertNull(Punycode.decode("ab cd"))
    }

    @Test
    fun `decode returns null when a basic section holds a non-ASCII code point`() {
        assertNull(Punycode.decode("bü-cher"))
    }

    @Test
    fun `decode returns null when the payload decodes to a lone surrogate scalar`() {
        // A long run of maximal base-36 digits decodes to U+DEF3 first: an unpaired surrogate is
        // not a valid Unicode scalar, so the strict decoder rejects it (IgnoreInvalidPunycode=false).
        assertNull(Punycode.decode("zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz"))
    }

    @Test
    fun `decode returns null when the trailing generalized integer is truncated`() {
        // "ä" encodes to a payload that ends mid-number once a digit is dropped.
        val truncated = assertNotNull(Punycode.encode("ä")).dropLast(1)
        assertTrue(truncated.isNotEmpty(), "fixture must leave a partial number")
        assertNull(Punycode.decode(truncated))
    }

    private fun fromCodePoints(vararg codePoints: Int): String =
        buildString {
            for (codePoint in codePoints) {
                if (codePoint <= 0xFFFF) {
                    append(codePoint.toChar())
                } else {
                    val offset = codePoint - 0x10000
                    append((0xD800 + (offset ushr 10)).toChar())
                    append((0xDC00 + (offset and 0x3FF)).toChar())
                }
            }
        }
}
