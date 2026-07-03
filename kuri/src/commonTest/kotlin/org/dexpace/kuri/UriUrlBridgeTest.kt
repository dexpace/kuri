/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class UriUrlBridgeTest {
    private fun uri(input: String): Uri = Uri.parse(input).getOrNull() ?: fail("expected $input to parse as Uri")

    private fun url(input: String): Url = Url.parse(input).getOrNull() ?: fail("expected $input to parse as Url")

    @Test
    fun `toUrl succeeds for an http uri`() {
        val converted = uri("http://h/a/b").toUrl().getOrNull() ?: fail("expected a Url")

        assertEquals("http://h/a/b", converted.href)
    }

    @Test
    fun `toUrl fails for a relative reference that is not a valid url`() {
        assertFalse(uri("a/b/c").toUrl().isOk())
    }

    @Test
    fun `toUri round-trips a parsed url`() {
        val source = url("https://user:pass@example.com:8443/path?q=1#frag")

        val bridged = source.toUri()

        assertEquals(source.href, bridged.uriString)
        assertEquals("https", bridged.scheme)
        assertEquals("user:pass", bridged.userInfo)
        assertEquals(8443, bridged.port)
    }

    @Test
    fun `toUri then toUrl round-trips back to the original url`() {
        val source = url("https://example.com/a/b?q=1#f")

        val roundTripped = source.toUri().toUrl().getOrNull() ?: fail("expected a Url")

        assertEquals(source, roundTripped)
    }

    @Test
    fun `toUrl preserves the canonical authority of an http uri`() {
        val converted = uri("http://u@h:8080/p").toUrl().getOrNull() ?: fail("expected a Url")

        assertTrue(converted.href.startsWith("http://u@h:8080/p"))
    }
}
