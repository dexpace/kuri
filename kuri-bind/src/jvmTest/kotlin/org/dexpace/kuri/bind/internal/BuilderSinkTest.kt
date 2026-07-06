/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.bind.internal

import org.dexpace.kuri.Uri
import org.dexpace.kuri.Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class BuilderSinkTest {
    // UrlBuilderSink — userinfo split and fragment encoding

    @Test
    fun `url sink splits userinfo and percent-encodes each part`() {
        val b = Url.Builder().scheme("https").host("h")
        val sink = UrlBuilderSink(b)
        sink.userInfo("a b", "p:w")
        val url = b.build()
        // Url.username / Url.password return the percent-encoded stored values.
        assertEquals("a%20b", url.username)
        assertEquals("p%3Aw", url.password)
    }

    @Test
    fun `url sink percent-encodes fragment under FRAGMENT set`() {
        val b = Url.Builder().scheme("https").host("h")
        val sink = UrlBuilderSink(b)
        sink.fragmentDecoded("x y")
        val url = b.build()
        // encodedFragment is a public alias of fragment on Url.
        assertEquals("x%20y", url.encodedFragment)
    }

    @Test
    fun `url sink scheme sets scheme on builder`() {
        val b = Url.Builder().host("h")
        val sink = UrlBuilderSink(b)
        sink.scheme("https")
        val url = b.build()
        assertEquals("https", url.scheme)
        assertNotNull(url)
    }

    @Test
    fun `url sink profile returns URL`() {
        val sink = UrlBuilderSink(Url.Builder())
        assertEquals(org.dexpace.kuri.bind.Profile.URL, sink.profile)
    }

    // UriBuilderSink — verbatim-join userinfo and fragment encoding

    @Test
    fun `uri sink joins userinfo with encoded parts and a literal colon`() {
        val b = Uri.Builder().scheme("https").host("h")
        val sink = UriBuilderSink(b)
        sink.userInfo("a b", "p:w")
        val uri = b.build()
        // Uri.userInfo returns the raw (already-encoded) userinfo string.
        // user encoded under USER_INFO, literal ':' separator, password ':' encoded under USER_INFO.
        assertEquals("a%20b:p%3Aw", uri.userInfo)
    }

    @Test
    fun `uri sink stores username-only userinfo without colon`() {
        val b = Uri.Builder().scheme("https").host("h")
        val sink = UriBuilderSink(b)
        sink.userInfo("bob", null)
        val uri = b.build()
        assertEquals("bob", uri.userInfo)
    }

    @Test
    fun `uri sink percent-encodes fragment under FRAGMENT set`() {
        val b = Uri.Builder().scheme("https").host("h")
        val sink = UriBuilderSink(b)
        sink.fragmentDecoded("x y")
        val uri = b.build()
        assertEquals("x%20y", uri.fragment)
    }

    @Test
    fun `uri sink profile returns URI`() {
        val sink = UriBuilderSink(Uri.Builder())
        assertEquals(org.dexpace.kuri.bind.Profile.URI, sink.profile)
    }
}
