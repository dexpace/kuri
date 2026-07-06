/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.bind.internal

import org.dexpace.kuri.Url
import org.dexpace.kuri.bind.KuriBindException
import org.dexpace.kuri.host.Host
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class ComponentSinkTest {
    private fun urlOf(build: ComponentSink.() -> Unit): Url {
        val builder = Url.Builder()
        ComponentSink(strict = false).apply(build).projectInto(UrlBuilderSink(builder))
        return builder.build()
    }

    @Test
    fun `first writer wins for single-valued host`() {
        val url =
            urlOf {
                setScheme("https", "scheme")
                setHost(HostValue.Text("first.example"), "a")
                setHost(HostValue.Text("second.example"), "b") // ignored
            }
        assertEquals("first.example", url.host?.asText())
    }

    @Test
    fun `path segments and query params accumulate in order`() {
        val url =
            urlOf {
                setScheme("https", "s")
                setHost(HostValue.Text("h"), "h")
                addPathSegment("a")
                addPathSegment("b")
                addQueryParameter("x", "1")
                addQueryParameter("x", "2")
            }
        assertEquals("/a/b", url.encodedPath)
        assertEquals("x=1&x=2", url.query)
    }

    @Test
    fun `strict mode rejects a conflicting single-valued write`() {
        assertFailsWith<KuriBindException> {
            val sink = ComponentSink(strict = true)
            sink.setPort(1, "a")
            sink.setPort(2, "b")
        }
    }

    @Test
    fun `strict mode allows an equal repeat`() {
        val sink = ComponentSink(strict = true)
        sink.setPort(8080, "a")
        sink.setPort(8080, "b") // equal → no conflict
    }

    @Test
    fun `single-valued scheme userinfo and fragment keep the first write`() {
        val url =
            urlOf {
                setScheme("https", "s")
                setScheme("http", "s2") // ignored
                setHost(HostValue.Text("h"), "h")
                setUserInfo("alice", "secret", "u")
                setUserInfo("bob", "other", "u2") // ignored
                setFragment("frag1", "f")
                setFragment("frag2", "f2") // ignored
            }
        assertEquals("https", url.scheme)
        assertEquals("alice", url.username)
        assertEquals("secret", url.password)
        assertEquals("frag1", url.encodedFragment)
    }

    @Test
    fun `raw path segments split and accumulate before later single segments`() {
        val url =
            urlOf {
                setScheme("https", "s")
                setHost(HostValue.Text("h"), "h")
                addPathSegmentsRaw("a/b")
                addPathSegment("c")
            }
        assertEquals("/a/b/c", url.encodedPath)
        assertEquals("h", url.host?.asText())
    }

    @Test
    fun `structured host projects onto the builder`() {
        val url =
            urlOf {
                setScheme("https", "s")
                setHost(HostValue.Structured(Host.RegName("structured.example")), "h")
            }
        assertEquals("structured.example", url.host?.asText())
        assertEquals("https", url.scheme)
    }

    @Test
    fun `unset single-valued slots are skipped and a set port projects`() {
        val builder = Url.Builder().scheme("https").host("preset.example")
        val sink = ComponentSink(strict = false)
        sink.setPort(8443, "p")
        sink.projectInto(UrlBuilderSink(builder))
        val url = builder.build()
        assertEquals(8443, url.port)
        assertEquals("preset.example", url.host?.asText())
    }

    @Test
    fun `a null query value projects the name without an equals sign`() {
        val url =
            urlOf {
                setScheme("https", "s")
                setHost(HostValue.Text("h"), "h")
                addQueryParameter("flag", null)
                addQueryParameter("x", "1")
            }
        assertEquals("flag&x=1", url.query)
        assertEquals("h", url.host?.asText())
    }

    @Test
    fun `addQueryParameter rejects an empty name`() {
        val sink = ComponentSink(strict = false)
        val error =
            assertFailsWith<IllegalArgumentException> {
                sink.addQueryParameter("", "v")
            }
        assertNotNull(error.message)
    }

    @Test
    fun `strict mode reports the failing component path`() {
        val sink = ComponentSink(strict = true)
        sink.setHost(HostValue.Text("first.example"), "endpoint.host")
        val error =
            assertFailsWith<KuriBindException> {
                sink.setHost(HostValue.Text("second.example"), "other.host")
            }
        assertEquals("other.host", error.path)
    }
}
