/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.error

/**
 * The closed classification of non-fatal WHATWG validation anomalies (SPEC §12.3).
 *
 * WHATWG parsing is a lenient repair process: it accepts and silently corrects
 * inputs a strict reader would reject, and each such correction is a
 * *validation error*. A validation error is observational only: it MUST NOT
 * change the produced value relative to an otherwise identical run, and on its
 * own MUST NOT downgrade an [ParseResult.Ok] to an [ParseResult.Err].
 *
 * This enum is the closed set of anomaly kinds `kuri` classifies, defined for
 * completeness of the error model. Any offset or `isFailure` metadata from
 * §12.3 is carried by the record that wraps an anomaly with one of these kinds;
 * the enum itself is just the classification.
 */
public enum class ValidationError {
    /** An ASCII tab/LF/CR (U+0009/U+000A/U+000D) was stripped from the input. */
    TAB_OR_NEWLINE_REMOVED,

    /** A leading or trailing C0-control-or-space (U+0000..U+0020) was trimmed. */
    LEADING_OR_TRAILING_C0_CONTROL_OR_SPACE,

    /** A `\` was interpreted as `/` under a special scheme (table row c). */
    BACKSLASH_AS_SOLIDUS,

    /** An authority-introducing slash run was not exactly `//` (table row m). */
    MISSING_AUTHORITY_SLASHES,

    /**
     * A code point that is not a URL code point (and not a valid percent-escape)
     * appeared in a component (the WHATWG *invalid-URL-unit* anomaly).
     */
    INVALID_URL_UNIT,
}
