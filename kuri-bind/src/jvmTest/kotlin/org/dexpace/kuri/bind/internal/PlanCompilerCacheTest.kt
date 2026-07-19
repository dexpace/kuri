/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.bind.internal

import org.dexpace.kuri.bind.Host
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame

// Five distinct types so a compiler capped at four cached plans is guaranteed to evict at least one.
private class PlanTarget1(
    @Host val h: String,
)

private class PlanTarget2(
    @Host val h: String,
)

private class PlanTarget3(
    @Host val h: String,
)

private class PlanTarget4(
    @Host val h: String,
)

private class PlanTarget5(
    @Host val h: String,
)

class PlanCompilerCacheTest {
    @Test
    fun `plan cache is bounded and recompiles for a type evicted by newer lookups`() {
        val compiler = PlanCompiler(KotlinReflectMemberScanner(), maxCacheSize = 4)

        val first = compiler.planFor(PlanTarget1::class)
        // Repeated lookups of the same cached type return the exact same instance (a cache hit).
        assertSame(first, compiler.planFor(PlanTarget1::class))

        // Compiling four more distinct types past the cap evicts PlanTarget1 (least recently used).
        compiler.planFor(PlanTarget2::class)
        compiler.planFor(PlanTarget3::class)
        compiler.planFor(PlanTarget4::class)
        compiler.planFor(PlanTarget5::class)

        // The cache is bounded: PlanTarget1's plan had to be recompiled (a new instance), proving the
        // earlier entry was evicted rather than retained forever — the fix for kuri-bind#89.
        val recompiled = compiler.planFor(PlanTarget1::class)
        assertNotSame(first, recompiled)
    }
}
