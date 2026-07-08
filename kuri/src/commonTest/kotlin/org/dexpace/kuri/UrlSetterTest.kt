/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri

import kotlin.test.Test
import kotlin.test.assertEquals

class UrlSetterTest {
    private fun url(s: String): Url = Url.parseOrThrow(s)

    @Test
    fun `withProtocol changes a special scheme to another special scheme`() {
        assertEquals("https://example.net/", url("http://example.net/").withProtocol("https").href)
    }

    @Test
    fun `withProtocol is a no-op when crossing the special boundary`() {
        val original = url("http://example.net/")
        assertEquals(original.href, original.withProtocol("fake").href)
    }

    @Test
    fun `withUsername percent-encodes and sets the user`() {
        assertEquals("wss://me@example.org/", url("wss://example.org/").withUsername("me").href)
    }

    @Test
    fun `withUsername is a no-op when the url has no host`() {
        val original = url("mailto:x@example.com")
        assertEquals(original.href, original.withUsername("me").href)
    }

    @Test
    fun `withPassword sets the password`() {
        assertEquals("wss://:pw@example.org/", url("wss://example.org/").withPassword("pw").href)
    }
}
