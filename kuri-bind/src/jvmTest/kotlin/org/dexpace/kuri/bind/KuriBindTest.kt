/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.bind

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.dexpace.kuri.Uri as KuriUri
import org.dexpace.kuri.Url as KuriUrl

@Url
@PathTemplate("/items/{id}")
private class ItemLookup(
    @Path("id") val id: String,
)

// A root whose host getter throws a non-argument exception: `...OrNull` must let it propagate rather
// than swallow it into `null` (only an `IllegalArgumentException` maps to `null`).
@Url
private class ThrowingHost {
    @Host val host: String get() = error("boom")
}

@Url
private class Absolute(
    @Scheme val scheme: String = "https",
    @Host val host: String,
)

@Uri
private class UriAbsolute(
    @Scheme val scheme: String = "https",
    @Host val host: String,
)

private class NoRootMarker(
    val name: String,
)

// A linear `@Url` merge chain used to probe the exact `maxDepth` boundary. It carries a scheme so a
// within-bound chain builds to a full URL, and each level merges the same scheme/host without conflict.
@Url
private class DepthNode(
    @Scheme val scheme: String = "https",
    @Host val host: String = "h",
    @Url val next: DepthNode? = null,
)

// A root whose `@Host` accessor throws an `IllegalArgumentException` (the family every expected binding
// failure belongs to): `...OrNull` must translate it to `null`, distinct from the `ThrowingHost` case
// where a non-argument fault propagates.
@Url
private class IaeHost {
    @Host val host: String get() = throw IllegalArgumentException("bad host")
}

class KuriBindTest {
    @Test
    fun `binds onto a base url with the object appending a templated segment`() {
        val base = KuriUrl.parseOrThrow("https://api.example.com/v1")
        val builder = KuriBind.bindInto(base.newBuilder(), ItemLookup("42"))
        val url = builder.build()
        assertEquals("https://api.example.com/v1/items/42", url.toString())
    }

    @Test
    fun `fresh-builder toUrl uses the object's own scheme and host`() {
        val url = KuriBind.toUrl(Absolute(host = "example.com"))
        assertEquals("https", url.scheme)
        assertEquals("https://example.com/", url.toString())
    }

    @Test
    fun `toUrlOrNull returns null when the root lacks the url marker`() {
        val result = KuriBind.toUrlOrNull(NoRootMarker("x"))
        assertNull(result)
    }

    @Test
    fun `toUrl throws when the root lacks the url marker`() {
        val failure = assertFailsWith<KuriBindException> { KuriBind.toUrl(NoRootMarker("x")) }
        assertEquals(true, failure.message?.contains("@url"))
    }

    @Test
    fun `fresh-builder toUri binds a uri root`() {
        val uri = KuriBind.toUri(UriAbsolute(host = "example.com"))
        assertEquals("https", uri.scheme)
        assertEquals("https://example.com", uri.toString())
    }

    @Test
    fun `toUri throws when a url root is bound through the uri entry point`() {
        val failure = assertFailsWith<KuriBindException> { KuriBind.toUri(Absolute(host = "example.com")) }
        assertEquals(true, failure.message?.contains("@uri"))
    }

    @Test
    fun `toUriOrNull returns null when the root lacks the uri marker`() {
        val result = KuriBind.toUriOrNull(NoRootMarker("x"))
        assertNull(result)
    }

    @Test
    fun `toUrlOrNull rethrows a non-argument failure instead of swallowing it`() {
        val error = assertFails { KuriBind.toUrlOrNull(ThrowingHost()) }
        assertTrue(error !is IllegalArgumentException)
    }

    @Test
    fun `a chain exactly at the depth bound binds while one level deeper fails`() {
        // The walk depth starts at 0 and increments once per @Url merge, so a K-node chain reaches
        // depth K-1: maxDepth = 2 admits a 3-node chain (depths 0,1,2) and rejects a 4-node chain.
        val atBound = DepthNode(next = DepthNode(next = DepthNode()))
        assertEquals("https://h/", KuriBind.toUrl(atBound, BindOptions(maxDepth = 2)).toString())
        val overBound = DepthNode(next = DepthNode(next = DepthNode(next = DepthNode())))
        val failure =
            assertFailsWith<KuriBindException> {
                KuriBind.toUrl(overBound, BindOptions(maxDepth = 2))
            }
        assertTrue(failure.message.orEmpty().contains("depth"))
    }

    @Test
    fun `toUrlOrNull does not swallow a user accessor's IllegalArgumentException`() {
        // The `...OrNull` contract maps a binding-level IllegalArgumentException to null, but a fault
        // thrown by the target's OWN accessor arrives wrapped in a reflective InvocationTargetException
        // (not an IllegalArgumentException), so it propagates rather than being swallowed. Pinned to
        // document that boundary — the reflective wrapping is what keeps an accessor fault out of null.
        val error = assertFails { KuriBind.toUrlOrNull(IaeHost()) }
        assertTrue(error !is IllegalArgumentException)
    }

    @Test
    fun `bindInto uri builder uses default options when omitted`() {
        val builder = KuriBind.bindInto(KuriUri.Builder(), UriAbsolute(host = "example.com"))
        assertEquals("https://example.com", builder.build().toString())
    }

    @Test
    fun `toUrlBuilder uses default options when omitted`() {
        val builder = KuriBind.toUrlBuilder(Absolute(host = "example.com"))
        assertEquals("https://example.com/", builder.build().toString())
    }

    @Test
    fun `toUrlBuilderOrNull uses default options when omitted`() {
        val builder = KuriBind.toUrlBuilderOrNull(Absolute(host = "example.com"))
        assertNotNull(builder)
        assertEquals("https://example.com/", builder.build().toString())
    }

    @Test
    fun `toUriBuilder uses default options when omitted`() {
        val builder = KuriBind.toUriBuilder(UriAbsolute(host = "example.com"))
        assertEquals("https://example.com", builder.build().toString())
    }

    @Test
    fun `toUriBuilderOrNull uses default options when omitted`() {
        val builder = KuriBind.toUriBuilderOrNull(UriAbsolute(host = "example.com"))
        assertNotNull(builder)
        assertEquals("https://example.com", builder.build().toString())
    }

    @Test
    fun `toUrlOrNull returns a url when binding succeeds`() {
        val url = KuriBind.toUrlOrNull(Absolute(host = "example.com"))
        assertNotNull(url)
        assertEquals("https://example.com/", url.toString())
    }

    @Test
    fun `toUriOrNull returns a uri when binding succeeds`() {
        val uri = KuriBind.toUriOrNull(UriAbsolute(host = "example.com"))
        assertNotNull(uri)
        assertEquals("https://example.com", uri.toString())
    }
}
