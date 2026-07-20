/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.error

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

/**
 * Structural tests for [ValidationError] and [ValidationErrorKind] (SPEC §12.3): the record shape
 * — a [ValidationError.kind], its [ValidationError.at] offset, and [ValidationError.isFailure] —
 * that the class KDoc previously claimed but that had no implementation (issue #117).
 */
internal class ValidationErrorTest {
    private companion object {
        const val OFFSET: Int = 7
    }

    @Test
    fun `exposes the kind and at it was constructed with`() {
        // arrange + act
        val error = ValidationError(ValidationErrorKind.BACKSLASH_AS_SOLIDUS, at = OFFSET)

        // assert
        assertEquals(ValidationErrorKind.BACKSLASH_AS_SOLIDUS, error.kind)
        assertEquals(OFFSET, error.at)
    }

    @Test
    fun `isFailure is false for every currently recorded kind`() {
        // Every kind kuri records today is non-fatal by construction: a WHATWG failure-class
        // condition never reaches this list, it surfaces as a UriParseError instead (see
        // ValidationError.isFailure's KDoc).
        for (kind in ValidationErrorKind.entries) {
            assertFalse(ValidationError(kind, at = 0).isFailure, "expected $kind to be non-failure")
        }
    }

    @Test
    fun `is equal by value and differs on an unequal field`() {
        // arrange
        val error = ValidationError(ValidationErrorKind.INVALID_CREDENTIALS, at = OFFSET)
        val same = ValidationError(ValidationErrorKind.INVALID_CREDENTIALS, at = OFFSET)
        val differentAt = ValidationError(ValidationErrorKind.INVALID_CREDENTIALS, at = OFFSET + 1)
        val differentKind = ValidationError(ValidationErrorKind.INVALID_URL_UNIT, at = OFFSET)

        // act + assert
        assertEquals(same, error)
        assertEquals(same.hashCode(), error.hashCode())
        assertNotEquals(differentAt, error)
        assertNotEquals(differentKind, error)
    }

    @Test
    fun `copy preserves isFailure`() {
        // isFailure is a body-level val (not a constructor parameter, per SPEC §12.3's shape), so
        // it is derived fresh on every instance rather than carried through copy() -- this pins
        // down that the derived value stays consistent after a copy.
        val error = ValidationError(ValidationErrorKind.MISSING_AUTHORITY_SLASHES, at = OFFSET)

        val copied = error.copy(at = OFFSET + 1)

        assertEquals(OFFSET + 1, copied.at)
        assertFalse(copied.isFailure)
    }
}
