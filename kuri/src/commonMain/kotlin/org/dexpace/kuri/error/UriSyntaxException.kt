/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.error

/**
 * Thrown when a parse fails and the caller opted into exceptions rather than a [ParseResult] branch.
 *
 * Raised by [ParseResult.getOrThrow] and by the throwing parse entry points, this exception is an
 * [IllegalArgumentException] (a malformed URI is a bad argument) that additionally carries the
 * structured [error]. Its message is a human-readable rendering of that error, while [error] retains
 * the full machine-inspectable detail (offsets, sub-causes) for callers that need it.
 *
 * Instances are constructed only inside the library (the constructor is `internal`); callers obtain
 * one by catching it, never by building it.
 *
 * @property error the structured cause of the failure, identical to the [ParseResult.Err] error.
 */
public class UriSyntaxException internal constructor(
    public val error: UriParseError,
) : IllegalArgumentException(error.message)
