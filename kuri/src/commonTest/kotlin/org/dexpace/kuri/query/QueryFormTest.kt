/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.query

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Behavioural tests for the form-urlencoded bridge and duplicate-preserving factories of
 * [QueryParameters]/[QueryParametersBuilder]: [QueryParameters.toFormUrlEncoded],
 * [QueryParameters.Companion.parseForm], [QueryParameters.Companion.of] over varargs, and the
 * [QueryParameter] overloads of [QueryParametersBuilder.add]/[QueryParametersBuilder.set]
 * (SPEC §10.3-§10.4).
 */
class QueryFormTest {
    @Test
    fun `toFormUrlEncoded renders a space value as plus per QUERY-20`() {
        val params = QueryParameters.of(QueryParameter("a", "b c"))
        assertEquals("a=b+c", params.toFormUrlEncoded())
    }

    @Test
    fun `toFormUrlEncoded escapes literal plus ampersand and equals as UTF-8 octets per QUERY-20`() {
        val params = QueryParameters.of(QueryParameter("k", "+&="))
        assertEquals("k=%2B%26%3D", params.toFormUrlEncoded())
    }

    @Test
    fun `toFormUrlEncoded emits a name-only pair for a null value like toQueryString`() {
        val params = QueryParameters.of(QueryParameter("flag", null))
        assertEquals("flag", params.toFormUrlEncoded())
        assertEquals("flag", params.toQueryString())
    }

    @Test
    fun `toFormUrlEncoded is empty for an empty snapshot`() {
        assertEquals("", QueryParameters.of().toFormUrlEncoded())
    }

    @Test
    fun `parseForm maps plus to space and preserves duplicate names per QUERY-21`() {
        val params = QueryParameters.parseForm("a=b+c&a=d")
        assertEquals(2, params.size)
        assertEquals("b c", params.get("a"))
        assertEquals(listOf<String?>("b c", "d"), params.getAll("a"))
    }

    @Test
    fun `parseForm treats a segment without equals as an empty value not the null sentinel per QUERY-21`() {
        val params = QueryParameters.parseForm("a")
        assertEquals(1, params.size)
        assertEquals("", params.valueAt(0))
    }

    @Test
    fun `parseForm skips empty segments from leading trailing and doubled ampersands per QUERY-21`() {
        val params = QueryParameters.parseForm("&a=1&&b=2&")
        assertEquals(listOf("a" to "1", "b" to "2"), params.entries)
    }

    @Test
    fun `parseForm yields an empty snapshot for an empty body`() {
        assertTrue(QueryParameters.parseForm("").isEmpty())
    }

    @Test
    fun `toFormUrlEncoded then parseForm round-trips a space-bearing pair`() {
        val params = QueryParameters.of(QueryParameter("full name", "Ada Lovelace"))
        val round = QueryParameters.parseForm(params.toFormUrlEncoded())
        assertEquals("Ada Lovelace", round.get("full name"))
    }

    @Test
    fun `of over varargs preserves duplicate names and order`() {
        val params = QueryParameters.of(QueryParameter("k", "1"), QueryParameter("k", "2"))
        assertEquals(2, params.size)
        assertEquals(listOf<String?>("1", "2"), params.getAll("k"))
    }

    @Test
    fun `of over varargs round-trips a snapshot through iteration`() {
        val original = QueryParameters.parse("a=1&a=2&b=3")
        val rebuilt = QueryParameters.of(*original.toList().toTypedArray())
        assertEquals(original, rebuilt)
    }

    @Test
    fun `builder add with a QueryParameter appends without destructuring`() {
        val params =
            QueryParametersBuilder()
                .add(QueryParameter("a", "1"))
                .add(QueryParameter("a", null))
                .build()
        assertEquals(listOf("a" to "1", "a" to null), params.entries)
    }

    @Test
    fun `builder set with a QueryParameter replaces the first and removes the rest`() {
        val params =
            QueryParameters
                .parse("a=1&b=2&a=3")
                .newBuilder()
                .set(QueryParameter("a", "9"))
                .build()
        assertEquals(listOf("a" to "9", "b" to "2"), params.entries)
    }

    @Test
    fun `the in operator still resolves from Kotlin after hiding contains from Java`() {
        val params = QueryParameters.parse("a=1")
        assertTrue("a" in params)
        assertFalse("z" in params)
    }
}
