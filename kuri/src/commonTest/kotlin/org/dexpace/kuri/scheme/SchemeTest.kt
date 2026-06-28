/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.scheme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Behavioural tests for [Scheme] against SPEC §6.2–§6.4 and §13.3 category B. */
class SchemeTest {
    @Test
    fun `accepts lower-case alpha scheme`() {
        assertTrue(Scheme.isValidScheme("http"))
    }

    @Test
    fun `accepts upper-case scheme as valid per SCH-7`() {
        assertTrue(Scheme.isValidScheme("HTTP"))
    }

    @Test
    fun `accepts single-letter scheme`() {
        assertTrue(Scheme.isValidScheme("a"))
    }

    @Test
    fun `accepts plus minus and dot in the tail per SCH-8`() {
        assertTrue(Scheme.isValidScheme("ht+tp"))
        assertTrue(Scheme.isValidScheme("ht-tp"))
        assertTrue(Scheme.isValidScheme("ht.tp"))
    }

    @Test
    fun `accepts digits in the tail`() {
        assertTrue(Scheme.isValidScheme("a1b2"))
    }

    @Test
    fun `rejects empty scheme per SCH-10`() {
        assertFalse(Scheme.isValidScheme(""))
    }

    @Test
    fun `rejects leading digit per SCH-7`() {
        assertFalse(Scheme.isValidScheme("1abc"))
    }

    @Test
    fun `rejects leading plus minus or dot per SCH-7`() {
        assertFalse(Scheme.isValidScheme("+http"))
        assertFalse(Scheme.isValidScheme("-http"))
        assertFalse(Scheme.isValidScheme(".http"))
    }

    @Test
    fun `rejects embedded space`() {
        assertFalse(Scheme.isValidScheme("ht tp"))
    }

    @Test
    fun `rejects embedded slash`() {
        assertFalse(Scheme.isValidScheme("ht/tp"))
    }

    @Test
    fun `rejects percent sign per SCH-9`() {
        assertFalse(Scheme.isValidScheme("ht%tp"))
    }

    @Test
    fun `rejects non-ascii code point per SCH-16`() {
        assertFalse(Scheme.isValidScheme("httpé"))
    }

    @Test
    fun `normalize lower-cases ascii letters per SCH-15`() {
        assertEquals("http", Scheme.normalize("HTTP"))
        assertEquals("https", Scheme.normalize("Https"))
        assertEquals("wss", Scheme.normalize("WsS"))
    }

    @Test
    fun `normalize leaves already-lower-case scheme unchanged`() {
        assertEquals("http", Scheme.normalize("http"))
    }

    @Test
    fun `normalize preserves digits and tail punctuation`() {
        assertEquals("ht+t-p.2", Scheme.normalize("HT+T-P.2"))
    }

    @Test
    fun `isSpecial is true for the six special schemes per SCH-1`() {
        listOf("http", "https", "ws", "wss", "ftp", "file").forEach { scheme ->
            assertTrue(Scheme.isSpecial(scheme), "expected $scheme to be special")
        }
    }

    @Test
    fun `isSpecial matches case-insensitively per SCH-3`() {
        assertTrue(Scheme.isSpecial("HTTP"))
        assertTrue(Scheme.isSpecial("FILE"))
    }

    @Test
    fun `isSpecial is false for non-special schemes`() {
        assertFalse(Scheme.isSpecial("mailto"))
        assertFalse(Scheme.isSpecial("foo"))
    }

    @Test
    fun `isSpecial is false for near-misses per SCH-3`() {
        assertFalse(Scheme.isSpecial("http2"))
        assertFalse(Scheme.isSpecial("xhttp"))
    }

    @Test
    fun `defaultPort returns Table 6-1 ports for special schemes per SCH-18`() {
        assertEquals(80, Scheme.defaultPort("http"))
        assertEquals(443, Scheme.defaultPort("https"))
        assertEquals(80, Scheme.defaultPort("ws"))
        assertEquals(443, Scheme.defaultPort("wss"))
        assertEquals(21, Scheme.defaultPort("ftp"))
    }

    @Test
    fun `defaultPort is null for file per SCH-2`() {
        assertNull(Scheme.defaultPort("file"))
    }

    @Test
    fun `defaultPort is null for non-special scheme per SCH-25`() {
        assertNull(Scheme.defaultPort("foo"))
    }

    @Test
    fun `defaultPort normalizes case before lookup per SCH-3`() {
        assertEquals(80, Scheme.defaultPort("HTTP"))
    }
}
