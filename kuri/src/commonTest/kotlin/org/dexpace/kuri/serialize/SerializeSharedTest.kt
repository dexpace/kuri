/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.serialize

import org.dexpace.kuri.parser.ParsedComponents
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Tests for the shared authority serializer used by both the WHATWG URL and RFC 3986 URI
 * recomposition paths.
 */
internal class SerializeSharedTest {
    @Test
    fun `serializeAuthority rejects components with no host`() {
        // Authority serialization requires a non-null host; callers only reach it behind that guard.
        assertFailsWith<IllegalArgumentException> { serializeAuthority(ParsedComponents(host = null)) }
    }
}
