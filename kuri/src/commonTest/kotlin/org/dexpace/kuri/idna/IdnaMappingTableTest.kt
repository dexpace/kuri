/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.idna

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Spot-checks [IdnaMappingTable.map] against known rows of Unicode's IdnaMappingTable.txt under the
 * `Url`-profile parameter set (SPEC §7.4, [HOST-28]: `UseSTD3ASCIIRules = false`,
 * `Transitional_Processing = false`).
 */
class IdnaMappingTableTest {
    @Test
    fun `maps ASCII upper A to lower a`() {
        assertEquals(IdnaMapping.Mapped("a"), IdnaMappingTable.map('A'.code))
    }

    @Test
    fun `maps ASCII upper Z to lower z`() {
        assertEquals(IdnaMapping.Mapped("z"), IdnaMappingTable.map('Z'.code))
    }

    @Test
    fun `treats ASCII lower a as valid`() {
        assertEquals(IdnaMapping.Valid, IdnaMappingTable.map('a'.code))
    }

    @Test
    fun `treats ASCII digits as valid`() {
        assertEquals(IdnaMapping.Valid, IdnaMappingTable.map('0'.code))
        assertEquals(IdnaMapping.Valid, IdnaMappingTable.map('9'.code))
    }

    @Test
    fun `ignores soft hyphen U+00AD`() {
        assertEquals(IdnaMapping.Ignored, IdnaMappingTable.map(0x00AD))
    }

    @Test
    fun `keeps sharp s U+00DF as deviation under non-transitional processing`() {
        // 00DF is a deviation character; non-transitional processing keeps it (treated as valid).
        assertEquals(IdnaMapping.Deviation, IdnaMappingTable.map(0x00DF))
    }

    @Test
    fun `maps capital sharp s U+1E9E to small sharp s`() {
        assertEquals(IdnaMapping.Mapped("ß"), IdnaMappingTable.map(0x1E9E))
    }

    @Test
    fun `treats NUL U+0000 as valid because STD3 rules are disabled`() {
        // 0000..002C is disallowed_STD3_valid; with UseSTD3ASCIIRules=false it is VALID here.
        // NUL's host-disallowance comes from the forbidden-domain re-scan ([HOST-30]), not mapping.
        assertEquals(IdnaMapping.Valid, IdnaMappingTable.map(0x0000))
    }

    @Test
    fun `treats C1 control U+0080 as disallowed`() {
        // 0080..009F is a plain `disallowed` range, unaffected by the STD3 setting.
        assertEquals(IdnaMapping.Disallowed, IdnaMappingTable.map(0x0080))
    }

    @Test
    fun `maps non-breaking space U+00A0 to an ASCII space`() {
        // disallowed_STD3_mapped becomes MAPPED with UseSTD3ASCIIRules=false.
        assertEquals(IdnaMapping.Mapped(" "), IdnaMappingTable.map(0x00A0))
    }

    @Test
    fun `maps the DZ-with-caron digraph U+01C4 to a multi-code-point expansion`() {
        // 01C4 maps to two code points: 'd' followed by U+017E (small z with caron).
        assertEquals(IdnaMapping.Mapped("dž"), IdnaMappingTable.map(0x01C4))
    }

    @Test
    fun `treats BMP private use U+E000 as disallowed`() {
        assertEquals(IdnaMapping.Disallowed, IdnaMappingTable.map(0xE000))
    }

    @Test
    fun `treats valid BMP CJK ideograph U+4E00 as valid`() {
        assertEquals(IdnaMapping.Valid, IdnaMappingTable.map(0x4E00))
    }

    @Test
    fun `maps astral CJK compatibility ideograph U+2F800 to its canonical form`() {
        assertEquals(IdnaMapping.Mapped("丽"), IdnaMappingTable.map(0x2F800))
    }

    @Test
    fun `treats the maximum code point U+10FFFF as disallowed`() {
        assertEquals(IdnaMapping.Disallowed, IdnaMappingTable.map(0x10FFFF))
    }

    @Test
    fun `treats first astral code point U+10000 as valid`() {
        assertEquals(IdnaMapping.Valid, IdnaMappingTable.map(0x10000))
    }

    @Test
    fun `rejects a code point below the valid range`() {
        assertFailsWith<IllegalArgumentException> { IdnaMappingTable.map(-1) }
    }

    @Test
    fun `rejects a code point above the valid range`() {
        assertFailsWith<IllegalArgumentException> { IdnaMappingTable.map(0x110000) }
    }
}
