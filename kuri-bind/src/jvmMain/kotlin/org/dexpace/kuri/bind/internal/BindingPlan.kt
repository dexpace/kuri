/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.bind.internal

import kotlin.reflect.KClass

/** A single component operation for a leaf member. */
internal enum class LeafOp { SCHEME, USERINFO, USERNAME, PASSWORD, HOST, PORT, PATH, QUERY, QUERY_MAP, FRAGMENT }

/** The scope a complex member is recursed under. */
internal enum class Scope { MERGE, QUERY, PATH, QUERY_MAP }

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
        val queryName: String?,
    ) : BindStep

    data class Hole(
        override val member: ScannedMember,
        val name: String,
    ) : BindStep
}

/** A compiled, validated plan for one type. */
internal class TypePlan(
    val type: KClass<*>,
    val steps: List<BindStep>,
    val template: PathTemplate?,
)
