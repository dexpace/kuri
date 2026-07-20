/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri

import org.dexpace.kuri.error.ResourceLimit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests for [ParseOptions], the immutable opt-in parsing configuration (SPEC §7.2.2, [HOST-18],
 * [CONF-126]). Covers the default-off/default-[ResourceLimit] invariant, the fluent builder,
 * `newBuilder` round-tripping, the per-parse [ResourceLimit] overrides (SPEC §12.6, [ERR-36]), and
 * structural equality/hashing/`toString` over every field.
 */
class ParseOptionsTest {
    @Test
    fun `DEFAULT keeps every opt-in off`() {
        assertFalse(ParseOptions.DEFAULT.allowIpv6ZoneId, "zone-id support must default to off")
    }

    @Test
    fun `DEFAULT applies every ResourceLimit's documented default`() {
        assertEquals(ResourceLimit.InputLength.defaultMax, ParseOptions.DEFAULT.inputLength)
        assertEquals(ResourceLimit.ExpandedLength.defaultMax, ParseOptions.DEFAULT.expandedLength)
        assertEquals(ResourceLimit.PathSegments.defaultMax, ParseOptions.DEFAULT.pathSegments)
        assertEquals(ResourceLimit.ResolutionDepth.defaultMax, ParseOptions.DEFAULT.resolutionDepth)
    }

    @Test
    fun `DEFAULT equals a freshly built options`() {
        assertEquals(ParseOptions.DEFAULT, ParseOptions.Builder().build())
        assertEquals(ParseOptions.DEFAULT.hashCode(), ParseOptions.Builder().build().hashCode())
    }

    @Test
    fun `builder overrides inputLength`() {
        val options = ParseOptions.Builder().inputLength(100).build()

        assertEquals(100, options.inputLength)
    }

    @Test
    fun `builder overrides expandedLength independently of inputLength`() {
        val options =
            ParseOptions
                .Builder()
                .inputLength(100)
                .expandedLength(200)
                .build()

        assertEquals(100, options.inputLength)
        assertEquals(200, options.expandedLength)
    }

    @Test
    fun `expandedLength stays at its own default when only inputLength is overridden`() {
        val options = ParseOptions.Builder().inputLength(100).build()

        assertEquals(100, options.inputLength)
        assertEquals(ResourceLimit.ExpandedLength.defaultMax, options.expandedLength)
    }

    @Test
    fun `builder overrides pathSegments`() {
        val options = ParseOptions.Builder().pathSegments(5).build()

        assertEquals(5, options.pathSegments)
    }

    @Test
    fun `builder overrides resolutionDepth`() {
        val options = ParseOptions.Builder().resolutionDepth(16).build()

        assertEquals(16, options.resolutionDepth)
    }

    @Test
    fun `builder rejects a non-positive inputLength`() {
        assertFailsWith<IllegalArgumentException> { ParseOptions.Builder().inputLength(0) }
    }

    @Test
    fun `builder rejects a non-positive expandedLength`() {
        assertFailsWith<IllegalArgumentException> { ParseOptions.Builder().expandedLength(-1) }
    }

    @Test
    fun `builder rejects a non-positive pathSegments`() {
        assertFailsWith<IllegalArgumentException> { ParseOptions.Builder().pathSegments(0) }
    }

    @Test
    fun `builder rejects a non-positive resolutionDepth`() {
        assertFailsWith<IllegalArgumentException> { ParseOptions.Builder().resolutionDepth(0) }
    }

    @Test
    fun `newBuilder round-trips every overridden limit`() {
        val original =
            ParseOptions
                .Builder()
                .inputLength(100)
                .expandedLength(200)
                .pathSegments(5)
                .resolutionDepth(16)
                .build()

        val copy = original.newBuilder().build()

        assertEquals(original, copy)
        assertEquals(original.hashCode(), copy.hashCode())
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
    fun `an options carries no hidden state beyond its five fields`() {
        // Regression guard against the dropped expandedLength-tracking: a value built directly is
        // indistinguishable from one reached through a different newBuilder history, as long as the
        // five observable fields end up equal. Overriding inputLength must not silently drag
        // expandedLength along, so both of these keep expandedLength at its own default.
        val direct = ParseOptions.Builder().inputLength(100).build()
        val viaHistory =
            ParseOptions
                .Builder()
                .inputLength(500)
                .build()
                .newBuilder()
                .inputLength(100)
                .build()

        assertEquals(direct, viaHistory)
        assertEquals(direct.hashCode(), viaHistory.hashCode())
        assertEquals(ResourceLimit.ExpandedLength.defaultMax, direct.expandedLength)
        assertEquals(ResourceLimit.ExpandedLength.defaultMax, viaHistory.expandedLength)
    }

    @Test
    fun `equals and hashCode fold over every field`() {
        val on = ParseOptions.Builder().allowIpv6ZoneId(true).build()
        val onAgain = ParseOptions.Builder().allowIpv6ZoneId(true).build()
        val off = ParseOptions.Builder().allowIpv6ZoneId(false).build()
        val loweredPathSegments = ParseOptions.Builder().pathSegments(5).build()
        val loweredInputLength = ParseOptions.Builder().inputLength(100).build()
        val raisedExpandedLength = ParseOptions.Builder().expandedLength(200_000).build()
        val loweredResolutionDepth = ParseOptions.Builder().resolutionDepth(16).build()

        assertEquals(on, onAgain)
        assertEquals(on.hashCode(), onAgain.hashCode())
        assertNotEquals(on, off)
        assertEquals(ParseOptions.DEFAULT, off)
        assertNotEquals(ParseOptions.DEFAULT, loweredPathSegments)
        assertNotEquals(ParseOptions.DEFAULT, loweredInputLength)
        assertNotEquals(ParseOptions.DEFAULT, raisedExpandedLength)
        assertNotEquals(ParseOptions.DEFAULT, loweredResolutionDepth)
    }

    @Test
    fun `equals rejects a foreign type`() {
        assertNotEquals<Any?>(ParseOptions.DEFAULT, ParseOptions.DEFAULT.toString())
    }

    @Test
    fun `toString names every field and its value`() {
        val enabled = ParseOptions.Builder().allowIpv6ZoneId(true).build()

        assertEquals(
            "ParseOptions(allowIpv6ZoneId=false, inputLength=65536, expandedLength=65536, " +
                "pathSegments=10000, resolutionDepth=256)",
            ParseOptions.DEFAULT.toString(),
        )
        assertEquals(
            "ParseOptions(allowIpv6ZoneId=true, inputLength=65536, expandedLength=65536, " +
                "pathSegments=10000, resolutionDepth=256)",
            enabled.toString(),
        )
    }
}
