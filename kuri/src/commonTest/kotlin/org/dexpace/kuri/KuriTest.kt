/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri

import kotlin.test.Test
import kotlin.test.assertTrue

class KuriTest {
    @Test
    fun versionIsNotBlank() {
        assertTrue(Kuri.VERSION.isNotBlank())
    }

    @Test
    fun versionHasDottedShape() {
        assertTrue(Kuri.VERSION.contains('.'), "the version string is dotted, e.g. 0.1.0-alpha.1")
    }
}
