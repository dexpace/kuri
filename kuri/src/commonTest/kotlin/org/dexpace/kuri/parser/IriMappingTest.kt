/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.parser

import org.dexpace.kuri.Uri
import org.dexpace.kuri.error.ParseResult
import org.dexpace.kuri.host.Host
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Structural tests for [IriMapping], the RFC 3987 §3.1 IRI-to-URI mapping. Covers the
 * authority-less split (a scheme with no host) and the plain host-with-port split, complementing the
 * host-centric coverage in the public `IriTest`. The non-ASCII path point is built from an explicit
 * code point so its stored form is deterministic.
 */
internal class IriMappingTest {
    // U+00E9 é — a precomposed NFC scalar, built explicitly to pin its form regardless of the editor.
    private val eAcute: String = Char(0x00E9).toString()

    @Test
    fun `maps a scheme-only iri with no authority`() {
        val result = IriMapping.toUri("foo:b$eAcute")

        val uri = assertIs<ParseResult.Ok<Uri>>(result).value
        assertEquals("foo", uri.scheme)
        assertEquals(null, uri.host)
        assertEquals("b%C3%A9", uri.encodedPath)
    }

    @Test
    fun `maps a plain host with an explicit port`() {
        val result = IriMapping.toUri("http://host.example:8080/caf$eAcute")

        val uri = assertIs<ParseResult.Ok<Uri>>(result).value
        assertEquals(Host.RegName("host.example"), uri.host)
        assertEquals(8080, uri.port)
        assertEquals("/caf%C3%A9", uri.encodedPath)
    }
}
