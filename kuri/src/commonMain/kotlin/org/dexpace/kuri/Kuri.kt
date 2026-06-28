/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri

/**
 * Library metadata for kuri.
 *
 * This is a placeholder marker so the build, gates, and source sets have a real
 * public declaration to act on before any URI parsing API exists.
 */
public object Kuri {
    /** The current kuri library version. */
    public const val VERSION: String = "0.1.0-SNAPSHOT"

    /**
     * Returns the current library version.
     *
     * @return the [VERSION] string; never blank.
     */
    public fun version(): String = VERSION
}
