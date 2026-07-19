/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.bind

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class BindOptionsTest {
    @Test
    fun `default is non-strict with depth 64`() {
        assertFalse(BindOptions.DEFAULT.strict)
        assertEquals(64, BindOptions.DEFAULT.maxDepth)
    }

    @Test
    fun `rejects a maxDepth outside 1 to 512`() {
        assertFailsWith<IllegalArgumentException> { BindOptions(maxDepth = 0) }
        assertFailsWith<IllegalArgumentException> { BindOptions(maxDepth = 513) }
    }

    @Test
    fun `accepts a maxDepth of exactly 512`() {
        assertEquals(512, BindOptions(maxDepth = 512).maxDepth)
    }
}
