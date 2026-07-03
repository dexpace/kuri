/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri

import org.dexpace.kuri.error.getOrThrow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises the Java-facing static entry points of the RFC 3987 facility [Iri]: the
 * `@JvmStatic` `Iri.toUri(String)` and `Iri.toUnicode(Uri)` methods a Java caller invokes as
 * `Iri.toUri(...)` / `Iri.toUnicode(...)`. Kotlin here stands in for those Java call sites.
 */
class IriInteropTest {
    // U+00E4 ä, built from its code point so the source file's encoding cannot alter its NFC form.
    private val aUmlaut: String = Char(0x00E4).toString()

    @Test
    fun `toUri maps a non-ascii host through the static entry point`() {
        val uri = Iri.toUri("http://$aUmlaut.example/").getOrThrow()

        assertTrue(uri.uriString.all { it.code < 0x80 }, "the mapped uri must be fully ASCII")
    }

    @Test
    fun `toUnicode renders a mapped uri back for display through the static entry point`() {
        val uri = Iri.toUri("http://$aUmlaut.example/").getOrThrow()

        assertEquals("http://$aUmlaut.example/", Iri.toUnicode(uri))
    }
}
