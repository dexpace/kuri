/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises the Java-facing static entry point that carries the [ParseOptions] opt-in
 * (SPEC §7.2.2, [HOST-18]): the `@JvmStatic`/`@JvmOverloads` `Uri.parse(input, options)` factory,
 * and the `ParseOptions.Builder` a Java caller uses. Kotlin here stands in for the Java call sites
 * the generated overloads must keep compiling and working. The `Url` profile is deliberately absent:
 * it accepts no `ParseOptions`, so there is no Java-facing zoned-`Url` factory to exercise ([HOST-17]).
 */
class ParseOptionsInteropTest {
    private val zoneOptions: ParseOptions = ParseOptions.Builder().allowIpv6ZoneId(true).build()

    @Test
    fun `Uri parse with options accepts a zoned authority`() {
        val uri = Uri.parse("//[fe80::1%25eth0]", zoneOptions).getOrThrow()

        assertEquals("//[fe80::1%25eth0]", uri.uriString)
    }

    @Test
    fun `the builder produces an enabled options value`() {
        val options = ParseOptions.Builder().allowIpv6ZoneId(true).build()

        assertTrue(options.allowIpv6ZoneId)
    }
}
