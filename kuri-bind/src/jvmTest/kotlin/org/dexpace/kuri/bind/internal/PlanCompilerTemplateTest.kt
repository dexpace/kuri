/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.bind.internal

import org.dexpace.kuri.bind.KuriBindException
import org.dexpace.kuri.bind.Path
import org.dexpace.kuri.bind.Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.dexpace.kuri.bind.PathTemplate as PathTemplateAnn

@PathTemplateAnn("/users/{id}/files/{path...}")
private class Templated(
    @Path("id") val id: String,
    @Path("path") val path: List<String>,
)

private class FilesProvider(
    @Path("path") val path: List<String>,
)

// The `{path...}` hole is filled by a `@Path` on a merged sub-object, not on the root — this
// exercises provider resolution across the merge scope.
@PathTemplateAnn("/users/{id}/files/{path...}")
private class TemplatedAcrossMerge(
    @Path("id") val id: String,
    @Url val files: FilesProvider,
)

@PathTemplateAnn("/users/{id}")
private class MissingHole(
    @Path("other") val other: String,
)

@PathTemplateAnn("/users/{id}")
private class DuplicateProvider(
    @Path("id") val first: String,
    @Path("id") val second: String,
)

@PathTemplateAnn("/users/{id}")
private class UnknownProvider(
    @Path("id") val id: String,
    @Path("extra") val extra: String,
)

@PathTemplateAnn("/users/{id}")
private class UnnamedInTemplate(
    @Path val id: String,
)

// A merge sub-object providing the single hole `{id}`. Two same-typed siblings of this type both
// provide `{id}`, which must be detected as an ambiguous (multiple) provider rather than deduped.
private class Wing(
    @Path("id") val id: String,
)

@PathTemplateAnn("/x/{id}")
private class TwoSameTypedWings(
    @Url val left: Wing,
    @Url val right: Wing,
)

class PlanCompilerTemplateTest {
    private val compiler = PlanCompiler(KotlinReflectMemberScanner())

    @Test
    fun `resolves holes to named providers`() {
        val plan = compiler.compile(Templated::class)
        val template = assertNotNull(plan.template)
        assertEquals("/users/{id}/files/{path...}", rebuild(template))
        val holes = plan.steps.filterIsInstance<BindStep.Hole>().map { it.name }
        assertEquals(listOf("id", "path"), holes.sorted())
    }

    @Test
    fun `resolves a hole provided by a merged sub-object`() {
        val plan = compiler.compile(TemplatedAcrossMerge::class)
        assertNotNull(plan.template)
        // Only `{id}` is provided directly on the root; `{path}` comes from the merged FilesProvider.
        val rootHoles = plan.steps.filterIsInstance<BindStep.Hole>().map { it.name }
        assertEquals(listOf("id"), rootHoles)
    }

    @Test
    fun `fails when a hole has no provider`() {
        val failure = assertFailsWith<KuriBindException> { compiler.compile(MissingHole::class) }
        assertTrue(failure.message.orEmpty().contains("no @Path provider"))
    }

    @Test
    fun `fails when a hole has more than one provider`() {
        val failure = assertFailsWith<KuriBindException> { compiler.compile(DuplicateProvider::class) }
        assertTrue(failure.message.orEmpty().contains("multiple @Path providers"))
    }

    @Test
    fun `fails when two same-typed merge siblings both provide one hole`() {
        // The type-cycle guard must be per-branch: both `Wing` siblings provide `{id}`, so the
        // bijection must see two providers and reject the ambiguity rather than dedup by type.
        val failure = assertFailsWith<KuriBindException> { compiler.compile(TwoSameTypedWings::class) }
        assertTrue(failure.message.orEmpty().contains("multiple @Path providers"))
    }

    @Test
    fun `fails when a named @Path matches no hole`() {
        val failure = assertFailsWith<KuriBindException> { compiler.compile(UnknownProvider::class) }
        assertTrue(failure.message.orEmpty().contains("extra"))
    }

    @Test
    fun `fails on an unnamed @Path in template mode`() {
        val failure = assertFailsWith<KuriBindException> { compiler.compile(UnnamedInTemplate::class) }
        assertTrue(failure.message.orEmpty().contains("must name a hole"))
    }

    private fun rebuild(t: PathTemplate): String =
        t.tokens.joinToString("") { tk ->
            when (tk) {
                is PathToken.Literal -> tk.raw
                is PathToken.Hole -> if (tk.catchAll) "{${tk.name}...}" else "{${tk.name}}"
            }
        }
}
