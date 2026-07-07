/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.bind.internal

import kotlin.reflect.KClass

/** A single component operation for a leaf member. */
internal enum class LeafOp { SCHEME, USERINFO, USERNAME, PASSWORD, HOST, PORT, PATH, QUERY, QUERY_MAP, FRAGMENT }

/** The scope a complex member is recursed under. */
internal enum class Scope { MERGE, QUERY, PATH }

/** One step of a compiled [TypePlan]. */
internal sealed interface BindStep {
    val member: ScannedMember

    data class Leaf(
        override val member: ScannedMember,
        val op: LeafOp,
        val queryName: String?,
    ) : BindStep

    data class Recurse(
        override val member: ScannedMember,
        val scope: Scope,
    ) : BindStep

    data class Hole(
        override val member: ScannedMember,
        val name: String,
    ) : BindStep
}

/**
 * Reads a single template hole's value from the root target, following the merge chain that reaches
 * its `@Path` provider.
 *
 * The [accessor] composes root → merge-member reads → provider member, so a hole supplied by an
 * `@Url`/`@Uri`-merged child resolves without consulting that child's own (non-template) plan.
 * [catchAll] mirrors the template hole it fills: a catch-all expands a collection/slash-string into
 * many segments, whereas a single-segment hole must resolve to a scalar.
 */
internal class HoleProvider(
    val accessor: (Any) -> Any?,
    val catchAll: Boolean,
)

/** A compiled, validated plan for one type. */
internal class TypePlan(
    val type: KClass<*>,
    val steps: List<BindStep>,
    val template: PathTemplate?,
    val holeProviders: Map<String, HoleProvider>,
)
