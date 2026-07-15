/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.bind.internal

import org.dexpace.kuri.Uri
import org.dexpace.kuri.Url
import org.dexpace.kuri.percent.Percent
import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun `url sink sets a password-only userinfo with an empty username`() {
        val b = Url.Builder().scheme("https").host("h")
        UrlBuilderSink(b).userInfo("", "pw")
        val url = b.build()
        assertEquals("", url.username)
        assertEquals("pw", url.password)
    }

    @Test
    fun `uri sink joins a password-only userinfo with an empty username`() {
        val b = Uri.Builder().scheme("https").host("h")
        UriBuilderSink(b).userInfo("", "pw")
        assertEquals(":pw", b.build().userInfo)
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
    fun `uri sink appends a query parameter to the builder`() {
        val b = Uri.Builder().scheme("https").host("h")
        val sink = UriBuilderSink(b)
        sink.addQueryParameter("k", "v")
        val uri = b.build()
        assertEquals("https://h?k=v", uri.toString())
    }

    // A literal '%' in a decoded value must survive as data, not be misread as a percent-escape
    // introducer, once it reaches the wire — the query/fragment/userinfo encode sets don't reserve
    // '%' themselves (issue #76).

    @Test
    fun `url sink escapes a literal percent in a query value so it reads back literally`() {
        val b = Url.Builder().scheme("https").host("h")
        val sink = UrlBuilderSink(b)
        sink.addQueryParameter("q", "100%3Doff")
        val url = b.build()
        assertEquals("100%3Doff", url.queryParameters.get("q"))
    }

    @Test
    fun `uri sink escapes a literal percent in a query value so it reads back literally`() {
        val b = Uri.Builder().scheme("https").host("h")
        val sink = UriBuilderSink(b)
        sink.addQueryParameter("q", "100%3Doff")
        val uri = b.build()
        assertEquals("100%3Doff", uri.queryParameters().get("q"))
    }

    @Test
    fun `url sink escapes a literal percent in a decoded fragment so it reads back literally`() {
        val b = Url.Builder().scheme("https").host("h")
        val sink = UrlBuilderSink(b)
        sink.fragmentDecoded("100%3Doff")
        val url = b.build()
        assertEquals("100%3Doff", Percent.decode(requireNotNull(url.encodedFragment)))
    }

    @Test
    fun `uri sink escapes a literal percent in a decoded fragment so it reads back literally`() {
        val b = Uri.Builder().scheme("https").host("h")
        val sink = UriBuilderSink(b)
        sink.fragmentDecoded("100%3Doff")
        val uri = b.build()
        assertEquals("100%3Doff", Percent.decode(requireNotNull(uri.fragment)))
    }

    @Test
    fun `url sink escapes a literal percent in a username so it reads back literally`() {
        val b = Url.Builder().scheme("https").host("h")
        val sink = UrlBuilderSink(b)
        sink.userInfo("100%3Doff", null)
        val url = b.build()
        assertEquals("100%3Doff", Percent.decode(url.username))
    }

    @Test
    fun `url sink escapes a literal percent in a password so it reads back literally`() {
        val b = Url.Builder().scheme("https").host("h")
        val sink = UrlBuilderSink(b)
        sink.userInfo("bob", "100%3Doff")
        val url = b.build()
        assertEquals("100%3Doff", Percent.decode(url.password))
    }

    @Test
    fun `uri sink escapes a literal percent in a username so it reads back literally`() {
        val b = Uri.Builder().scheme("https").host("h")
        val sink = UriBuilderSink(b)
        sink.userInfo("100%3Doff", null)
        val uri = b.build()
        assertEquals("100%3Doff", Percent.decode(requireNotNull(uri.userInfo)))
    }

    @Test
    fun `uri sink escapes a literal percent in a password so it reads back literally`() {
        val b = Uri.Builder().scheme("https").host("h")
        val sink = UriBuilderSink(b)
        sink.userInfo("bob", "100%3Doff")
        val uri = b.build()
        val password = requireNotNull(uri.userInfo).substringAfter(':')
        assertEquals("100%3Doff", Percent.decode(password))
    }

    @Test
    fun `url sink escaping round-trips a bare percent beside a character the set reserves`() {
        val b = Url.Builder().scheme("https").host("h")
        val sink = UrlBuilderSink(b)
        sink.addQueryParameter("q", "100% off")
        val url = b.build()
        assertEquals("100% off", url.queryParameters.get("q"))
    }

    @Test
    fun `uri sink escaping round-trips a bare percent beside a character the set reserves`() {
        val b = Uri.Builder().scheme("https").host("h")
        val sink = UriBuilderSink(b)
        sink.addQueryParameter("q", "100% off")
        val uri = b.build()
        assertEquals("100% off", uri.queryParameters().get("q"))
    }

    @Test
    fun `url sink query value with no percent sign is unaffected by the escape`() {
        val b = Url.Builder().scheme("https").host("h")
        val sink = UrlBuilderSink(b)
        sink.addQueryParameter("q", "hello world")
        val url = b.build()
        assertEquals("q=hello%20world", url.query)
        assertEquals("hello world", url.queryParameters.get("q"))
    }

    @Test
    fun `uri sink query value with no percent sign is unaffected by the escape`() {
        val b = Uri.Builder().scheme("https").host("h")
        val sink = UriBuilderSink(b)
        sink.addQueryParameter("q", "hello world")
        val uri = b.build()
        assertEquals("q=hello%20world", uri.query)
        assertEquals("hello world", uri.queryParameters().get("q"))
    }
}
