/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.error

/**
 * One non-fatal WHATWG validation anomaly recorded while parsing (SPEC §12.3).
 *
 * WHATWG parsing is a lenient repair process: it accepts and silently corrects inputs a strict
 * reader would reject, and each such correction is a *validation error*. A validation error is
 * observational only: it MUST NOT change the produced value relative to an otherwise identical
 * run, and on its own MUST NOT downgrade a [ParseResult.Ok] to a [ParseResult.Err].
 *
 * This is the record §12.3 specifies: a closed anomaly [kind], the offset [at] which it occurred,
 * and whether that kind [isFailure] under WHATWG's own classification.
 *
 * @property kind the closed classification of what was repaired; see [ValidationErrorKind].
 * @property at the offset at which the anomaly was detected. For [ValidationErrorKind.TAB_OR_NEWLINE_REMOVED]
 *   and [ValidationErrorKind.LEADING_OR_TRAILING_C0_CONTROL_OR_SPACE] — the two pre-processing-stage
 *   anomalies, detected before the state machine's own pointer exists — this is an offset into the
 *   *original*, unprocessed input. For every other kind it is an offset into the pre-processed input
 *   (tab/newline-stripped, leading/trailing-C0-or-space-trimmed, fragment-pruned); a kind recorded
 *   while scanning the fragment continues that same coordinate space past the pruned `#`.
 */
public data class ValidationError(
    public val kind: ValidationErrorKind,
    public val at: Int,
) {
    /**
     * Whether [kind] is one of the WHATWG kinds for which "validation error" and "parse failure"
     * always coincide.
     *
     * Every [ValidationErrorKind] kuri currently records is non-fatal by construction: a
     * WHATWG failure-class condition (e.g. an empty special-scheme host, an out-of-range port)
     * surfaces instead as a fatal [UriParseError] returned via [ParseResult.Err] and is never
     * added to a parse's validation-error list, so this is always `false` today. The field is
     * kept — per SPEC §12.3 — so a consumer that inspects it does not need to change if a
     * genuinely failure-class kind is ever added.
     */
    public val isFailure: Boolean = false
}

/**
 * The closed classification of non-fatal WHATWG validation anomalies (SPEC §12.3).
 */
public enum class ValidationErrorKind {
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
     * appeared in a component (the WHATWG *invalid-URL-unit* anomaly, [PARSE-59]).
     */
    INVALID_URL_UNIT,

    /**
     * A userinfo subcomponent (`username[:password]@`) was present in the authority (the WHATWG
     * *invalid-credentials* anomaly). Recorded once per `@` encountered while splitting the
     * authority ([PARSE-56]) — both a non-final `@` folded into the username and the final `@`
     * delimiter itself — each at its own offset.
     */
    INVALID_CREDENTIALS,

    /**
     * The `file` FILE state's file-base branch found the remaining input begins with a Windows
     * drive letter and cleared the copied base path before reconsuming PATH (the WHATWG
     * *file-invalid-Windows-drive-letter* anomaly, [PARSE-57]).
     */
    FILE_INVALID_WINDOWS_DRIVE_LETTER,

    /**
     * The `file` FILE_HOST state's scanned buffer was a Windows drive letter and was
     * reinterpreted as a path rather than a host (the WHATWG
     * *file-invalid-Windows-drive-letter-host* anomaly, [PARSE-58]).
     */
    FILE_INVALID_WINDOWS_DRIVE_LETTER_HOST,
}
