/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.bind

/**
 * Options controlling a bind.
 *
 * @property strict when `true`, a conflicting second write to a single-valued component is an error
 *   rather than being ignored (first-writer-wins otherwise).
 * @property maxDepth the recursion bound for the object/type graph (must be in `1..512`).
 */
public data class BindOptions
    @JvmOverloads
    constructor(
        val strict: Boolean = false,
        val maxDepth: Int = 64,
    ) {
        init {
            require(maxDepth in 1..MAX_DEPTH_LIMIT) { "maxDepth must be in 1..$MAX_DEPTH_LIMIT: $maxDepth" }
        }

        public companion object {
            internal const val MAX_DEPTH_LIMIT: Int = 512

            /** The default options: non-strict, depth 64. */
            @JvmField
            public val DEFAULT: BindOptions = BindOptions()
        }
    }
