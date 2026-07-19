/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.bind.internal

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A thread-safe, fixed-capacity least-recently-used cache.
 *
 * [PlanCompiler] and [KotlinReflectMemberScanner] key their reflective caches by `KClass`, which
 * strongly retains its backing `java.lang.Class` and, through that, the defining `ClassLoader`. An
 * unbounded cache pins every distinct type ever looked up — and its `ClassLoader` — for the life of the
 * process; in a host that mints classes dynamically (per-deployment loaders, hot code redeploy,
 * bytecode-generated proxies) that retention grows without bound. Capping the cache at [maxSize]
 * entries bounds that retention: once full, inserting a new entry evicts the least-recently-used one,
 * so a churning stream of distinct keys only ever pins the most recently used [maxSize] of them.
 *
 * Backed by a single [LinkedHashMap] in access order, guarded by a [ReentrantLock] rather than
 * `synchronized` (the project avoids `synchronized` because it pins carrier threads under Loom); every
 * operation runs in amortized O(1) under the lock.
 */
internal class BoundedCache<K : Any, V : Any>(
    private val maxSize: Int,
) {
    init {
        require(maxSize > 0) { "maxSize must be positive, was $maxSize" }
    }

    private val lock = ReentrantLock()

    // `accessOrder = true` reorders an entry to most-recently-used on every `get`/`put`, so
    // `removeEldestEntry` evicting once the map grows past `maxSize` always drops the
    // least-recently-used entry rather than merely the oldest-inserted one.
    private val delegate =
        object : LinkedHashMap<K, V>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean = size > maxSize
        }

    /**
     * Returns the cached value for [key], computing it via [compute] and caching the result on a miss.
     *
     * A concurrent miss on the same [key] may run [compute] more than once: the lock is released while
     * [compute] runs, so two racing callers can each compute before either one inserts, and only one
     * computed result survives in the cache. Every caller in this module compiles/scans a type as a
     * pure function of that type, so the discarded result is equal to the one that wins and the race is
     * harmless — see [PlanCompiler.planFor] and [KotlinReflectMemberScanner.scan].
     *
     * @param key the cache key.
     * @param compute produces the value for [key] on a cache miss; runs with the lock released (see
     *   above), so a reentrant call back into this cache for a different key — or a concurrent call on
     *   another thread — is safe and cannot deadlock. Recursing back into [getOrPut] for the SAME [key]
     *   from within [compute] is still unsafe, though not by deadlocking: [key] is only inserted after
     *   [compute] returns, so the recursive call also misses and invokes [compute] again, causing
     *   unbounded recursion rather than reusing an in-flight result.
     * @return the cached or freshly computed value for [key].
     */
    fun getOrPut(
        key: K,
        compute: (K) -> V,
    ): V {
        lock.withLock { delegate[key] }?.let { return it }
        val computed = compute(key)
        return lock.withLock { delegate.getOrPut(key) { computed } }
    }

    /** The number of entries currently held; exposed so tests can assert the size bound holds. */
    val size: Int
        get() = lock.withLock { delegate.size }

    private companion object {
        const val INITIAL_CAPACITY = 16
        const val LOAD_FACTOR = 0.75f
    }
}
