/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KuriTest {
    @Test
    fun versionIsNotBlank() {
        assertTrue(Kuri.version().isNotBlank())
    }

    @Test
    fun versionMatchesConstant() {
        assertEquals(Kuri.VERSION, Kuri.version())
    }
}
