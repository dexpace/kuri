/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.query

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Behavioural tests for [FormUrlEncoded] against SPEC §10.4 ([QUERY-20]-[QUERY-23]): the
 * `application/x-www-form-urlencoded` dialect where `+` means space.
 */
class FormUrlEncodedTest {
    @Test
    fun `parse maps plus to space in name and value per QUERY-21`() {
        assertEquals(listOf("a b" to "c d"), FormUrlEncoded.parse("a+b=c+d"))
    }

    @Test
    fun `parse treats a segment without equals as an empty value per QUERY-21`() {
        assertEquals(listOf("a" to ""), FormUrlEncoded.parse("a"))
    }

    @Test
    fun `parse skips empty segments from leading trailing and doubled ampersands per QUERY-21`() {
        assertEquals(listOf("a" to "1", "b" to "2"), FormUrlEncoded.parse("&a=1&&b=2&"))
    }

    @Test
    fun `parse handles the empty-name segments mixed with a pair per QUERY-21`() {
        // "a=&=&=b" -> ("a",""), ("",""), ("","b"); no segment is empty so none is skipped.
        assertEquals(listOf("a" to "", "" to "", "" to "b"), FormUrlEncoded.parse("a=&=&=b"))
    }

    @Test
    fun `parse percent-decodes accented octets as UTF-8 per QUERY-21 and QUERY-22`() {
        assertEquals(listOf("name" to "é"), FormUrlEncoded.parse("name=%C3%A9"))
    }

    @Test
    fun `serialize renders space as plus per QUERY-20`() {
        assertEquals("a+b=c+d", FormUrlEncoded.serialize(listOf("a b" to "c d")))
    }

    @Test
    fun `serialize escapes literal plus ampersand and equals per QUERY-20`() {
        assertEquals("k=%2B", FormUrlEncoded.serialize(listOf("k" to "+")))
        assertEquals("k=%26", FormUrlEncoded.serialize(listOf("k" to "&")))
        assertEquals("k=%3D", FormUrlEncoded.serialize(listOf("k" to "=")))
    }

    @Test
    fun `serialize escapes accented characters as UTF-8 octets per QUERY-20 and QUERY-22`() {
        assertEquals("name=%C3%A9", FormUrlEncoded.serialize(listOf("name" to "é")))
    }

    @Test
    fun `serialize keeps the form-unreserved punctuation literal per QUERY-20`() {
        assertEquals("k=*-._", FormUrlEncoded.serialize(listOf("k" to "*-._")))
    }

    @Test
    fun `serialize then parse round-trips a space-bearing pair per QUERY-20 and QUERY-21`() {
        val pairs = listOf("full name" to "Ada Lovelace")
        assertEquals(pairs, FormUrlEncoded.parse(FormUrlEncoded.serialize(pairs)))
    }

    @Test
    fun `parse caps the pair count at the resource bound per QUERY-24`() {
        val overLimit = (0 until MAX_PAIRS + 1).joinToString("&") { "k$it=v$it" }
        assertEquals(MAX_PAIRS, FormUrlEncoded.parse(overLimit).size)
    }
}
