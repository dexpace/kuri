/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.error

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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
    fun `Ok equals another Ok with an equal value and differs on an unequal value`() {
        // arrange + act + assert
        assertEquals(ParseResult.Ok(1), ParseResult.Ok(1))
        assertNotEquals(ParseResult.Ok(1), ParseResult.Ok(2))
    }

    @Test
    fun `equal Oks share a hash code`() {
        // arrange + act + assert
        assertEquals(ParseResult.Ok("scheme").hashCode(), ParseResult.Ok("scheme").hashCode())
    }

    @Test
    fun `an Ok holding a null value hashes to zero`() {
        // arrange: exercises the null arm of value?.hashCode() ?: 0
        val result: ParseResult<String?> = ParseResult.Ok(null)

        // act + assert
        assertEquals(0, result.hashCode())
    }

    @Test
    fun `fold runs onOk for an Ok and onErr for an Err`() {
        // arrange: cover both arms of the fold when-expression at one call site
        val ok: ParseResult<Int> = ParseResult.Ok(3)
        val err: ParseResult<Int> = ParseResult.Err(UriParseError.MissingScheme)

        // act
        val fromOk = ok.fold(onOk = { it + 1 }, onErr = { -1 })
        val fromErr = err.fold(onOk = { it + 1 }, onErr = { -1 })

        // assert
        assertEquals(4, fromOk)
        assertEquals(-1, fromErr)
    }

    @Test
    fun `Err equals another Err with an equal error and differs on an unequal error`() {
        // arrange
        val port: ParseResult<Nothing> = ParseResult.Err(UriParseError.InvalidPort("99999"))
        val samePort: ParseResult<Nothing> = ParseResult.Err(UriParseError.InvalidPort("99999"))
        val missing: ParseResult<Nothing> = ParseResult.Err(UriParseError.MissingScheme)

        // act + assert
        assertEquals(port, samePort)
        assertNotEquals(port, missing)
    }

    @Test
    fun `equal Errs share a hash code`() {
        // arrange
        val error: UriParseError = UriParseError.MissingScheme

        // act + assert
        assertEquals(ParseResult.Err(error).hashCode(), ParseResult.Err(error).hashCode())
    }

    @Test
    fun `an Ok never equals an Err`() {
        // arrange
        val ok: ParseResult<Int> = ParseResult.Ok(1)
        val err: ParseResult<Int> = ParseResult.Err(UriParseError.MissingScheme)

        // act + assert
        assertNotEquals(ok, err)
        assertNotEquals(err, ok)
    }

    @Test
    fun `toString renders the variant and its payload`() {
        // arrange + act + assert
        assertEquals("Ok(1)", ParseResult.Ok(1).toString())
        assertEquals("Err(MissingScheme)", ParseResult.Err(UriParseError.MissingScheme).toString())
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
