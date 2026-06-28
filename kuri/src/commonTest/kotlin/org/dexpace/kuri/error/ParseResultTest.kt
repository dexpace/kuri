/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.error

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behavioural tests for the [ParseResult] ADT and its helpers (SPEC §12.1).
 */
internal class ParseResultTest {
    private companion object {
        const val OFFSET: Int = 7
    }

    @Test
    fun `Ok carries the produced value`() {
        // arrange
        val result = ParseResult.Ok("scheme")

        // act + assert
        assertEquals("scheme", result.value)
    }

    @Test
    fun `Err carries the fatal error`() {
        // arrange
        val error: UriParseError = UriParseError.InvalidScheme(OFFSET, "leading digit")
        val result = ParseResult.Err(error)

        // act + assert
        assertEquals(error, result.error)
    }

    @Test
    fun `getOrNull returns the value when Ok`() {
        // arrange
        val result: ParseResult<Int> = ParseResult.Ok(2)

        // act + assert
        assertEquals(2, result.getOrNull())
    }

    @Test
    fun `getOrNull returns null when Err`() {
        // arrange
        val result: ParseResult<Int> = ParseResult.Err(UriParseError.MissingScheme)

        // act + assert
        assertNull(result.getOrNull())
    }

    @Test
    fun `isOk is true for Ok and false for Err`() {
        // arrange
        val ok: ParseResult<Int> = ParseResult.Ok(1)
        val err: ParseResult<Int> = ParseResult.Err(UriParseError.EmptyHost)

        // act + assert
        assertTrue(ok.isOk())
        assertFalse(err.isOk())
    }

    @Test
    fun `map transforms the value when Ok`() {
        // arrange
        val result: ParseResult<Int> = ParseResult.Ok(2)

        // act
        val mapped = result.map { it * it }

        // assert
        assertEquals(ParseResult.Ok(4), mapped)
    }

    @Test
    fun `map passes the error through unchanged when Err`() {
        // arrange
        val error = UriParseError.InvalidPort("99999")
        val result: ParseResult<Int> = ParseResult.Err(error)

        // act
        val mapped: ParseResult<String> = result.map { it.toString() }

        // assert
        assertEquals(ParseResult.Err(error), mapped)
    }

    @Test
    fun `map does not invoke transform when Err`() {
        // arrange
        val result: ParseResult<Int> = ParseResult.Err(UriParseError.MissingScheme)
        var invoked = false

        // act
        result.map {
            invoked = true
            it
        }

        // assert
        assertFalse(invoked, "transform must not run on the failure path")
    }

    @Test
    fun `when over ParseResult is exhaustive without an else branch`() {
        // arrange
        val result: ParseResult<String> = ParseResult.Ok("value")

        // act: a when with no else compiles only because the hierarchy is sealed
        val described: String =
            when (result) {
                is ParseResult.Ok -> "ok:${result.value}"
                is ParseResult.Err -> "err:${result.error}"
            }

        // assert
        assertEquals("ok:value", described)
    }
}
