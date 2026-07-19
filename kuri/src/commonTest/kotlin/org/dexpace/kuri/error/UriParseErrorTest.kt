/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.error

import org.dexpace.kuri.ParseOptions
import org.dexpace.kuri.Uri
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

/**
 * Exhaustive rendering and structural tests for the [UriParseError] catalog (SPEC §12.2):
 * every variant's [UriParseError.message], the [UriParseError.InputTooLong],
 * [UriParseError.LimitExceeded], and [UriParseError.ForbiddenHostCodePoint] value semantics, and
 * the end-to-end production paths that reach the size, resource-limit, and forbidden-code-point
 * failures through [Uri.parse].
 */
internal class UriParseErrorTest {
    private companion object {
        const val OFFSET: Int = 5
        const val MAX_INPUT_LENGTH: Int = 65_536
    }

    @Test
    fun `message renders the exact string for InvalidScheme`() {
        // arrange + act + assert
        assertEquals(
            "invalid scheme at offset $OFFSET: leading digit",
            UriParseError.InvalidScheme(OFFSET, "leading digit").message,
        )
    }

    @Test
    fun `message renders the exact string for MissingScheme`() {
        // arrange + act + assert
        assertEquals("missing required scheme", UriParseError.MissingScheme.message)
    }

    @Test
    fun `message renders the exact string for InvalidPercentEncoding`() {
        // arrange + act + assert
        assertEquals("invalid percent-encoding at offset 3", UriParseError.InvalidPercentEncoding(3).message)
    }

    @Test
    fun `message renders the exact string for InvalidPort`() {
        // arrange + act + assert
        assertEquals("invalid port: \"99999\"", UriParseError.InvalidPort("99999").message)
    }

    @Test
    fun `message renders the exact string for EmptyHost`() {
        // arrange + act + assert
        assertEquals("empty host is not permitted for this scheme", UriParseError.EmptyHost.message)
    }

    @Test
    fun `message renders the exact string for InvalidHost`() {
        // arrange
        val error = UriParseError.InvalidHost("bad", HostError.Ipv6Malformed)

        // act + assert
        assertEquals("invalid host \"bad\": ${HostError.Ipv6Malformed}", error.message)
    }

    @Test
    fun `message renders the exact string for ForbiddenHostCodePoint`() {
        // arrange + act + assert
        assertEquals(
            "forbidden host code point 124 at offset 2",
            UriParseError.ForbiddenHostCodePoint(codePoint = 124, at = 2).message,
        )
    }

    @Test
    fun `message renders the exact string for InputTooLong`() {
        // arrange + act + assert
        assertEquals(
            "input length 8193 exceeds maximum 8192",
            UriParseError.InputTooLong(length = 8193, max = 8192).message,
        )
    }

    @Test
    fun `InputTooLong exposes its length and max`() {
        // arrange
        val error = UriParseError.InputTooLong(length = 8193, max = MAX_INPUT_LENGTH)

        // act + assert
        assertEquals(8193, error.length)
        assertEquals(MAX_INPUT_LENGTH, error.max)
    }

    @Test
    fun `InputTooLong is equal by value and differs on an unequal field`() {
        // arrange
        val error = UriParseError.InputTooLong(length = 8193, max = MAX_INPUT_LENGTH)
        val same = UriParseError.InputTooLong(length = 8193, max = MAX_INPUT_LENGTH)
        val other = UriParseError.InputTooLong(length = 9000, max = MAX_INPUT_LENGTH)

        // act + assert
        assertEquals(same, error)
        assertEquals(same.hashCode(), error.hashCode())
        assertNotEquals(other, error)
    }

    @Test
    fun `message renders the exact string for LimitExceeded`() {
        // arrange + act + assert
        assertEquals(
            "resource limit PathSegments exceeded: observed 3 exceeds maximum 2",
            UriParseError.LimitExceeded(ResourceLimit.PathSegments, observed = 3, max = 2).message,
        )
    }

    @Test
    fun `LimitExceeded exposes its limit and observed and max values`() {
        // arrange
        val error = UriParseError.LimitExceeded(ResourceLimit.PathSegments, observed = 3, max = 2)

        // act + assert
        assertEquals(ResourceLimit.PathSegments, error.limit)
        assertEquals(3, error.observed)
        assertEquals(2, error.max)
    }

    @Test
    fun `LimitExceeded is equal by value and differs on an unequal field`() {
        // arrange
        val error = UriParseError.LimitExceeded(ResourceLimit.PathSegments, observed = 3, max = 2)
        val same = UriParseError.LimitExceeded(ResourceLimit.PathSegments, observed = 3, max = 2)
        val differentLimit = UriParseError.LimitExceeded(ResourceLimit.ResolutionDepth, observed = 3, max = 2)
        val differentObserved = UriParseError.LimitExceeded(ResourceLimit.PathSegments, observed = 4, max = 2)

        // act + assert
        assertEquals(same, error)
        assertEquals(same.hashCode(), error.hashCode())
        assertNotEquals(differentLimit, error)
        assertNotEquals(differentObserved, error)
    }

    @Test
    fun `parse reports LimitExceeded with the overridden PathSegments limit`() {
        // arrange: three segments exceeds a builder-lowered maximum of two
        val options = ParseOptions.Builder().pathSegments(2).build()

        // act
        val result = Uri.parse("s:/a/b/c", options)

        // assert
        val error = assertIs<ParseResult.Err>(result).error
        val limitExceeded = assertIs<UriParseError.LimitExceeded>(error)
        assertEquals(ResourceLimit.PathSegments, limitExceeded.limit)
        assertEquals(3L, limitExceeded.observed)
        assertEquals(2L, limitExceeded.max)
    }

    @Test
    fun `parse keeps the default PathSegments bound when no override is given`() {
        // arrange: the same three-segment path that trips a lowered override parses fine by default
        val result = Uri.parse("s:/a/b/c")

        // act + assert
        val uri = assertIs<ParseResult.Ok<Uri>>(result).value
        assertEquals("s:/a/b/c", uri.uriString)
    }

    @Test
    fun `ForbiddenHostCodePoint exposes its code point and offset`() {
        // arrange
        val error = UriParseError.ForbiddenHostCodePoint(codePoint = 124, at = 2)

        // act + assert
        assertEquals(124, error.codePoint)
        assertEquals(2, error.at)
    }

    @Test
    fun `parse reports InputTooLong when input exceeds the maximum length`() {
        // arrange: any input longer than the bound trips the length gate before decomposition
        val oversized = "a".repeat(MAX_INPUT_LENGTH + 1)

        // act
        val result = Uri.parse(oversized)

        // assert
        val error = assertIs<ParseResult.Err>(result).error
        val tooLong = assertIs<UriParseError.InputTooLong>(error)
        assertEquals(MAX_INPUT_LENGTH + 1, tooLong.length)
        assertEquals(MAX_INPUT_LENGTH, tooLong.max)
    }

    @Test
    fun `parse reports ForbiddenHostCodePoint for a forbidden reg-name code point`() {
        // arrange: U+007C VERTICAL LINE is admitted by neither unreserved nor sub-delims in a reg-name
        val result = Uri.parse("s://ex|ample")

        // act + assert
        val error = assertIs<ParseResult.Err>(result).error
        val forbidden = assertIs<UriParseError.ForbiddenHostCodePoint>(error)
        assertEquals('|'.code, forbidden.codePoint)
        // The offset is relative to the host substring "ex|ample" (UriParser forwards the host
        // error's index unchanged), so the '|' at host index 2 is the reported offense.
        assertEquals(2, forbidden.at)
    }
}
