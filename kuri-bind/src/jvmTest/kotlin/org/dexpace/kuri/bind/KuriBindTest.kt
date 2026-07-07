/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.bind

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
}
