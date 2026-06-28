/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.host

import org.dexpace.kuri.text.isC0Control

/** U+007F DELETE, the lone non-C0 control added by the forbidden-domain set ([HOST-37]). */
private const val DELETE: Char = '\u007F'

/** U+0000 NULL, the first forbidden-host code point of the [HOST-36] table. */
private const val NULL: Char = '\u0000'

/**
 * The forbidden-host code points of SPEC §7.6 [HOST-36], exactly as tabulated.
 *
 * Held as a [Set] for O(1) membership and to keep the source list legible. The set
 * is precisely the 17 listed code points; it is intentionally *not* the full C0
 * control range — only NUL, TAB, LF, and CR of that range are forbidden hosts. The
 * broader C0/DELETE/`%` restrictions belong to the forbidden-domain set ([HOST-37]).
 */
private val FORBIDDEN_HOST_CODE_POINTS: Set<Char> =
    setOf(
        NULL, // U+0000 NULL
        '\t', // U+0009 TAB
        '\n', // U+000A LF
        '\r', // U+000D CR
        ' ', // U+0020 SPACE
        '#',
        '/',
        ':',
        '<',
        '>',
        '?',
        '@',
        '[',
        '\\',
        ']',
        '^',
        '|',
    )

/**
 * True when [cp] is a **forbidden host code point** (SPEC §7.6 [HOST-36]).
 *
 * Applies to opaque hosts (§7.5). The set is the 17 code points of the [HOST-36]
 * table verbatim; membership is a single [Set] lookup with no case folding or
 * Unicode classification.
 *
 * @param cp the candidate code point.
 * @return `true` iff [cp] is one of the [HOST-36] code points.
 */
internal fun isForbiddenHostCodePoint(cp: Char): Boolean = cp in FORBIDDEN_HOST_CODE_POINTS

/**
 * True when [cp] is a **forbidden domain code point** (SPEC §7.6 [HOST-37]).
 *
 * This is a strict superset of the forbidden-host set ([HOST-36]) used by the IDNA
 * domain pipeline (§7.4): it additionally forbids U+0025 (`%`), every C0 control
 * (U+0000–U+001F), and U+007F DELETE. Per the WHATWG definition it does *not*
 * forbid non-ASCII code points U+0080 and above.
 *
 * @param cp the candidate code point.
 * @return `true` iff [cp] is forbidden in a domain.
 */
internal fun isForbiddenDomainCodePoint(cp: Char): Boolean =
    isForbiddenHostCodePoint(cp) || cp == '%' || cp.isC0Control() || cp == DELETE
