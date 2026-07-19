/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.error

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for the [ResourceLimit] registry (SPEC §12.6): every variant carries the exact documented
 * default, and the registry enumerates exactly the seven limits section 12.6 declares.
 */
internal class ResourceLimitTest {
    @Test
    fun `each limit carries its SPEC section 12_6 documented default`() {
        // arrange
        val expected =
            mapOf(
                ResourceLimit.InputLength to 65_536L,
                ResourceLimit.ExpandedLength to 65_536L,
                ResourceLimit.PathSegments to 10_000L,
                ResourceLimit.HostLabelLength to 63L,
                ResourceLimit.HostTotalLength to 253L,
                ResourceLimit.PortMax to 65_535L,
                ResourceLimit.ResolutionDepth to 256L,
            )

        // act + assert
        for ((limit, defaultMax) in expected) {
            assertEquals(defaultMax, limit.defaultMax, "unexpected default for $limit")
        }
    }

    @Test
    fun `the registry enumerates exactly the seven SPEC section 12_6 limits`() {
        // arrange + act + assert: adding or removing a variant is an intentional, API-visible change
        assertEquals(7, ResourceLimit.entries.size)
    }
}
