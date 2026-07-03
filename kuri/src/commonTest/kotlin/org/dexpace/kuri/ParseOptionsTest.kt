/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests for [ParseOptions], the immutable opt-in parsing configuration (SPEC §7.2.2, [HOST-18],
 * [CONF-126]). Covers the default-off invariant, the fluent builder, `newBuilder` round-tripping,
 * and structural equality/hashing/`toString` over the single field.
 */
class ParseOptionsTest {
    @Test
    fun `DEFAULT keeps every opt-in off`() {
        assertFalse(ParseOptions.DEFAULT.allowIpv6ZoneId, "zone-id support must default to off")
    }

    @Test
    fun `builder toggles allowIpv6ZoneId on`() {
        val options = ParseOptions.Builder().allowIpv6ZoneId(true).build()

        assertTrue(options.allowIpv6ZoneId)
    }

    @Test
    fun `builder toggles allowIpv6ZoneId back off`() {
        val builder = ParseOptions.Builder().allowIpv6ZoneId(true)

        val options = builder.allowIpv6ZoneId(false).build()

        assertFalse(options.allowIpv6ZoneId)
    }

    @Test
    fun `newBuilder reproduces an equal value`() {
        val original = ParseOptions.Builder().allowIpv6ZoneId(true).build()

        val copy = original.newBuilder().build()

        assertEquals(original, copy)
        assertEquals(original.hashCode(), copy.hashCode())
    }

    @Test
    fun `newBuilder can override a pre-filled option`() {
        val original = ParseOptions.Builder().allowIpv6ZoneId(true).build()

        val flipped = original.newBuilder().allowIpv6ZoneId(false).build()

        assertFalse(flipped.allowIpv6ZoneId)
        assertEquals(ParseOptions.DEFAULT, flipped)
    }

    @Test
    fun `equals and hashCode fold over the single field`() {
        val on = ParseOptions.Builder().allowIpv6ZoneId(true).build()
        val onAgain = ParseOptions.Builder().allowIpv6ZoneId(true).build()
        val off = ParseOptions.Builder().allowIpv6ZoneId(false).build()

        assertEquals(on, onAgain)
        assertEquals(on.hashCode(), onAgain.hashCode())
        assertNotEquals(on, off)
        assertEquals(ParseOptions.DEFAULT, off)
    }

    @Test
    fun `equals rejects a foreign type`() {
        assertNotEquals<Any?>(ParseOptions.DEFAULT, "ParseOptions(allowIpv6ZoneId=false)")
    }

    @Test
    fun `toString names the single field and its value`() {
        val enabled = ParseOptions.Builder().allowIpv6ZoneId(true).build()

        assertEquals("ParseOptions(allowIpv6ZoneId=false)", ParseOptions.DEFAULT.toString())
        assertEquals("ParseOptions(allowIpv6ZoneId=true)", enabled.toString())
    }
}
