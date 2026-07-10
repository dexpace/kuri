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
import org.dexpace.kuri.bind.Query
import org.dexpace.kuri.bind.QueryMap
import org.dexpace.kuri.bind.Scheme
import org.dexpace.kuri.bind.Url
import org.dexpace.kuri.bind.UserInfo
import org.dexpace.kuri.bind.Username
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private class Flat(
    @Scheme val s: String,
    @Host val h: String,
    @Port val p: Int,
)

private class TwoAnnotations(
    @Host @Port val x: String,
)

// A complex merge target: `@Url` requires a complex object, so the AllComponents fixture merges this
// rather than a scalar.
private class SubMerge(
    @Host val h: String,
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
    @Url val base: SubMerge,
)

// `@Url` on a scalar member: rejected at compile since a scalar has no components to merge.
private class ScalarMerge(
    @Url val base: String,
)

// `@QueryMap` on a non-map member: rejected at compile, mirroring `@Query` on a Map.
private class NonMapQueryMap(
    @QueryMap val notAMap: String,
)

// A non-binding annotation used to prove the compiler ignores annotations outside the marker set.
@Retention(AnnotationRetention.RUNTIME)
private annotation class NotBinding

// One member carries a component marker, another carries only a non-binding annotation: the latter
// must produce no bind step (its stray annotation is not a marker), leaving just the `@Host` leaf.
private class NonBindingMemberHolder(
    @Host val h: String,
    @NotBinding val note: String,
)

class PlanCompilerFlatTest {
    private val compiler = PlanCompiler(KotlinReflectMemberScanner())

    @Test
    fun `compiles leaf steps in constructor order`() {
        val plan = compiler.compile(Flat::class)
        val ops = plan.steps.filterIsInstance<BindStep.Leaf>().map { it.op }
        assertEquals(listOf(LeafOp.SCHEME, LeafOp.HOST, LeafOp.PORT), ops)
    }

    @Test
    fun `rejects more than one component annotation on a member`() {
        assertFailsWith<KuriBindException> { compiler.compile(TwoAnnotations::class) }
    }

    @Test
    fun `compiles a step for every component annotation`() {
        val plan = compiler.compile(AllComponents::class)
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

    @Test
    fun `rejects @Url on a scalar member`() {
        val failure = assertFailsWith<KuriBindException> { compiler.compile(ScalarMerge::class) }
        assertTrue(failure.message.orEmpty().contains("complex object"))
    }

    @Test
    fun `rejects @QueryMap on a non-map member at compile`() {
        val failure = assertFailsWith<KuriBindException> { compiler.compile(NonMapQueryMap::class) }
        assertTrue(failure.message.orEmpty().contains("@QueryMap requires a Map"))
    }

    @Test
    fun `skips a member carrying only a non-binding annotation`() {
        val plan = compiler.compile(NonBindingMemberHolder::class)
        val ops = plan.steps.filterIsInstance<BindStep.Leaf>().map { it.op }
        assertEquals(listOf(LeafOp.HOST), ops)
    }
}
