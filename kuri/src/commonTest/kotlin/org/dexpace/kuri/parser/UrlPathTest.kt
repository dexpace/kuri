/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Unit tests for [BuilderPath], the builders' structured-path value (RFC 3986 §3.3; SPEC §3.7):
 * its segment-edit transforms ([BuilderPath.pushSegment], [BuilderPath.addSegments],
 * [BuilderPath.setSegment], [BuilderPath.removeSegment]) and their index-bounds contracts.
 */
internal class UrlPathTest {
    @Test
    fun `setSegment throws when the index is past the end`() {
        val path = BuilderPath(listOf("a", "b"))

        assertFailsWith<IndexOutOfBoundsException> { path.setSegment(5, "x") }
    }

    @Test
    fun `setSegment throws when the index is negative`() {
        val path = BuilderPath(listOf("a", "b"))

        assertFailsWith<IndexOutOfBoundsException> { path.setSegment(-1, "x") }
    }

    @Test
    fun `setSegment replaces the segment at the index and keeps the count`() {
        val path = BuilderPath(listOf("a", "b"))

        val next = path.setSegment(1, "c")

        assertEquals(listOf("a", "c"), next.segments)
    }

    @Test
    fun `removeSegment throws when the index is past the end`() {
        val path = BuilderPath(listOf("a"))

        assertFailsWith<IndexOutOfBoundsException> { path.removeSegment(3) }
    }

    @Test
    fun `removeSegment throws when the index is negative`() {
        val path = BuilderPath(listOf("a"))

        assertFailsWith<IndexOutOfBoundsException> { path.removeSegment(-1) }
    }

    @Test
    fun `removeSegment drops exactly the segment at the index`() {
        val path = BuilderPath(listOf("a", "b", "c"))

        val next = path.removeSegment(1)

        assertEquals(listOf("a", "c"), next.segments)
    }

    @Test
    fun `pushSegment appends onto a non-empty path`() {
        val path = BuilderPath(listOf("a"))

        val next = path.pushSegment("b")

        assertEquals(listOf("a", "b"), next.segments)
    }

    @Test
    fun `pushSegment fills an open trailing slot instead of doubling it`() {
        val path = BuilderPath(listOf("a", ""))

        val next = path.pushSegment("b")

        assertEquals(listOf("a", "b"), next.segments)
    }

    @Test
    fun `addSegments from an empty path drops the leading empty segment and defers rooting`() {
        val path = BuilderPath()

        val next = path.addSegments("/a/b") { it }

        assertEquals(listOf("a", "b"), next.segments)
        assertEquals(PathRooting.DEFERRED, next.rooting)
    }

    @Test
    fun `addSegments onto existing content keeps the rooting`() {
        val path = BuilderPath(listOf("a"))

        val next = path.addSegments("b/c") { it }

        assertEquals(listOf("a", "b", "c"), next.segments)
        assertEquals(PathRooting.ROOTED, next.rooting)
    }

    @Test
    fun `pushSegment appends an empty segment onto a non-empty path`() {
        val path = BuilderPath(listOf("a"))

        val next = path.pushSegment("")

        assertEquals(listOf("a", ""), next.segments)
    }

    @Test
    fun `toUriPathString returns an opaque path verbatim`() {
        val path: UrlPath = UrlPath.Opaque("a@b")

        assertEquals("a@b", path.toUriPathString())
    }

    @Test
    fun `decodedSegments yields the single decoded value for an opaque path`() {
        val result = decodedSegments(UrlPath.Opaque("a%40b")) { it }

        assertEquals(listOf("a%40b"), result)
    }
}
