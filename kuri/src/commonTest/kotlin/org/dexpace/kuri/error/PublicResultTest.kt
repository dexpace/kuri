/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.error

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Behavioural tests for the public result helpers ([getOrThrow], [fold]) and [UriSyntaxException].
 */
internal class PublicResultTest {
    private companion object {
        const val OFFSET: Int = 5
    }

    @Test
    fun `getOrThrow returns the value when Ok`() {
        // arrange
        val result: ParseResult<Int> = ParseResult.Ok(2)

        // act + assert
        assertEquals(2, result.getOrThrow())
    }

    @Test
    fun `getOrThrow throws UriSyntaxException carrying the error when Err`() {
        // arrange
        val error: UriParseError = UriParseError.InvalidScheme(OFFSET, "leading digit")
        val result: ParseResult<Int> = ParseResult.Err(error)

        // act
        val thrown = assertFailsWith<UriSyntaxException> { result.getOrThrow() }

        // assert
        assertSame(error, thrown.error)
    }

    @Test
    fun `fold dispatches to onOk when Ok`() {
        // arrange
        val result: ParseResult<Int> = ParseResult.Ok(2)

        // act
        val folded = result.fold(onOk = { "ok:$it" }, onErr = { "err:$it" })

        // assert
        assertEquals("ok:2", folded)
    }

    @Test
    fun `fold dispatches to onErr when Err`() {
        // arrange
        val error: UriParseError = UriParseError.MissingScheme
        val result: ParseResult<Int> = ParseResult.Err(error)

        // act
        val folded = result.fold(onOk = { "ok:$it" }, onErr = { "err:$it" })

        // assert
        assertEquals("err:$error", folded)
    }

    @Test
    fun `UriSyntaxException round-trips its error and has a non-blank message`() {
        // arrange
        val error: UriParseError = UriParseError.InvalidPort("99999")

        // act
        val exception =
            assertFailsWith<UriSyntaxException> {
                ParseResult.Err(error).getOrThrow()
            }

        // assert
        assertSame(error, exception.error)
        assertTrue(exception.message?.isNotBlank() == true, "message must be non-blank")
    }
}
