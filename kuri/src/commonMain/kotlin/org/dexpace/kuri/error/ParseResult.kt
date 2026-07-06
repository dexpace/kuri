/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.error

/**
 * The outcome of a parse, reference resolution, or profile conversion (SPEC §12.1).
 *
 * Recoverable parse failures are *values, not control flow*: every fallible
 * entry point returns a [ParseResult] rather than throwing. The type
 * has exactly two cases — [Ok] and [Err] — and is `sealed` so a `when` over it
 * is exhaustive without an `else` branch.
 *
 * `T` is covariant (`out`) so that an [Err] (a `ParseResult<Nothing>`) is
 * usable wherever any `ParseResult<T>` is expected, letting [map] thread a
 * failure straight through without re-wrapping.
 *
 * @param T the value produced on success.
 */
public sealed interface ParseResult<out T> {
    /**
     * A successful outcome carrying the produced [value].
     *
     * Two [Ok]s are equal exactly when their [value]s are equal. Equality, hashing, and rendering are
     * defined explicitly rather than derived: this is deliberately **not** a `data class`, so it exposes
     * no `copy`/`componentN` that would let a caller fabricate a variant or destructure the result.
     *
     * @property value the value produced on success; present exactly when the parse succeeded.
     */
    public class Ok<out T>(
        public val value: T,
    ) : ParseResult<T> {
        /**
         * Value equality over [value], consistent with [hashCode].
         *
         * @param other the value to compare against.
         * @return `true` iff [other] is an [Ok] whose [value] equals this one's.
         */
        override fun equals(other: Any?): Boolean = other is Ok<*> && other.value == value

        /**
         * The hash of [value], consistent with [equals].
         *
         * @return the hash of [value], or `0` when it is `null`.
         */
        override fun hashCode(): Int = value?.hashCode() ?: 0

        /**
         * A readable rendering of the success case for diagnostics.
         *
         * @return `"Ok(<value>)"`.
         */
        override fun toString(): String = "Ok($value)"
    }

    /**
     * A failed outcome carrying the single fatal [error] describing the first
     * fatal condition encountered, in input order.
     *
     * Two [Err]s are equal exactly when their [error]s are equal. Equality, hashing, and rendering are
     * defined explicitly rather than derived: this is deliberately **not** a `data class`, so it exposes
     * no `copy`/`componentN` that would let a caller fabricate a variant or destructure the result.
     *
     * @property error the fatal cause of the failure.
     */
    public class Err(
        public val error: UriParseError,
    ) : ParseResult<Nothing> {
        /**
         * Value equality over [error], consistent with [hashCode].
         *
         * @param other the value to compare against.
         * @return `true` iff [other] is an [Err] whose [error] equals this one's.
         */
        override fun equals(other: Any?): Boolean = other is Err && other.error == error

        /**
         * The hash of [error], consistent with [equals].
         *
         * @return the hash of [error].
         */
        override fun hashCode(): Int = error.hashCode()

        /**
         * A readable rendering of the failure case for diagnostics.
         *
         * @return `"Err(<error>)"`.
         */
        override fun toString(): String = "Err($error)"
    }

    /**
     * Reports whether this is an [Ok] (i.e. the parse succeeded).
     *
     * @return `true` iff this result is an [Ok].
     */
    public fun isOk(): Boolean = this is Ok

    /**
     * Returns the success value, or `null` when this is an [Err].
     *
     * A failure is punned to absence; prefer an explicit `when` when the error itself is
     * needed.
     *
     * @return the value of an [Ok], or `null` for an [Err].
     */
    public fun getOrNull(): T? =
        when (this) {
            is Ok -> value
            is Err -> null
        }

    /**
     * Returns the success value, or throws [UriSyntaxException] carrying the [Err] error.
     *
     * Use this at a boundary where a parse failure is genuinely exceptional and the caller prefers an
     * exception to a [ParseResult] branch; the thrown exception's [UriSyntaxException.error] is the
     * same structured [UriParseError] held by the [Err].
     *
     * @return the value of an [Ok].
     * @throws UriSyntaxException when this is an [Err], carrying that error.
     */
    public fun getOrThrow(): T =
        when (this) {
            is Ok -> value
            is Err -> throw UriSyntaxException(error)
        }
}

/**
 * Maps the success value through [transform], threading an [ParseResult.Err]
 * unchanged.
 *
 * The error case is forwarded as-is (its `Nothing` value type makes it a valid
 * `ParseResult<R>`), so a failure never invokes [transform] and never loses its
 * original [UriParseError].
 *
 * @param transform applied only to the value of an [ParseResult.Ok].
 * @return an [ParseResult.Ok] of the transformed value, or this [ParseResult.Err] unchanged.
 */
public fun <T, R> ParseResult<T>.map(transform: (T) -> R): ParseResult<R> =
    when (this) {
        is ParseResult.Ok -> ParseResult.Ok(transform(value))
        is ParseResult.Err -> this
    }

/**
 * Folds this result into a single [R] by applying [onOk] to a success or [onErr] to a failure.
 *
 * Exactly one of the two functions is invoked, so the call is total over the sealed [ParseResult]
 * surface and never loses the [UriParseError] on the failure path.
 *
 * @param onOk applied to the value of an [ParseResult.Ok].
 * @param onErr applied to the error of an [ParseResult.Err].
 * @return the value produced by whichever branch matched.
 */
public inline fun <T, R> ParseResult<T>.fold(
    onOk: (T) -> R,
    onErr: (UriParseError) -> R,
): R =
    when (this) {
        is ParseResult.Ok -> onOk(value)
        is ParseResult.Err -> onErr(error)
    }
