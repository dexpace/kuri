/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.template

/**
 * Thrown by [UriTemplate.parse] when a template string is syntactically malformed — an unterminated
 * `{`, an empty expression, a reserved operator (`=,!@|`), or an invalid prefix modifier.
 *
 * A template is normally a developer-authored constant, so malformation is a programmer error and
 * fails fast (the same posture as `kuri-bind`'s misconfiguration exception). For untrusted template
 * text use [UriTemplate.parseOrNull], which returns `null` instead of throwing. Expansion itself never
 * throws: an undefined variable simply contributes nothing, per RFC 6570.
 *
 * @property index the code-unit offset in the template where the defect was detected, or `-1` when not
 * localized to a single position.
 */
public class UriTemplateException internal constructor(
    message: String,
    public val index: Int = -1,
) : IllegalArgumentException(message)
