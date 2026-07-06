/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.bind

import org.dexpace.kuri.Url
import kotlin.test.Test
import kotlin.test.assertEquals

class SmokeTest {
    @Test
    fun `kuri-bind module can depend on and use kuri`() {
        val url = Url.parseOrThrow("https://example.com")
        assertEquals("example.com", url.host?.asText())
    }
}
