/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.bind.internal

import org.dexpace.kuri.bind.Host
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame

// Five distinct types so a scanner capped at four cached entries is guaranteed to evict at least one.
private class ScanTarget1(
    @Host val h: String,
)

private class ScanTarget2(
    @Host val h: String,
)

private class ScanTarget3(
    @Host val h: String,
)

private class ScanTarget4(
    @Host val h: String,
)

private class ScanTarget5(
    @Host val h: String,
)

class MemberScannerCacheTest {
    @Test
    fun `scan cache is bounded and recomputes for a type evicted by newer lookups`() {
        val scanner = KotlinReflectMemberScanner(maxCacheSize = 4)

        val first = scanner.scan(ScanTarget1::class)
        // Repeated lookups of the same cached type return the exact same instance (a cache hit).
        assertSame(first, scanner.scan(ScanTarget1::class))

        // Scanning four more distinct types past the cap evicts ScanTarget1 (least recently used).
        scanner.scan(ScanTarget2::class)
        scanner.scan(ScanTarget3::class)
        scanner.scan(ScanTarget4::class)
        scanner.scan(ScanTarget5::class)

        // The cache is bounded: ScanTarget1's list had to be recomputed (a new instance), proving the
        // earlier entry was evicted rather than retained forever.
        val recomputed = scanner.scan(ScanTarget1::class)
        assertNotSame(first, recomputed)
    }
}
