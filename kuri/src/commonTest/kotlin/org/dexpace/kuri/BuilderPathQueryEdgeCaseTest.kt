/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Builder path/query edge cases around a rootless path whose first segment is emptied, an
 * append onto a path that has collapsed to a single empty segment, and a parameter edit that
 * follows an empty-name query pair. Each verifies the path/query shape is carried structurally
 * rather than being re-derived from a serialized string.
 */
class BuilderPathQueryEdgeCaseTest {
    @Test
    fun `resetting an emptied first segment of a rootless path keeps the other segments`() {
        // The rootless path holds its segments structurally, so emptying segment 0 and then
        // setting it again leaves the interior segments intact and the path rootless.
        val uri =
            Uri
                .Builder()
                .scheme("s")
                .encodedPath("a/b/c")
                .setPathSegment(0, "")
                .setPathSegment(0, "z")
                .build()

        assertEquals("s:z/b/c", uri.uriString)
        assertEquals(listOf("z", "b", "c"), uri.pathSegments)
    }

    @Test
    fun `a rootless path whose first segment was emptied is rejected under an authority`() {
        // A rootless path cannot follow an authority (RFC 3986 §3.3), so it is rejected at build
        // rather than silently re-rooted to fit the host.
        val built =
            Uri
                .Builder()
                .scheme("http")
                .host("h")
                .encodedPath("a/b/c")
                .setPathSegment(0, "")
                .setPathSegment(0, "z")
                .buildOrNull()

        assertNull(built?.uriString)
    }

    @Test
    fun `appending path segments onto a single empty segment is rejected`() {
        // Once the only segment is emptied the path is a single empty segment, distinct from the
        // empty path; appending keeps that leading empty, which cannot begin a rootless path.
        val built =
            Uri
                .Builder()
                .scheme("http")
                .host("h")
                .addPathSegment("x")
                .setPathSegment(0, "")
                .addPathSegments("/y")
                .buildOrNull()

        assertNull(built?.uriString)
    }

    @Test
    fun `an empty-name query pair survives a later parameter edit`() {
        // The empty-name, no-value pair is a real parameter and is preserved when a further
        // parameter is added.
        val uri =
            Uri
                .Builder()
                .scheme("http")
                .host("h")
                .encodedPath("/p")
                .addQueryParameter("", null)
                .addQueryParameter("x", "1")
                .build()

        assertEquals("&x=1", uri.query)
        assertEquals("http://h/p?&x=1", uri.uriString)
    }

    @Test
    fun `an empty-name query pair survives a later parameter edit on a url`() {
        val url =
            Url
                .Builder()
                .scheme("https")
                .host("h")
                .addQueryParameter("", null)
                .addQueryParameter("x", "1")
                .build()

        assertEquals("&x=1", url.query)
        assertEquals("https://h/?&x=1", url.href)
    }
}
