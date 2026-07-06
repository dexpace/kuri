/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.percent

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Behavioural tests for the public [Percent] facade over the internal [PercentCodec] engine
 * (SPEC §5). Verifies the [Percent.Component] to encode-set mapping and the lenient, total decode
 * contract; exhaustive codec behaviour is covered by [PercentCodecTest].
 */
class PercentTest {
    // Non-ASCII literals are built from code points so their stored NFC form is deterministic.
    private val eAcute = Char(0x00E9).toString()
    private val snowman = Char(0x2603).toString()

    @Test
    fun `encode escapes reserved characters under the COMPONENT set`() {
        assertEquals("a%20b%2Fc", Percent.encode("a b/c", Percent.Component.COMPONENT))
    }

    @Test
    fun `encode returns the input unchanged when nothing needs escaping`() {
        val input = "abcDEF123-_.~"
        assertEquals(input, Percent.encode(input, Percent.Component.COMPONENT))
    }

    @Test
    fun `encode leaves slash literal for a path segment but escapes it for a component`() {
        assertEquals("/", Percent.encode("/", Percent.Component.PATH_SEGMENT))
        assertEquals("%2F", Percent.encode("/", Percent.Component.COMPONENT))
    }

    @Test
    fun `encode leaves ampersand literal in a query but escapes it in a component`() {
        assertEquals("a&b", Percent.encode("a&b", Percent.Component.QUERY))
        assertEquals("a%26b", Percent.encode("a&b", Percent.Component.COMPONENT))
    }

    @Test
    fun `encode escapes hash in a query but leaves it literal in a fragment`() {
        assertEquals("a%23b", Percent.encode("a#b", Percent.Component.QUERY))
        assertEquals("a#b", Percent.encode("a#b", Percent.Component.FRAGMENT))
    }

    @Test
    fun `encode escapes colon in userinfo but leaves it literal in a path segment`() {
        assertEquals("a%3Ab", Percent.encode("a:b", Percent.Component.USER_INFO))
        assertEquals("a:b", Percent.encode("a:b", Percent.Component.PATH_SEGMENT))
    }

    @Test
    fun `encode renders non-ascii as utf8 octet triplets`() {
        assertEquals("%C3%A9", Percent.encode(eAcute, Percent.Component.QUERY))
        assertEquals("%E2%98%83", Percent.encode(snowman, Percent.Component.PATH_SEGMENT))
    }

    @Test
    fun `encode escapes a space under every component`() {
        Percent.Component.entries.forEach { component ->
            assertEquals("%20", Percent.encode(" ", component), "space must encode under $component")
        }
    }

    @Test
    fun `decode reverses percent triplets back to text`() {
        assertEquals("a b", Percent.decode("a%20b"))
        assertEquals("a/b", Percent.decode("a%2Fb"))
        assertEquals(eAcute, Percent.decode("%C3%A9"))
        assertEquals(snowman, Percent.decode("%E2%98%83"))
    }

    @Test
    fun `decode leaves a malformed percent sequence verbatim`() {
        assertEquals("bad%", Percent.decode("bad%"))
        assertEquals("%zz", Percent.decode("%zz"))
        assertEquals("%", Percent.decode("%"))
    }

    @Test
    fun `decode keeps a literal plus unchanged`() {
        assertEquals("a+b", Percent.decode("a+b"))
    }

    @Test
    fun `encode then decode round-trips arbitrary text under the COMPONENT set`() {
        val raw = "100% a b/c#?&=+" + eAcute + snowman
        val roundTripped = Percent.decode(Percent.encode(raw, Percent.Component.COMPONENT))
        assertEquals(raw, roundTripped)
    }
}
