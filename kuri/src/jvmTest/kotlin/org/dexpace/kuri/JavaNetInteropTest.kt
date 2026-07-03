/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri

import java.net.URI
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class JavaNetInteropTest {
    @Test
    fun `Url toJavaUri equals the java net URI of the same string`() {
        val url: Url = Url.parse("https://h/a/b?q=1#f").getOrThrow()

        assertEquals(URI("https://h/a/b?q=1#f"), url.toJavaUri())
    }

    @Test
    fun `Url toJavaUrl round-trips the href`() {
        val url: Url = Url.parse("https://h/a/b?q=1#f").getOrThrow()

        val javaUrl: URL = url.toJavaUrl()

        assertEquals(url.href, javaUrl.toString())
    }

    @Test
    fun `Uri toJavaUri bridges a mailto to its canonical string`() {
        // kuri preserves the scheme-rootless path, so the canonical form is "mailto:a@b".
        val uri: Uri = Uri.parse("mailto:a@b").getOrThrow()

        val javaUri: URI = uri.toJavaUri()

        assertEquals("mailto:a@b", uri.uriString)
        assertEquals(URI(uri.uriString), javaUri)
        assertEquals("mailto", javaUri.scheme)
    }

    @Test
    fun `java net URI toKuriUrl is Ok and exposes the scheme`() {
        val parsed = URI("http://h/p").toKuriUrl()

        assertTrue(parsed.isOk(), "expected http://h/p to parse as a Url")
        assertEquals("http", parsed.getOrThrow().scheme)
    }

    @Test
    fun `java net URI toKuriUri is Ok for a urn`() {
        val parsed = URI("urn:x:y").toKuriUri()

        assertTrue(parsed.isOk(), "expected urn:x:y to parse as a Uri")
        assertEquals("urn", parsed.getOrThrow().scheme)
    }

    @Test
    fun `java net URL round-trips to a kuri Url`() {
        val origin: Url = Url.parse("https://example.com/path?k=v#frag").getOrThrow()
        val javaUrl: URL = origin.toJavaUrl()

        val back: Url = javaUrl.toKuriUrl().getOrThrow()

        assertEquals(origin.href, back.href)
    }

    @Test
    fun `kuri Url parse static-style and accessor-style API is reachable`() {
        // Exercises the @JvmStatic factory and the @get:JvmName accessors from Kotlin call sites.
        val url: Url = Url.parse("https://user@host:8443/p").getOrThrow()

        assertEquals("https", url.scheme)
        assertEquals("host", url.hostName ?: fail("expected a host name"))
    }
}
