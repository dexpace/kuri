/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.bind

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KuriBindExceptionTest {
    @Test
    fun `message is verbatim when no path`() {
        val ex = KuriBindException("bad value")
        assertEquals("bad value", ex.message)
        assertNull(ex.path)
    }

    @Test
    fun `message is path-qualified when a path is present`() {
        val ex = KuriBindException("bad value", "endpoint.host")
        assertEquals("bad value (at endpoint.host)", ex.message)
        assertEquals("endpoint.host", ex.path)
    }
}
