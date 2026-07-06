/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class KuriVersionTest {
    @Test
    fun `VERSION is wired to the build-generated constant`() {
        // KURI_VERSION is emitted from project.version by the generateKuriVersion Gradle task; this
        // referencing it at all proves the generated source compiled and Kuri reads the same value.
        assertEquals(KURI_VERSION, Kuri.VERSION)
    }

    @Test
    fun `VERSION is never the stale hardcoded literal`() {
        // Regression guard: VERSION used to be a hand-maintained literal that silently drifted from the
        // published coordinate. Deriving it from project.version means it can never be this placeholder.
        assertNotEquals("0.1.0-SNAPSHOT", Kuri.VERSION)
        assertTrue(Kuri.VERSION.isNotBlank(), "VERSION must be non-blank")
    }

    @Test
    fun `VERSION has a semantic-version shape`() {
        // Assert the shape rather than a fixed value so the test survives every release-please bump.
        val semanticVersion = Regex("""^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$""")
        assertTrue(
            semanticVersion.matches(Kuri.VERSION),
            "VERSION should look like a semantic version but was '${Kuri.VERSION}'",
        )
    }
}
