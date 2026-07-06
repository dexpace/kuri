/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.bind.internal

import org.dexpace.kuri.bind.Fragment
import org.dexpace.kuri.bind.Host
import org.dexpace.kuri.bind.KuriBindException
import org.dexpace.kuri.bind.Password
import org.dexpace.kuri.bind.Path
import org.dexpace.kuri.bind.Port
import org.dexpace.kuri.bind.Profile
import org.dexpace.kuri.bind.Query
import org.dexpace.kuri.bind.QueryMap
import org.dexpace.kuri.bind.Scheme
import org.dexpace.kuri.bind.Url
import org.dexpace.kuri.bind.UserInfo
import org.dexpace.kuri.bind.Username
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private class Flat(
    @Scheme val s: String,
    @Host val h: String,
    @Port val p: Int,
)

private class TwoAnnotations(
    @Host @Port val x: String,
)

// Exercises every remaining component annotation (all leaf ops plus a sub-URL recurse) so the
// full stepFor mapping is compiled and asserted in one pass. Declared as a data class so its wide
// constructor is exempt from the long-parameter-list check (fixtures carry one field per component).
private data class AllComponents(
    @UserInfo val ui: String,
    @Username val user: String,
    @Password val pass: String,
    @Fragment val frag: String,
    @Path val seg: String,
    @Query val q: String,
    @QueryMap val qm: Map<String, String>,
    @Url val base: String,
)

class PlanCompilerFlatTest {
    private val compiler = PlanCompiler(KotlinReflectMemberScanner())

    @Test
    fun `compiles leaf steps in constructor order`() {
        val plan = compiler.compile(Flat::class, Profile.URL)
        val ops = plan.steps.filterIsInstance<BindStep.Leaf>().map { it.op }
        assertEquals(listOf(LeafOp.SCHEME, LeafOp.HOST, LeafOp.PORT), ops)
    }

    @Test
    fun `rejects more than one component annotation on a member`() {
        assertFailsWith<KuriBindException> { compiler.compile(TwoAnnotations::class, Profile.URL) }
    }

    @Test
    fun `compiles a step for every component annotation`() {
        val plan = compiler.compile(AllComponents::class, Profile.URL)
        val leafOps = plan.steps.filterIsInstance<BindStep.Leaf>().map { it.op }
        assertEquals(
            listOf(
                LeafOp.USERINFO,
                LeafOp.USERNAME,
                LeafOp.PASSWORD,
                LeafOp.FRAGMENT,
                LeafOp.PATH,
                LeafOp.QUERY,
                LeafOp.QUERY_MAP,
            ),
            leafOps,
        )
        val recurse = plan.steps.filterIsInstance<BindStep.Recurse>().single()
        assertEquals(Scope.MERGE, recurse.scope)
    }
}
