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

/**
 * Behavioural tests for the public [Schemes] facade over the internal [Scheme] engine (SPEC §6).
 * Verifies the default-port, special-ness, and validity lookups; exhaustive grammar coverage lives
 * in [SchemeTest].
 */
class SchemesTest {
    @Test
    fun `defaultPort returns the registered port for each special scheme`() {
        val ports = mapOf("http" to 80, "https" to 443, "ws" to 80, "wss" to 443, "ftp" to 21)
        ports.forEach { (scheme, port) ->
            assertEquals(port, Schemes.defaultPort(scheme), "wrong default port for $scheme")
        }
    }

    @Test
    fun `defaultPort is null for file and for non-special schemes`() {
        assertNull(Schemes.defaultPort("file"))
        assertNull(Schemes.defaultPort("mailto"))
        assertNull(Schemes.defaultPort("foo"))
    }

    @Test
    fun `defaultPort ignores case`() {
        assertEquals(443, Schemes.defaultPort("HTTPS"))
    }

    @Test
    fun `isSpecial is true for the six special schemes`() {
        listOf("ftp", "file", "http", "https", "ws", "wss").forEach { scheme ->
            assertTrue(Schemes.isSpecial(scheme), "expected $scheme to be special")
        }
    }

    @Test
    fun `isSpecial ignores case`() {
        assertTrue(Schemes.isSpecial("HTTP"))
    }

    @Test
    fun `isSpecial is false for non-special schemes and near-misses`() {
        assertFalse(Schemes.isSpecial("mailto"))
        assertFalse(Schemes.isSpecial("http2"))
        assertFalse(Schemes.isSpecial("foo"))
    }

    @Test
    fun `isValid accepts well-formed schemes`() {
        listOf("http", "a", "ht+tp", "ht-tp", "ht.tp", "a1b2", "HTTP").forEach { scheme ->
            assertTrue(Schemes.isValid(scheme), "expected $scheme to be valid")
        }
    }

    @Test
    fun `isValid rejects malformed schemes`() {
        listOf("", "1x", "+http", "ht tp", "ht/tp").forEach { scheme ->
            assertFalse(Schemes.isValid(scheme), "expected $scheme to be invalid")
        }
    }
}
