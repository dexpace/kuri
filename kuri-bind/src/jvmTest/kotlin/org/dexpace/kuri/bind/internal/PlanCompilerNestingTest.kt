/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.bind.internal

import org.dexpace.kuri.bind.Host
import org.dexpace.kuri.bind.KuriBindException
import org.dexpace.kuri.bind.Path
import org.dexpace.kuri.bind.Query
import org.dexpace.kuri.bind.Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private class Filters(
    @Query("status") val status: String,
)

private class Endpoint(
    @Host val host: String,
)

private class Root(
    @Query val filters: Filters, // complex + @Query → scoped recursion
    @Url val endpoint: Endpoint, // complex + @Url    → merge
    @Query("q") val term: String, // scalar + @Query   → leaf
)

private class BadMap(
    @Query val m: Map<String, String>,
)

private class Coordinates(
    @Path val lat: String,
)

private class Located(
    @Path val at: Coordinates,
) // complex + @Path → PATH-scoped recursion

class PlanCompilerNestingTest {
    private val compiler = PlanCompiler(KotlinReflectMemberScanner())

    @Test
    fun `distinguishes scoped recursion merge and leaf`() {
        val steps = compiler.compile(Root::class).steps
        val filters = steps.first { it.member.name == "filters" }
        val endpoint = steps.first { it.member.name == "endpoint" }
        val term = steps.first { it.member.name == "term" }
        assertEquals(Scope.QUERY, (filters as BindStep.Recurse).scope)
        assertEquals(Scope.MERGE, (endpoint as BindStep.Recurse).scope)
        assertTrue(term is BindStep.Leaf)
    }

    @Test
    fun `recurses a complex @Path member under the PATH scope`() {
        val step = compiler.compile(Located::class).steps.single()
        assertTrue(step is BindStep.Recurse)
        assertEquals(Scope.PATH, (step as BindStep.Recurse).scope)
    }

    @Test
    fun `rejects a map under @Query`() {
        val failure = assertFailsWith<KuriBindException> { compiler.compile(BadMap::class) }
        assertEquals("m", failure.path)
        assertTrue(failure.message.orEmpty().contains("@QueryMap"))
    }
}
