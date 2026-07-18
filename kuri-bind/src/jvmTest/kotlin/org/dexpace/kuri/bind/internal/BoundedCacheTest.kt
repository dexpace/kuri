/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.bind.internal

import java.util.concurrent.CyclicBarrier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BoundedCacheTest {
    @Test
    fun `rejects a non-positive max size`() {
        assertFailsWith<IllegalArgumentException> { BoundedCache<Int, String>(0) }
        assertFailsWith<IllegalArgumentException> { BoundedCache<Int, String>(-1) }
    }

    @Test
    fun `caches the computed value for repeated lookups of the same key`() {
        val cache = BoundedCache<Int, String>(maxSize = 4)
        var computations = 0
        val compute: (Int) -> String = { key ->
            computations++
            "value-$key"
        }

        val first = cache.getOrPut(1, compute)
        val second = cache.getOrPut(1, compute)

        assertEquals("value-1", first)
        assertSame(first, second)
        assertEquals(1, computations)
    }

    @Test
    fun `never grows past its configured max size`() {
        val maxSize = 8
        val cache = BoundedCache<Int, String>(maxSize)

        for (key in 0 until maxSize * 4) {
            cache.getOrPut(key) { "value-$it" }
            assertTrue(cache.size <= maxSize, "cache size ${cache.size} exceeded max $maxSize after key $key")
        }
        assertEquals(maxSize, cache.size)
    }

    @Test
    fun `evicts the least-recently-used entry once it grows past the max size`() {
        val cache = BoundedCache<Int, String>(maxSize = 3)
        cache.getOrPut(1) { "one" }
        cache.getOrPut(2) { "two" }
        cache.getOrPut(3) { "three" }

        // A fresh 4th key evicts key 1 (the least recently used), not 2 or 3.
        cache.getOrPut(4) { "four" }

        var recomputed = false
        val one =
            cache.getOrPut(1) {
                recomputed = true
                "one-recomputed"
            }
        assertTrue(recomputed, "expected the evicted key to be recomputed on its next lookup")
        assertEquals("one-recomputed", one)
    }

    @Test
    fun `touching an entry protects it from eviction ahead of an untouched older entry`() {
        val cache = BoundedCache<Int, String>(maxSize = 3)
        cache.getOrPut(1) { "one" }
        cache.getOrPut(2) { "two" }
        cache.getOrPut(3) { "three" }

        // Re-reading key 1 marks it most-recently-used, so inserting key 4 evicts key 2 instead.
        cache.getOrPut(1) { "one" }
        cache.getOrPut(4) { "four" }

        var oneRecomputed = false
        cache.getOrPut(1) {
            oneRecomputed = true
            "one-recomputed"
        }
        assertFalse(oneRecomputed, "key 1 should have survived eviction after being touched")

        var twoRecomputed = false
        cache.getOrPut(2) {
            twoRecomputed = true
            "two-recomputed"
        }
        assertTrue(twoRecomputed, "key 2 should have been evicted in favor of the touched key 1")
    }

    @Test
    fun `a concurrent miss on the same key may recompute but only one result is retained`() {
        val cache = BoundedCache<Int, String>(maxSize = 4)
        val threads = 16
        val results = arrayOfNulls<String>(threads)
        val barrier = CyclicBarrier(threads)
        val workers =
            (0 until threads).map { index ->
                Thread {
                    barrier.await()
                    results[index] = cache.getOrPut(42) { "value-$index-${System.nanoTime()}" }
                }
            }
        workers.forEach { it.start() }
        workers.forEach { it.join() }

        val distinctResults = results.toSet()
        assertEquals(1, distinctResults.size, "all racing callers must observe the same winning value")
        assertSame(results[0], cache.getOrPut(42) { "should not run" })
    }
}
