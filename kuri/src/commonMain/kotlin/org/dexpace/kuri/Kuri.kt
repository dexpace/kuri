/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri

/**
 * Library metadata for kuri.
 *
 * kuri has no single facade: parse and manipulate web URLs with [Url] (the WHATWG URL Standard
 * profile) or generic URIs with [Uri] (the RFC 3986 profile). This object exists only to expose the
 * compiled-in [VERSION], so a consumer can report or log which release it is running against.
 *
 * @see Url
 * @see Uri
 */
public object Kuri {
    /**
     * The kuri library version, for example `0.1.0`.
     *
     * Generated at build time from the project's `version` (the single source in `gradle.properties`,
     * owned by release automation), so it always matches the published Maven coordinate and can never
     * drift from a hand-maintained literal.
     */
    public const val VERSION: String = KURI_VERSION
}
