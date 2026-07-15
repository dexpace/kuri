/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.ktx

import org.dexpace.kuri.Uri
import org.dexpace.kuri.Url
import org.dexpace.kuri.error.ParseResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KtxTest {
    private val base: Url = Url.parseOrThrow("https://example.com/api")

    @Test
    fun `div appends encoded path segments and does not mutate the receiver`() {
        val next = base / "v2" / "users"
        assertEquals(listOf("api", "v2", "users"), next.pathSegments)
        assertEquals(listOf("api"), base.pathSegments)
    }

    @Test
    fun `div percent-encodes a decoded segment`() {
        val url = base / "a b"
        assertEquals("/api/a%20b", url.encodedPath)
        assertEquals(listOf("api", "a b"), url.pathSegments)
    }

    @Test
    fun `plus appends a query parameter preserving duplicates`() {
        val url = base + ("a" to "1") + ("a" to "2")
        assertEquals(listOf("1", "2"), url.queryParameters.getAll("a"))
    }

    @Test
    fun `plus a map appends every entry`() {
        val url = base + mapOf("page" to "2", "q" to "kotlin")
        assertEquals("2", url["page"])
        assertEquals("kotlin", url["q"])
    }

    @Test
    fun `get reads the first value and contains reports presence`() {
        val url = Url.parseOrThrow("https://h/?q=kotlin&q=jvm")
        assertEquals("kotlin", url["q"])
        assertTrue("q" in url)
        assertFalse("missing" in url)
        assertNull(url["missing"])
    }

    @Test
    fun `edit applies a single build and leaves the original untouched`() {
        val next =
            base.edit {
                addPathSegment("42")
                setQueryParameter("page", "2")
            }
        assertEquals("https://example.com/api/42?page=2", next.toString())
        assertEquals(listOf("api"), base.pathSegments)
    }

    @Test
    fun `buildUrl assembles from scratch with sugar helpers`() {
        val url =
            buildUrl {
                scheme("https")
                host("api.example.com")
                pathSegments("v2", "users")
                params("page" to "2")
            }
        assertEquals("api.example.com", url.hostName)
        assertEquals(listOf("v2", "users"), url.pathSegments)
        assertEquals("2", url["page"])
    }

    @Test
    fun `buildUri assembles a generic uri`() {
        val uri =
            buildUri {
                scheme("https")
                host("h")
                pathSegments("x", "y")
                params("q" to "1")
            }
        assertEquals("https://h/x/y?q=1", uri.toString())
    }

    @Test
    fun `string toUrl parses or returns null`() {
        assertEquals("example.com", "https://example.com".toUrl()?.hostName)
        assertNull("://nope".toUrl())
        assertTrue("https://example.com".toUrlResult() is ParseResult.Ok)
    }

    @Test
    fun `invoke is a throwing constructor-style factory`() {
        assertEquals("example.com", Url("https://example.com").hostName)
        assertEquals("mailto", Uri("mailto:ada@example.com").scheme)
    }

    @Test
    fun `uri operators mirror url operators`() {
        val uri = Uri.parseOrThrow("https://h/a") / "b" + ("q" to "1")
        assertEquals(listOf("a", "b"), uri.pathSegments)
        assertEquals("1", uri["q"])
    }
}
