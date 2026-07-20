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
                ResourceLimit.InputLength to 65_536,
                ResourceLimit.ExpandedLength to 65_536,
                ResourceLimit.PathSegments to 10_000,
                ResourceLimit.HostLabelLength to 63,
                ResourceLimit.HostTotalLength to 253,
                ResourceLimit.PortMax to 65_535,
                ResourceLimit.ResolutionDepth to 256,
            )

        // act + assert
        for ((limit, defaultMax) in expected) {
            assertEquals(defaultMax, limit.defaultMax, "unexpected default for $limit")
        }
    }

    @Test
    fun `the registry enumerates exactly the seven SPEC section 12_6 limits in declared order`() {
        // arrange + act + assert: adding, removing, or reordering a variant is an intentional,
        // API-visible change -- assert the exact names and order, not merely the count.
        assertEquals(
            listOf(
                "InputLength",
                "ExpandedLength",
                "PathSegments",
                "HostLabelLength",
                "HostTotalLength",
                "PortMax",
                "ResolutionDepth",
            ),
            ResourceLimit.entries.map { it.name },
        )
    }
}
