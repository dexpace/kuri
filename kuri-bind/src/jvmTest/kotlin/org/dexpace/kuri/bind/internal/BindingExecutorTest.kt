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

// A nullable-element list joined by a comma delimiter: covers the join, null-skip, empty, all-null,
// single-element, and order-preservation scenarios with one shared fixture.
private class DelimitedRoles(
    @Scheme val scheme: String,
    @Host val host: String,
    @Query("roles", ',') val roles: List<String?>,
)

private class DelimitedPerms(
    @Scheme val scheme: String,
    @Host val host: String,
    @Query("perm", '|') val perms: List<String>,
)

private class ScalarWithDelimiter(
    @Scheme val scheme: String,
    @Host val host: String,
    @Query("x", ',') val x: String,
)

// An unset delimiter (the default): must still fan out into repeated params, unchanged.
private class FanOutRoles(
    @Scheme val scheme: String,
    @Host val host: String,
    @Query("roles") val roles: List<String>,
)

private enum class Role { ADMIN, USER }

private class DelimitedEnumRoles(
    @Scheme val scheme: String,
    @Host val host: String,
    @Query("roles", ',') val roles: List<Role>,
)

class BindingExecutorTest {
    private val executor = BindingExecutor(PlanCompiler(KotlinReflectMemberScanner()))

    // Exercises the reflective walk end to end into a URL: this is exactly what the `@Url` entry point
    // does after root validation — run the executor, then project the accumulated components onto a
    // fresh Url.Builder through the URL sink.
    private fun bind(
        target: Any,
        options: BindOptions = BindOptions.DEFAULT,
    ): Url.Builder {
        val base = Url.Builder()
        executor.execute(target, options).projectInto(UrlBuilderSink(base))
        return base
    }

    @Test
    fun `binds a templated request end to end`() {
        val target = Req("https", "h", "shoes", listOf("a", "b"), "x y")
        assertEquals("https://h/search/shoes/a/b?q=x%20y", bind(target).build().toString())
    }

    @Test
    fun `combines sibling username and password into one userinfo`() {
        val url = bind(Creds("https", "h", "bob", "s3cret")).build()
        assertEquals("https://bob:s3cret@h/", url.toString())
    }

    @Test
    fun `recurses a scoped query into the nested object's query members`() {
        val target = ScopedSearch("https", "h", QueryFilters("red", "large"))
        assertEquals("https://h/?color=red&size=large", bind(target).build().toString())
    }

    @Test
    fun `merges a nested @Url sub-object host and port into the parent`() {
        val url = bind(MergedCall("https", HostPortEndpoint("h", 8443))).build()
        assertEquals("https://h:8443/", url.toString())
    }

    @Test
    fun `fails when the bind depth is exceeded`() {
        val deep = Chain(Chain(Chain(null)))
        assertFailsWith<KuriBindException> {
            bind(deep, BindOptions(maxDepth = 1))
        }
    }

    @Test
    fun `fails on a self-referential cycle`() {
        val node = SelfRef("h", null)
        node.self = node
        assertFailsWith<KuriBindException> {
            bind(node)
        }
    }

    @Test
    fun `joins a delimited query list into one comma separated parameter`() {
        val target = DelimitedRoles("https", "h", listOf("admin", "user"))
        assertEquals("https://h/?roles=admin,user", bind(target).build().toString())
    }

    @Test
    fun `joins a delimited query list with a pipe delimiter`() {
        val target = DelimitedPerms("https", "h", listOf("read", "write"))
        assertEquals("https://h/?perm=read|write", bind(target).build().toString())
    }

    @Test
    fun `skips a null element when joining a delimited query list`() {
        val target = DelimitedRoles("https", "h", listOf("admin", null, "user"))
        assertEquals("https://h/?roles=admin,user", bind(target).build().toString())
    }

    @Test
    fun `emits no parameter for an empty delimited query list`() {
        val target = DelimitedRoles("https", "h", emptyList())
        assertEquals("https://h/", bind(target).build().toString())
    }

    @Test
    fun `emits no parameter for an all null delimited query list`() {
        val target = DelimitedRoles("https", "h", listOf(null))
        assertEquals("https://h/", bind(target).build().toString())
    }

    @Test
    fun `retains an empty string element when joining a delimited query list`() {
        val target = DelimitedRoles("https", "h", listOf("", "user"))
        assertEquals("https://h/?roles=,user", bind(target).build().toString())
    }

    @Test
    fun `joins a single empty string element into an empty valued parameter`() {
        val target = DelimitedRoles("https", "h", listOf(""))
        assertEquals("https://h/?roles=", bind(target).build().toString())
    }

    @Test
    fun `joins a single element delimited query list without a trailing delimiter`() {
        val target = DelimitedRoles("https", "h", listOf("solo"))
        assertEquals("https://h/?roles=solo", bind(target).build().toString())
    }

    @Test
    fun `preserves element order when joining a delimited query list`() {
        val target = DelimitedRoles("https", "h", listOf("user", "admin"))
        assertEquals("https://h/?roles=user,admin", bind(target).build().toString())
    }

    @Test
    fun `ignores the delimiter on a scalar query field`() {
        val target = ScalarWithDelimiter("https", "h", "solo")
        assertEquals("https://h/?x=solo", bind(target).build().toString())
    }

    @Test
    fun `still fans out a query list with an unset delimiter`() {
        val target = FanOutRoles("https", "h", listOf("admin", "user"))
        assertEquals("https://h/?roles=admin&roles=user", bind(target).build().toString())
    }

    @Test
    fun `joins enum elements by name in a delimited query list`() {
        val target = DelimitedEnumRoles("https", "h", listOf(Role.ADMIN, Role.USER))
        assertEquals("https://h/?roles=ADMIN,USER", bind(target).build().toString())
    }
}
