/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.idna

/**
 * Outcome of the UTS-46 mapping step for a single code point (SPEC §7.4, [HOST-26] step 2).
 *
 * The mapping table is decoded under the `Url`-profile parameter set ([HOST-28]):
 * `UseSTD3ASCIIRules = false` (so STD3-disallowed-but-otherwise-valid points are [Valid] and
 * STD3-disallowed-but-otherwise-mapped points are [Mapped]) and `Transitional_Processing = false`
 * (so deviation characters are *kept*, surfaced here as the distinct [Deviation] status rather
 * than being folded into [Valid]).
 *
 * Closed hierarchy so callers can exhaust it with `when` and no `else`.
 */
internal sealed interface IdnaMapping {
    /** The code point is permitted as-is and contributes itself to the output. */
    object Valid : IdnaMapping

    /** The code point is dropped from the output (e.g. U+00AD SOFT HYPHEN). */
    object Ignored : IdnaMapping

    /**
     * The code point is not permitted in a domain and MUST fail host parsing ([HOST-30]).
     *
     * Note: ASCII points such as U+0000 are *not* [Disallowed] here — they are [Valid] at the
     * mapping layer and are rejected later by the mandatory forbidden-domain re-scan ([HOST-30]).
     */
    object Disallowed : IdnaMapping

    /**
     * A deviation character (U+00DF, U+03C2, U+200C, U+200D). Under non-transitional processing
     * it is treated exactly like [Valid] — the code point is kept — but the status is retained
     * distinctly so a future transitional mode could map it instead.
     */
    object Deviation : IdnaMapping

    /**
     * The code point is replaced by [replacement], a non-empty sequence of one or more code points.
     *
     * @property replacement the substitute text (already the post-mapping code points).
     */
    data class Mapped(
        val replacement: String,
    ) : IdnaMapping
}
