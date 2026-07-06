/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.bind

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnnotationsTest {
    @Url
    @PathTemplate("/x/{id}")
    private data class Sample(
        @Scheme val scheme: String,
        @Path("id") val id: String,
        @Query("q") val q: String,
    )

    @Test
    fun `component annotations retain at runtime with expected members`() {
        val ann = Sample::class.annotations
        assertTrue(ann.any { it is Url })
        val template = ann.filterIsInstance<PathTemplate>().single()
        assertEquals("/x/{id}", template.value)
    }

    @Test
    fun `query name defaults to empty for member-name fallback`() {
        assertEquals("", Query::class.java.getMethod("value").defaultValue)
    }
}
