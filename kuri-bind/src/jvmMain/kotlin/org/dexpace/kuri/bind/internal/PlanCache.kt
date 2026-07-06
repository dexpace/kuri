/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.bind.internal

import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

/**
 * Compiles each type's [TypePlan] once and caches it, keyed by type; safe for concurrent binds.
 *
 * The cache is profile-agnostic: a plan describes both a `Url` and a `Uri` binding, since the profile
 * only narrows which components project later (see [PlanCompiler]). One shared cache therefore backs
 * both [ReflectiveUrlBinder] and [ReflectiveUriBinder], which differ only by the sink they project
 * through — so a type compiled for a URL bind is reused verbatim for a URI bind.
 */
internal class PlanCache(
    private val compiler: PlanCompiler,
) {
    private val plans = ConcurrentHashMap<KClass<*>, TypePlan>()

    /**
     * The compiled plan for [type], computing and caching it on first use (compile-once per type).
     *
     * @throws KuriBindException when [type] cannot be compiled into a valid plan (delegated to
     *   [PlanCompiler.compile]).
     */
    fun planFor(type: KClass<*>): TypePlan {
        require(type != Any::class) { "cannot bind Any" }
        val plan = plans.getOrPut(type) { compiler.compile(type) }
        check(plan.type == type) { "plan cache returned a plan for ${plan.type} under key $type" }
        return plan
    }
}
