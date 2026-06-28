/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.error

/**
 * The outcome of a parse, reference resolution, or profile conversion (SPEC §12.1).
 *
 * Recoverable parse failures are *values, not control flow*: every fallible
 * entry point returns a [ParseResult] rather than throwing ([ERR-1]). The type
 * has exactly two cases — [Ok] and [Err] — and is `sealed` so a `when` over it
 * is exhaustive without an `else` branch ([ERR-4]).
 *
 * `T` is covariant (`out`) so that an [Err] (a `ParseResult<Nothing>`) is
 * usable wherever any `ParseResult<T>` is expected, letting [map] thread a
 * failure straight through without re-wrapping.
 *
 * @param T the value produced on success.
 */
internal sealed interface ParseResult<out T> {
    /**
     * A successful outcome carrying the produced [value] ([ERR-2]).
     *
     * Non-fatal validation errors observed during a successful parse will be
     * carried alongside the value once the parser lands ([ERR-3]); this starter
     * shape holds only the value.
     *
     * @property value the produced result; never absent on success.
     */
    data class Ok<out T>(
        val value: T,
    ) : ParseResult<T>

    /**
     * A failed outcome carrying the single fatal [error] describing the first
     * fatal condition encountered, in input order ([ERR-2]).
     *
     * @property error the fatal cause of the failure.
     */
    data class Err(
        val error: UriParseError,
    ) : ParseResult<Nothing>
}

/**
 * Returns the success value, or `null` when this is an [ParseResult.Err].
 *
 * Mirrors the `parseOrNull` convenience contract of [ERR-5]: a failure punned to
 * absence. Prefer an explicit `when` when the error itself is needed.
 */
internal fun <T> ParseResult<T>.getOrNull(): T? =
    when (this) {
        is ParseResult.Ok -> value
        is ParseResult.Err -> null
    }

/** Returns `true` iff this is an [ParseResult.Ok] (i.e. the parse succeeded). */
internal fun <T> ParseResult<T>.isOk(): Boolean = this is ParseResult.Ok

/**
 * Maps the success value through [transform], threading an [ParseResult.Err]
 * unchanged.
 *
 * The error case is forwarded as-is (its `Nothing` value type makes it a valid
 * `ParseResult<R>`), so a failure never invokes [transform] and never loses its
 * original [UriParseError].
 *
 * @param transform applied only to the value of an [ParseResult.Ok].
 */
internal fun <T, R> ParseResult<T>.map(transform: (T) -> R): ParseResult<R> =
    when (this) {
        is ParseResult.Ok -> ParseResult.Ok(transform(value))
        is ParseResult.Err -> this
    }
