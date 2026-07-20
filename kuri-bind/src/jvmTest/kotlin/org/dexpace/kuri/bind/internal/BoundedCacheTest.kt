/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.bind.internal

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
    fun `a concurrent miss on the same key computes for every racer but retains only one result`() {
        val cache = BoundedCache<Int, String>(maxSize = 4)
        val threads = 16
        val results = arrayOfNulls<String>(threads)
        val computeCount = AtomicInteger(0)
        val startBarrier = CyclicBarrier(threads)
        // Gates every compute call until all `threads` racers have missed the cache and entered compute,
        // so none can finish and insert before the rest have raced past the first check — otherwise a
        // scheduling fluke could serialize the callers and the double-checked-locking discard path
        // (BoundedCache.getOrPut's second `lock.withLock` re-check) would never actually be exercised.
        val allComputingBarrier = CyclicBarrier(threads)
        val workers =
            (0 until threads).map { index ->
                Thread {
                    startBarrier.await()
                    results[index] =
                        cache.getOrPut(42) {
                            computeCount.incrementAndGet()
                            allComputingBarrier.await()
                            "value-$index-${System.nanoTime()}"
                        }
                }
            }
        workers.forEach { it.start() }
        workers.forEach { it.join() }

        assertEquals(threads, computeCount.get(), "every racing caller must have missed the cache and computed")
        val distinctResults = results.toSet()
        assertEquals(1, distinctResults.size, "all racing callers must observe the same winning value")
        assertSame(results[0], cache.getOrPut(42) { "should not run" })
    }

    @Test
    fun `concurrent inserts of distinct keys past the cap evict safely without exceeding the max size`() {
        val maxSize = 4
        val cache = BoundedCache<Int, String>(maxSize)
        val threads = 16
        val keysPerThread = 64
        val startBarrier = CyclicBarrier(threads)
        // A worker thread's own AssertionError/exception is never seen by the JUnit-driving main thread
        // unless caught and relayed explicitly, so every failure is queued here and asserted after all
        // workers join rather than asserted from inside the worker.
        val failures = ConcurrentLinkedQueue<Throwable>()
        val workers =
            (0 until threads).map { threadIndex ->
                Thread {
                    try {
                        startBarrier.await()
                        for (key in threadIndex until threads * keysPerThread step threads) {
                            cache.getOrPut(key) { "value-$it" }
                            check(cache.size <= maxSize) { "cache size ${cache.size} exceeded max $maxSize" }
                        }
                    } catch (t: Throwable) {
                        failures.add(t)
                    }
                }
            }
        workers.forEach { it.start() }
        workers.forEach { it.join() }

        assertTrue(failures.isEmpty(), "worker threads recorded failures: $failures")
        assertEquals(maxSize, cache.size)
    }

    @Test
    fun `compute for one key does not block a concurrent getOrPut for a different key`() {
        val cache = BoundedCache<Int, String>(maxSize = 4)
        val enteredCompute = CountDownLatch(1)
        val releaseCompute = CountDownLatch(1)
        var firstResult: String? = null

        val blockedComputation =
            Thread {
                firstResult =
                    cache.getOrPut(1) {
                        enteredCompute.countDown()
                        releaseCompute.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        "one"
                    }
            }
        blockedComputation.start()
        assertTrue(
            enteredCompute.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            "the blocked thread must have entered compute for key 1",
        )

        // Key 1's compute is still parked; a different key must not be blocked by its held reference to
        // the released lock, proving getOrPut's lock is not held across a caller's compute call.
        val second = cache.getOrPut(2) { "two" }
        assertEquals("two", second)

        releaseCompute.countDown()
        blockedComputation.join(TimeUnit.SECONDS.toMillis(AWAIT_TIMEOUT_SECONDS))
        assertEquals("one", firstResult)
    }

    private companion object {
        const val AWAIT_TIMEOUT_SECONDS = 5L
    }
}
