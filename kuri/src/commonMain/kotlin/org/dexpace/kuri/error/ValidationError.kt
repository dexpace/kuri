/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.error

/**
 * Non-fatal parsing anomalies (SPEC §12.3).
 *
 * WHATWG parsing is a lenient repair process: it accepts and silently corrects
 * inputs a strict reader would reject, recording each correction as a
 * *validation error*. `kuri` preserves these so strict or security-sensitive
 * callers can inspect what was repaired while lenient callers ignore them. A
 * validation error is observational only: it MUST NOT change the produced value
 * relative to an otherwise identical run ([ERR-20]), and on its own MUST NOT
 * downgrade an [ParseResult.Ok] to an [ParseResult.Err] ([TERM-3]).
 *
 * This is a faithful *starter subset* of the §12.3 kinds — the ones reachable
 * before the parser's authority/host/path states exist. The full set
 * (`ExtraAuthoritySlashes`, `CredentialsInAuthority`, `DefaultPortRemoved`,
 * `EmptyHostForFileScheme`, …) lands with the parser. Offset/`isFailure`
 * metadata from §12.3 is carried by the parser-side record that wraps this kind;
 * this enum is just the closed classification.
 */
internal enum class ValidationError {
    /** An ASCII tab/LF/CR (U+0009/U+000A/U+000D) was stripped from the input ([ERR-19]). */
    TAB_OR_NEWLINE_REMOVED,

    /** A leading or trailing C0-control-or-space (U+0000..U+0020) was trimmed ([ERR-19]). */
    LEADING_OR_TRAILING_C0_CONTROL_OR_SPACE,

    /** A `\` was interpreted as `/` under a special scheme ([ERR-19], table row c). */
    BACKSLASH_AS_SOLIDUS,

    /** An authority-introducing slash run was not exactly `//` ([ERR-19], table row m). */
    MISSING_AUTHORITY_SLASHES,

    /**
     * A code point that is not a URL code point (and not a valid percent-escape)
     * appeared in a component (the WHATWG *invalid-URL-unit* anomaly, [GRAM-16]).
     */
    INVALID_URL_UNIT,
}
