/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.percent

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Behavioural tests for [PercentCodec] against SPEC §5.2-§5.5 and the §13.3 category J checklist
 * ([CONF-87]-[CONF-94]).
 */
class PercentCodecTest {
    private val nul = Char(0x00).toString()
    private val replacement = Char(0xFFFD).toString()

    @Test
    fun `decode reproduces the single octet of each triplet per CONF-87`() {
        assertEquals(nul, PercentCodec.decode("%00"))
        assertEquals("z", PercentCodec.decode("%7A"))
        assertEquals("b", PercentCodec.decode("%62"))
    }

    @Test
    fun `decode matches hex digits case-insensitively per PCT-24`() {
        assertEquals(PercentCodec.decode("%7A"), PercentCodec.decode("%7a"))
        assertEquals("z", PercentCodec.decode("%7a"))
    }

    @Test
    fun `decode gathers a triplet run into one utf8 sequence per PCT-25`() {
        assertEquals("☃", PercentCodec.decode("%E2%98%83"))
        assertEquals("🍩", PercentCodec.decode("%F0%9F%8D%A9"))
        assertEquals("a☃", PercentCodec.decode("a%E2%98%83"))
    }

    @Test
    fun `decode leaves malformed percent sequences literal per CONF-88`() {
        assertEquals("a%f", PercentCodec.decode("a%f"))
        assertEquals("%/b", PercentCodec.decode("%/b"))
        assertEquals("%", PercentCodec.decode("%"))
        // The leading % is literal; each following %30 decodes to '0', giving the string "%00".
        assertEquals("%00", PercentCodec.decode("%%30%30"))
    }

    @Test
    fun `decode maps malformed utf8 to the replacement character per CONF-89`() {
        assertEquals(replacement + "x", PercentCodec.decode("%E2%98x"))
    }

    @Test
    fun `decode keeps plus literal by default and maps it to space only on request per PCT-28`() {
        assertEquals("a+b", PercentCodec.decode("a+b"))
        assertEquals("a b", PercentCodec.decode("a+b", plusAsSpace = true))
    }

    @Test
    fun `encode emits uppercase triplets for set members per PCT-19`() {
        assertEquals("%20", PercentCodec.encode(" ", PercentEncodeSets.PATH))
        assertEquals("a%3Cb", PercentCodec.encode("a<b", PercentEncodeSets.PATH))
    }

    @Test
    fun `encode percent-encodes non-ascii as utf8 octets per CONF-91`() {
        assertEquals("%C3%A9", PercentCodec.encode("é", PercentEncodeSets.PATH))
        assertEquals("%E2%98%83", PercentCodec.encode("☃", PercentEncodeSets.PATH))
        assertEquals("%F0%9F%8D%A9", PercentCodec.encode("🍩", PercentEncodeSets.COMPONENT))
    }

    @Test
    fun `encode returns the input unchanged when nothing requires encoding per PCT-20`() {
        val input = "abc-DEF_123.*"
        assertEquals(input, PercentCodec.encode(input, PercentEncodeSets.PATH))
    }

    @Test
    fun `encode preserves an existing mixed-case triplet verbatim per PCT-32`() {
        assertEquals("%6d%6D", PercentCodec.encode("%6d%6D", PercentEncodeSets.PATH))
    }

    @Test
    fun `encode forces the percent sign in the component set per PCT-40`() {
        assertEquals("100%25", PercentCodec.encode("100%", PercentEncodeSets.COMPONENT))
    }

    @Test
    fun `encode maps space to plus and escapes a literal plus only in the form set per PCT-14`() {
        assertEquals("a+b", PercentCodec.encode("a b", PercentEncodeSets.FORM_URLENCODED, spaceAsPlus = true))
        assertEquals("a%2Bb", PercentCodec.encode("a+b", PercentEncodeSets.FORM_URLENCODED, spaceAsPlus = true))
    }

    @Test
    fun `encode percent-encodes c0 controls and del under every set per PCT-1`() {
        assertEquals("%00", PercentCodec.encode(Char(0x00).toString(), PercentEncodeSets.FRAGMENT))
        assertEquals("%7F", PercentCodec.encode(Char(0x7F).toString(), PercentEncodeSets.FRAGMENT))
    }

    @Test
    fun `encode replaces a lone surrogate with the replacement character octets per PCT-21`() {
        assertEquals("%EF%BF%BD", PercentCodec.encode(Char(0xD83C).toString(), PercentEncodeSets.PATH))
    }

    @Test
    fun `decodeNonAscii decodes a multi-octet non-ascii run`() {
        assertEquals("☃", PercentCodec.decodeNonAscii("%E2%98%83"))
        assertEquals("🍩", PercentCodec.decodeNonAscii("%F0%9F%8D%A9"))
    }

    @Test
    fun `decodeNonAscii leaves ascii triplets literal`() {
        assertEquals("%2F", PercentCodec.decodeNonAscii("%2F"))
        assertEquals("%20", PercentCodec.decodeNonAscii("%20"))
        assertEquals("a%41b", PercentCodec.decodeNonAscii("a%41b"))
    }

    @Test
    fun `decodeNonAscii leaves an invalid utf8 run literal`() {
        // Both octets are non-ascii but 0xC3 0xC3 is not valid utf8, so the run stays literal.
        assertEquals("%C3%C3", PercentCodec.decodeNonAscii("%C3%C3"))
        // A truncated 3-byte sequence is likewise invalid and kept verbatim.
        assertEquals("%E2%98", PercentCodec.decodeNonAscii("%E2%98"))
    }

    @Test
    fun `decodeNonAscii keeps a run literal when it mixes ascii and non-ascii octets`() {
        // %20 (ascii) abuts the ü run, so the maximal triplet run is mixed and stays literal.
        assertEquals("%20%C3%BC", PercentCodec.decodeNonAscii("%20%C3%BC"))
    }

    @Test
    fun `decodeNonAscii decodes non-ascii runs bounded by literal text`() {
        assertEquals("/fa" + Char(0x00DF), PercentCodec.decodeNonAscii("/fa%C3%9F"))
        assertEquals("a☃b", PercentCodec.decodeNonAscii("a%E2%98%83b"))
    }

    @Test
    fun `decodeNonAscii is identity for empty and triplet-free input`() {
        assertEquals("", PercentCodec.decodeNonAscii(""))
        assertEquals("abc/def", PercentCodec.decodeNonAscii("abc/def"))
        assertEquals("100%", PercentCodec.decodeNonAscii("100%"))
    }
}
