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
import org.dexpace.kuri.bind.Uri
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

// A `@Query` member typed `Double`: exercises the `Double` arm of the scalar-type table, so it compiles
// to a scalar QUERY leaf rather than recursing.
private class DoubleQuery(
    @Query("d") val d: Double,
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

// A merge member marked with `@Uri` rather than `@Url`: the compiler treats both markers as a MERGE
// step, so this exercises the `@Uri` arm of the marker dispatch alongside the `@Url` arm above.
private class UriMemberParent(
    @Uri val sub: SubMerge,
)

// `@Url` on a collection / map member: like a scalar, neither carries mergeable components, so both are
// rejected at compile.
private class UrlOnList(
    @Url val many: List<SubMerge>,
)

private class UrlOnMap(
    @Url val m: Map<String, SubMerge>,
)

// `@Query` collections whose element type is itself a collection or a map: the element is not a
// bindable object, so each member stays a scalar leaf rather than recursing.
private class NestedCollections(
    @Query("a") val a: List<List<String>>,
    @Query("b") val b: List<Map<String, String>>,
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

    @Test
    fun `compiles a @Uri merge member as a merge step`() {
        val recurse =
            compiler
                .compile(UriMemberParent::class)
                .steps
                .filterIsInstance<BindStep.Recurse>()
                .single()
        assertEquals(Scope.MERGE, recurse.scope)
    }

    @Test
    fun `rejects @Url on a collection member`() {
        val failure = assertFailsWith<KuriBindException> { compiler.compile(UrlOnList::class) }
        assertTrue(failure.message.orEmpty().contains("complex object"))
    }

    @Test
    fun `rejects @Url on a map member`() {
        val failure = assertFailsWith<KuriBindException> { compiler.compile(UrlOnMap::class) }
        assertTrue(failure.message.orEmpty().contains("complex object"))
    }

    @Test
    fun `rejects compiling Any`() {
        assertFailsWith<IllegalArgumentException> { compiler.compile(Any::class) }
    }

    @Test
    fun `treats a double query member as a scalar leaf`() {
        val step = compiler.compile(DoubleQuery::class).steps.single()
        assertTrue(step is BindStep.Leaf)
        assertEquals(LeafOp.QUERY, (step as BindStep.Leaf).op)
    }

    @Test
    fun `treats collections of collections and maps as leaf query members`() {
        val ops =
            compiler
                .compile(NestedCollections::class)
                .steps
                .filterIsInstance<BindStep.Leaf>()
                .map { it.op }
        assertEquals(listOf(LeafOp.QUERY, LeafOp.QUERY), ops)
    }
}
