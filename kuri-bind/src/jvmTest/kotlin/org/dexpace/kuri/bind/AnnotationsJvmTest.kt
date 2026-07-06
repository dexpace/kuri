/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.bind

import kotlin.test.Test
import kotlin.test.assertEquals

/** JVM-only checks that rely on `java.lang.reflect` (kept out of the platform-agnostic commonTest). */
class AnnotationsJvmTest {
    @Test
    fun `query name defaults to empty for member-name fallback`() {
        assertEquals("", Query::class.java.getMethod("value").defaultValue)
    }
}
