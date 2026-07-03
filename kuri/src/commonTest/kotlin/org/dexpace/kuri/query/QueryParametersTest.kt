/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.query

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behavioural tests for [QueryParameters] against SPEC §10.2-§10.3 and the §13.3 category K
 * checklist (ordered, duplicate-preserving, case-sensitive pairs with null-vs-empty sentinels).
 */
class QueryParametersTest {
    @Test
    fun `parse preserves order and duplicate names per QUERY-5`() {
        val params = QueryParameters.parse("a=1&b=2&a=3")
        assertEquals(3, params.size)
        assertEquals(listOf("a" to "1", "b" to "2", "a" to "3"), params.entries)
    }

    @Test
    fun `get returns the first matching value per QUERY-11`() {
        val params = QueryParameters.parse("a=1&b=2&a=3")
        assertEquals("1", params.get("a"))
        assertEquals("2", params.get("b"))
        assertNull(params.get("missing"))
    }

    @Test
    fun `get returns null for a first pair that had no equals per QUERY-11`() {
        val params = QueryParameters.parse("a&a=2")
        assertNull(params.get("a"))
    }

    @Test
    fun `getAll returns every value in order preserving nulls per QUERY-12`() {
        val params = QueryParameters.parse("a=1&b=2&a=3")
        assertEquals(listOf<String?>("1", "3"), params.getAll("a"))
        assertEquals(emptyList(), params.getAll("missing"))
    }

    @Test
    fun `getAll keeps the null sentinel distinct from the empty string per QUERY-12`() {
        val params = QueryParameters.parse("a&a=&a=x")
        assertEquals(listOf(null, "", "x"), params.getAll("a"))
    }

    @Test
    fun `has and names report distinct names in insertion order per QUERY-13`() {
        val params = QueryParameters.parse("a=1&b=2&a=3&c=4")
        assertTrue(params.has("a"))
        assertFalse(params.has("z"))
        assertEquals(listOf("a", "b", "c"), params.names().toList())
    }

    @Test
    fun `name matching is case sensitive per QUERY-5`() {
        val params = QueryParameters.parse("A=1&a=2")
        assertEquals("1", params.get("A"))
        assertEquals("2", params.get("a"))
        assertEquals(2, params.names().size)
    }

    @Test
    fun `present empty query yields one empty pair per QUERY-8`() {
        val fromQuestionMark = QueryParameters.parse("?")
        assertEquals(listOf("" to null), fromQuestionMark.entries)
        val fromEmpty = QueryParameters.parse("")
        assertEquals(listOf("" to null), fromEmpty.entries)
    }

    @Test
    fun `single ampersand yields two empty null pairs per QUERY-8`() {
        val params = QueryParameters.parse("&")
        assertEquals(listOf("" to null, "" to null), params.entries)
    }

    @Test
    fun `key without equals is a null value while key with equals is empty per QUERY-8`() {
        assertEquals(listOf("k" to null), QueryParameters.parse("k").entries)
        assertEquals(listOf("k" to ""), QueryParameters.parse("k=").entries)
    }

    @Test
    fun `only the first equals splits a pair per QUERY-8`() {
        val params = QueryParameters.parse("===3===")
        assertEquals(listOf("" to "==3==="), params.entries)
    }

    @Test
    fun `percent escapes are decoded with plus kept literal per QUERY-6 and QUERY-7`() {
        val params = QueryParameters.parse("a=%26%3D&b=1+2")
        assertEquals("&=", params.get("a"))
        assertEquals("1+2", params.get("b"))
    }

    @Test
    fun `leading question mark is dropped before deriving pairs per QUERY-6`() {
        val params = QueryParameters.parse("?a=1&b=2")
        assertEquals(listOf("a" to "1", "b" to "2"), params.entries)
    }

    @Test
    fun `nameAt and valueAt expose decoded pairs and preserve nulls per QUERY-10`() {
        val params = QueryParameters.parse("a&b=")
        assertEquals("a", params.nameAt(0))
        assertNull(params.valueAt(0))
        assertEquals("b", params.nameAt(1))
        assertEquals("", params.valueAt(1))
    }

    @Test
    fun `index access throws out of bounds for invalid indices per QUERY-10`() {
        val params = QueryParameters.parse("a=1")
        assertFailsWith<IndexOutOfBoundsException> { params.nameAt(-1) }
        assertFailsWith<IndexOutOfBoundsException> { params.valueAt(1) }
    }

    @Test
    fun `empty snapshot reports empty per QUERY-9`() {
        val params = QueryParametersBuilder().build()
        assertTrue(params.isEmpty())
        assertEquals(0, params.size)
        assertEquals("", params.toQueryString())
    }

    @Test
    fun `toQueryString round-trips duplicate names per QUERY-19`() {
        assertEquals("a=1&a=2&a=3", QueryParameters.parse("a=1&a=2&a=3").toQueryString())
    }

    @Test
    fun `toQueryString round-trips the triple-equals value per QUERY-19`() {
        assertEquals("===3===", QueryParameters.parse("===3===").toQueryString())
    }

    @Test
    fun `toQueryString preserves the null versus empty distinction per QUERY-19`() {
        assertEquals("k", QueryParameters.parse("k").toQueryString())
        assertEquals("k=", QueryParameters.parse("k=").toQueryString())
    }

    @Test
    fun `toQueryString escapes delimiters in names and values per QUERY-19`() {
        val query = QueryParameters.parse("a=%26%3D").toQueryString()
        // The value's '&' is escaped while its '=' is left literal so the pair round-trips.
        assertEquals("a=%26=", query)
        assertEquals("&=", QueryParameters.parse(query).get("a"))
    }

    @Test
    fun `toQueryString round-trips non-ASCII through percent encoding per QUERY-19`() {
        val params = QueryParameters.parse("name=%C3%A9")
        assertEquals("é", params.get("name"))
        assertEquals("name=%C3%A9", params.toQueryString())
    }

    @Test
    fun `plus is never serialized as space in the generic query per QUERY-23`() {
        val params = QueryParametersBuilder().add("q", "a b").build()
        assertEquals("q=a%20b", params.toQueryString())
    }

    @Test
    fun `parse caps the pair count at the resource bound per QUERY-24`() {
        val overLimit = (0 until MAX_PAIRS + 1).joinToString("&") { "k$it=v$it" }
        val params = QueryParameters.parse(overLimit)
        assertEquals(MAX_PAIRS, params.size)
        assertEquals("k0", params.nameAt(0))
    }

    @Test
    fun `parse keeps exactly the bound when pairs equal the limit per QUERY-24`() {
        val atLimit = (0 until MAX_PAIRS).joinToString("&") { "k$it" }
        assertEquals(MAX_PAIRS, QueryParameters.parse(atLimit).size)
    }

    @Test
    fun `snapshots with the same ordered pairs are equal and share a hash`() {
        val first = QueryParameters.parse("a=1&b=2&a=3")
        val second =
            QueryParametersBuilder()
                .add("a", "1")
                .add("b", "2")
                .add("a", "3")
                .build()
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun `snapshots differing in order or duplicates are unequal`() {
        val base = QueryParameters.parse("a=1&b=2")
        assertNotEquals(base, QueryParameters.parse("b=2&a=1"))
        assertNotEquals(base, QueryParameters.parse("a=1&b=2&a=1"))
    }

    @Test
    fun `equality keeps the null value distinct from the empty value`() {
        assertNotEquals(QueryParameters.parse("a"), QueryParameters.parse("a="))
    }

    @Test
    fun `toString renders the raw query string`() {
        val params = QueryParameters.parse("a=1&b=2")
        assertEquals("QueryParameters(a=1&b=2)", params.toString())
        assertContains(params.toString(), params.toQueryString())
    }

    @Test
    fun `public parse round-trips a representative query`() {
        val params = QueryParameters.parse("?q=hello%20world&lang=en&lang=fr&empty=&flag")
        assertEquals("hello world", params["q"])
        assertEquals(listOf<String?>("en", "fr"), params.getAll("lang"))
        assertEquals("", params["empty"])
        assertNull(params["flag"])
        assertEquals("q=hello%20world&lang=en&lang=fr&empty=&flag", params.toQueryString())
    }

    @Test
    fun `of builds one pair per map entry in iteration order`() {
        val map = linkedMapOf("a" to "1", "b" to null, "c" to "")
        val params = QueryParameters.of(map)
        assertEquals(listOf("a" to "1", "b" to null, "c" to ""), params.entries)
        assertEquals("a=1&b&c=", params.toQueryString())
    }

    @Test
    fun `iteration yields the pairs in order as QueryParameter values`() {
        val params = QueryParameters.parse("a=1&b&a=3")
        val collected = ArrayList<QueryParameter>()
        for (pair in params) {
            collected.add(pair)
        }
        assertEquals(
            listOf(
                QueryParameter("a", "1"),
                QueryParameter("b", null),
                QueryParameter("a", "3"),
            ),
            collected,
        )
    }

    @Test
    fun `toMap keeps the first value per name and drops duplicates`() {
        val map = QueryParameters.parse("a=1&b=2&a=3").toMap()
        assertEquals(mapOf("a" to "1", "b" to "2"), map)
        assertEquals(listOf("a", "b"), map.keys.toList())
    }

    @Test
    fun `operator get reads the first value by name`() {
        val params = QueryParameters.parse("a=1&a=2&b=3")
        assertEquals("1", params["a"])
        assertEquals("3", params["b"])
        assertNull(params["missing"])
    }

    @Test
    fun `operator contains reports membership by name`() {
        val params = QueryParameters.parse("a=1&b=2")
        assertTrue("a" in params)
        assertFalse("z" in params)
    }

    @Test
    fun `size property counts pairs including duplicates`() {
        val params = QueryParameters.parse("a=1&a=2&b=3")
        assertEquals(3, params.size)
    }
}
