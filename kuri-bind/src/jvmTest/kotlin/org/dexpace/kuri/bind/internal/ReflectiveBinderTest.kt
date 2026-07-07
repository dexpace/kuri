/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.bind.internal

import org.dexpace.kuri.Url
import org.dexpace.kuri.bind.BindOptions
import org.dexpace.kuri.bind.Host
import org.dexpace.kuri.bind.KuriBindException
import org.dexpace.kuri.bind.Password
import org.dexpace.kuri.bind.Path
import org.dexpace.kuri.bind.PathTemplate
import org.dexpace.kuri.bind.Port
import org.dexpace.kuri.bind.Query
import org.dexpace.kuri.bind.Scheme
import org.dexpace.kuri.bind.Username
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.dexpace.kuri.bind.Url as BindUrl

@PathTemplate("/search/{category}/{tags...}")
private class Req(
    @Scheme val scheme: String,
    @Host val host: String,
    @Path("category") val category: String,
    @Path("tags") val tags: List<String>,
    @Query("q") val term: String,
)

private class Creds(
    @Scheme val scheme: String,
    @Host val host: String,
    @Username val user: String,
    @Password val secret: String,
)

private class QueryFilters(
    @Query("color") val color: String,
    @Query("size") val size: String,
)

private class ScopedSearch(
    @Scheme val scheme: String,
    @Host val host: String,
    @Query val filters: QueryFilters,
)

private class HostPortEndpoint(
    @Host val host: String,
    @Port val port: Int,
)

private class MergedCall(
    @Scheme val scheme: String,
    @BindUrl val endpoint: HostPortEndpoint,
)

private class Chain(
    @BindUrl val next: Chain?,
)

private class SelfRef(
    @Host val host: String,
    @BindUrl var self: SelfRef?,
)

class ReflectiveBinderTest {
    private val binder = ReflectiveUrlBinder(PlanCache(PlanCompiler(KotlinReflectMemberScanner())))

    @Test
    fun `binds a templated request end to end`() {
        val target = Req("https", "h", "shoes", listOf("a", "b"), "x y")
        val url = binder.bind(Url.Builder(), target, BindOptions.DEFAULT).build()
        assertEquals("https://h/search/shoes/a/b?q=x%20y", url.toString())
    }

    @Test
    fun `combines sibling username and password into one userinfo`() {
        val url = binder.bind(Url.Builder(), Creds("https", "h", "bob", "s3cret"), BindOptions.DEFAULT).build()
        assertEquals("https://bob:s3cret@h/", url.toString())
    }

    @Test
    fun `recurses a scoped query into the nested object's query members`() {
        val target = ScopedSearch("https", "h", QueryFilters("red", "large"))
        val url = binder.bind(Url.Builder(), target, BindOptions.DEFAULT).build()
        assertEquals("https://h/?color=red&size=large", url.toString())
    }

    @Test
    fun `merges a nested @Url sub-object host and port into the parent`() {
        val url =
            binder
                .bind(
                    Url.Builder(),
                    MergedCall("https", HostPortEndpoint("h", 8443)),
                    BindOptions.DEFAULT,
                ).build()
        assertEquals("https://h:8443/", url.toString())
    }

    @Test
    fun `fails when the bind depth is exceeded`() {
        val deep = Chain(Chain(Chain(null)))
        assertFailsWith<KuriBindException> {
            binder.bind(Url.Builder(), deep, BindOptions(maxDepth = 1))
        }
    }

    @Test
    fun `fails on a self-referential cycle`() {
        val node = SelfRef("h", null)
        node.self = node
        assertFailsWith<KuriBindException> {
            binder.bind(Url.Builder(), node, BindOptions.DEFAULT)
        }
    }
}
