/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri

import org.dexpace.kuri.error.getOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.fail

class UriBuilderTest {
    private fun parseOk(input: String): Uri = Uri.parse(input).getOrNull() ?: fail("expected $input to parse")

    @Test
    fun `builds a full generic uri from its components`() {
        val uri =
            Uri
                .Builder()
                .scheme("foo")
                .userInfo("u")
                .host("h")
                .port(8042)
                .encodedPath("/over/there")
                .query("q")
                .fragment("n")
                .build()

        assertEquals("foo://u@h:8042/over/there?q#n", uri.uriString)
    }

    @Test
    fun `builds a relative reference with a null scheme`() {
        val uri =
            Uri
                .Builder()
                .host("h")
                .encodedPath("/p")
                .build()

        assertNull(uri.scheme)
        assertEquals("//h/p", uri.uriString)
    }

    @Test
    fun `newBuilder then build reproduces an equal value`() {
        val original = parseOk("foo://u@h:8042/over/there?q#n")

        val rebuilt = original.newBuilder().build()

        assertEquals(original, rebuilt)
        assertEquals(original.uriString, rebuilt.uriString)
    }

    @Test
    fun `a setter override changes only the targeted component`() {
        val original = parseOk("foo://h/a/b?q#n")

        val rebuilt =
            original
                .newBuilder()
                .fragment(null)
                .query(null)
                .build()

        assertEquals("foo://h/a/b", rebuilt.uriString)
    }

    @Test
    fun `the explicit port is preserved through a build`() {
        val uri =
            Uri
                .Builder()
                .scheme("http")
                .host("h")
                .port(80)
                .encodedPath("/")
                .build()

        assertEquals("http://h:80/", uri.uriString)
    }

    @Test
    fun `an invalid scheme is rejected eagerly`() {
        assertFailsWith<IllegalArgumentException> { Uri.Builder().scheme("1bad") }
    }

    @Test
    fun `a malformed component fails the build`() {
        val builder =
            Uri
                .Builder()
                .scheme("foo")
                .host("h")
                .encodedPath("/p?a=%2g")

        assertFailsWith<IllegalArgumentException> { builder.build() }
    }
}
