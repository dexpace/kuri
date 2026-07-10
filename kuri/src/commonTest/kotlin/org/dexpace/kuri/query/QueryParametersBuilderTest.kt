/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.query

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Behavioural tests for [QueryParametersBuilder] against SPEC §10.3.2 ([QUERY-15]-[QUERY-18]):
 * append, replace-first/remove-rest, remove-all, and a stable code-point sort.
 */
class QueryParametersBuilderTest {
    @Test
    fun `add appends without deduplicating and keeps null and empty sentinels per QUERY-15`() {
        val params =
            QueryParametersBuilder()
                .add("a", "1")
                .add("a", null)
                .add("a", "")
                .build()
        assertEquals(listOf("a" to "1", "a" to null, "a" to ""), params.entries)
    }

    @Test
    fun `set replaces the first occurrence removes the rest and keeps position per QUERY-16`() {
        val params =
            QueryParameters
                .parse("a=1&b=2&a=3&c=4&a=5")
                .newBuilder()
                .set("a", "X")
                .build()
        assertEquals(listOf("a" to "X", "b" to "2", "c" to "4"), params.entries)
    }

    @Test
    fun `set appends when the name is absent per QUERY-16`() {
        val params =
            QueryParameters
                .parse("a=1")
                .newBuilder()
                .set("b", "2")
                .build()
        assertEquals(listOf("a" to "1", "b" to "2"), params.entries)
    }

    @Test
    fun `removeAll drops every matching pair and preserves the rest per QUERY-17`() {
        val params =
            QueryParameters
                .parse("a=1&b=2&a=3&c=4")
                .newBuilder()
                .removeAll("a")
                .build()
        assertEquals(listOf("b" to "2", "c" to "4"), params.entries)
    }

    @Test
    fun `removeAll is a no-op when no pair matches per QUERY-17`() {
        val params =
            QueryParameters
                .parse("a=1&b=2")
                .newBuilder()
                .removeAll("z")
                .build()
        assertEquals(listOf("a" to "1", "b" to "2"), params.entries)
    }

    @Test
    fun `sort orders by name code point and is stable for equal names per QUERY-18`() {
        val params =
            QueryParameters
                .parse("b=1&a=2&b=3&a=4&c=5")
                .newBuilder()
                .sort()
                .build()
        // Equal names keep their pre-sort order: a=2 before a=4, b=1 before b=3.
        assertEquals(
            listOf("a" to "2", "a" to "4", "b" to "1", "b" to "3", "c" to "5"),
            params.entries,
        )
    }

    @Test
    fun `sort places supplementary plane names after every BMP name per QUERY-18`() {
        // U+1F600 (astral) must sort after the BMP name despite its leading surrogate U+D83D
        // ordering below '￿' in raw UTF-16 unit comparison.
        val astral = "😀"
        val bmp = "￿"
        val params =
            QueryParametersBuilder()
                .add(astral, "emoji")
                .add(bmp, "bmp")
                .sort()
                .build()
        assertEquals(listOf(bmp to "bmp", astral to "emoji"), params.entries)
    }

    @Test
    fun `sort orders a name before the longer name that extends it per QUERY-18`() {
        // A prefix compares as less than its extension: the code-point scan runs out of the
        // shorter name with every compared point equal, so length alone breaks the tie.
        val params =
            QueryParametersBuilder()
                .add("ab", "1")
                .add("a", "2")
                .sort()
                .build()
        assertEquals(listOf("a" to "2", "ab" to "1"), params.entries)
    }

    @Test
    fun `sort compares names that differ before their final character per QUERY-18`() {
        // "aac" and "abc" first diverge at the middle point while characters still remain, so the
        // scan stops on the unequal point rather than running off either name's end.
        val params =
            QueryParametersBuilder()
                .add("abc", "1")
                .add("aac", "2")
                .sort()
                .build()
        assertEquals(listOf("aac" to "2", "abc" to "1"), params.entries)
    }

    @Test
    fun `sort runs the shorter name off each comparison side per QUERY-18`() {
        // Repeated "m"/"ma" comparisons drive the code-point scan off the end of the shorter name
        // from both comparison sides, exercising each length-exit arm of the surrogate-aware scan.
        val params =
            QueryParametersBuilder()
                .add("ma", "1")
                .add("m", "2")
                .add("ma", "3")
                .add("m", "4")
                .sort()
                .build()
        assertEquals(listOf("m" to "2", "m" to "4", "ma" to "1", "ma" to "3"), params.entries)
    }

    @Test
    fun `newBuilder copies the snapshot so the source is untouched per QUERY-19`() {
        val source = QueryParameters.parse("a=1")
        source.newBuilder().add("b", "2").build()
        assertEquals(listOf("a" to "1"), source.entries)
    }

    @Test
    fun `addAll appends every map entry in iteration order and keeps the null sentinel per QUERY-15`() {
        val params =
            QueryParametersBuilder()
                .add("a", "1")
                .addAll(linkedMapOf("b" to "2", "c" to null))
                .build()
        assertEquals(listOf("a" to "1", "b" to "2", "c" to null), params.entries)
    }
}
