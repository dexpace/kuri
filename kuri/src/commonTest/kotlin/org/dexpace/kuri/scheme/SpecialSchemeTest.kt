/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.scheme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** Registry tests for [SpecialScheme] against SPEC §6.1 Table 6-1. */
class SpecialSchemeTest {
    @Test
    fun `fromName resolves every registry entry by its canonical name`() {
        SpecialScheme.entries.forEach { entry ->
            assertSame(entry, SpecialScheme.fromName(entry.schemeName))
        }
    }

    @Test
    fun `fromName resolves each special scheme name explicitly per SCH-1`() {
        assertSame(SpecialScheme.HTTP, SpecialScheme.fromName("http"))
        assertSame(SpecialScheme.HTTPS, SpecialScheme.fromName("https"))
        assertSame(SpecialScheme.WS, SpecialScheme.fromName("ws"))
        assertSame(SpecialScheme.WSS, SpecialScheme.fromName("wss"))
        assertSame(SpecialScheme.FTP, SpecialScheme.fromName("ftp"))
        assertSame(SpecialScheme.FILE, SpecialScheme.fromName("file"))
    }

    @Test
    fun `fromName returns null for an unknown scheme`() {
        assertNull(SpecialScheme.fromName("mailto"))
        assertNull(SpecialScheme.fromName("foo"))
    }

    @Test
    fun `fromName is case-sensitive and expects normalized input per SCH-3`() {
        assertNull(SpecialScheme.fromName("HTTP"))
    }

    @Test
    fun `fromName returns null for an empty scheme`() {
        assertNull(SpecialScheme.fromName(""))
    }

    @Test
    fun `default ports match Table 6-1 per SCH-2`() {
        assertEquals(21, SpecialScheme.FTP.defaultPort)
        assertEquals(80, SpecialScheme.HTTP.defaultPort)
        assertEquals(443, SpecialScheme.HTTPS.defaultPort)
        assertEquals(80, SpecialScheme.WS.defaultPort)
        assertEquals(443, SpecialScheme.WSS.defaultPort)
        assertNull(SpecialScheme.FILE.defaultPort)
    }

    @Test
    fun `registry contains exactly six special schemes per SCH-1`() {
        assertEquals(6, SpecialScheme.entries.size)
    }
}
