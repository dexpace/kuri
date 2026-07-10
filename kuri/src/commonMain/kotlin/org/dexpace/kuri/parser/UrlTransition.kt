/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.parser

import org.dexpace.kuri.error.UriParseError

/**
 * The outcome of running one state function: the next state and how the single non-decreasing
 * pointer moves (SPEC §8.2 [PARSE-9]–[PARSE-11]).
 *
 * - [Advance] consumes the current code point: the loop terminates if the pointer is already at
 *   EOF, otherwise advances by one ([PARSE-9]).
 * - [Reconsume] re-enters [next] without advancing — the WHATWG "decrease pointer by one"
 *   emulated as not advancing ([PARSE-11]).
 * - [Fail] aborts with a fatal [error] ([PARSE-15]/[PARSE-30] etc.).
 */
internal sealed interface UrlTransition {
    /** Consume the current code point and continue (or terminate at EOF) in [next]. */
    data class Advance(
        val next: UrlState,
    ) : UrlTransition

    /** Re-enter [next] at the same pointer position (WHATWG "decrease pointer by one"). */
    data class Reconsume(
        val next: UrlState,
    ) : UrlTransition

    /** Abort the parse with the fatal [error]. */
    data class Fail(
        val error: UriParseError,
    ) : UrlTransition

    /**
     * A state-override run finished successfully at the boundary of its component ([SET-*];
     * WHATWG "if state override is given … return"). The loop commits the seeded state and
     * terminates without consuming further input.
     */
    data object Done : UrlTransition

    /**
     * A state-override run hit a WHATWG no-op guard (e.g. special↔non-special scheme change,
     * a port on a hostless URL). The seeded state is discarded and the setter returns its
     * receiver unchanged; never an error, never a throw.
     */
    data object Abort : UrlTransition
}
