/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.bind

/**
 * Thrown when an object cannot be bound: a misconfigured annotation set, an unconvertible value, a
 * strict-mode conflict, a template validation failure, or a depth/cycle overflow. Binder misuse is a
 * programmer error, mirroring `Url.Builder.build()`'s use of the `IllegalArgumentException` family.
 *
 * @property path the member/hole path at which binding failed, when known (e.g. `endpoint.host`).
 */
public class KuriBindException internal constructor(
    message: String,
    public val path: String? = null,
) : IllegalArgumentException(if (path == null) message else "$message (at $path)")
