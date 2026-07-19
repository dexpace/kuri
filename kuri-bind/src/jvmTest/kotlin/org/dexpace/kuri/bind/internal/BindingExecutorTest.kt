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
import org.dexpace.kuri.bind.QueryMap
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

// A `@Query` member declared as `Any` compiles to a leaf (its erased declared type carries no
// binding members), but a bindable runtime value must recurse under the QUERY scope rather than
// stringify — exercising the runtime value-class dispatch on an otherwise leaf-compiled member.
private class ErasedQueryHolder(
    @Scheme val scheme: String,
    @Host val host: String,
    @Query val filters: Any,
)

// A scoped `@Query` object mixing a null-valued query member, a present one, and a stray `@Host` that
// scoped-query recursion must ignore (only QUERY/QUERY_MAP leaves are pulled): covers the scoped-leaf
// null skip and the leaf-op filter's non-matching arm.
private class NullableFilter(
    @Query("a") val a: String? = null,
    @Query("b") val b: String = "y",
    @Host val ignoredHost: String = "ignored",
)

private class ScopedNullable(
    @Scheme val scheme: String,
    @Host val host: String,
    @Query val filter: NullableFilter,
)

private class NullKeyMap(
    @Scheme val scheme: String,
    @Host val host: String,
    @QueryMap val params: Map<String?, String>,
)

private class NullValueMap(
    @Scheme val scheme: String,
    @Host val host: String,
    @QueryMap val params: Map<String, String?>,
)

// A non-delimited `@Query` list carrying a null element: it fans out, and the null element becomes a
// valueless parameter.
private class FanOutWithNull(
    @Scheme val scheme: String,
    @Host val host: String,
    @Query("t") val t: List<String?>,
)

// Only a `@Password` (no username): the username defaults to empty, so userinfo is password-only.
private class PasswordOnly(
    @Scheme val scheme: String,
    @Host val host: String,
    @Password val secret: String,
)

// A username with a null `@Password`: the password leaf reads null and is skipped, and the userinfo
// slot is a bare username.
private class NullablePassword(
    @Scheme val scheme: String,
    @Host val host: String,
    @Username val user: String,
    @Password val secret: String?,
)

private class PathLatLon(
    @Path val lat: String,
    @Path val lon: String,
)

// A `@Path` member typed as `Any` compiles to a leaf, yet a bindable runtime value must recurse under
// the PATH scope rather than stringify — the path-side twin of the erased-query dispatch.
private class ErasedPathHolder(
    @Scheme val scheme: String,
    @Host val host: String,
    @Path val at: Any,
)

// An erased `@Query` leaf whose runtime value is a `Map`: not bindable, so it stringifies as one param.
private class ErasedMapQuery(
    @Scheme val scheme: String,
    @Host val host: String,
    @Query("m") val m: Any,
)

private class Plain {
    override fun toString(): String = "plainobj"
}

// An erased `@Query` leaf whose runtime value is a plain object with no binding members: its empty plan
// makes it non-bindable, so it stringifies via toString.
private class ErasedPlainQuery(
    @Scheme val scheme: String,
    @Host val host: String,
    @Query("p") val p: Any,
)

// Scalar `@Query` values across the Char/Byte/Short/Long/Float scalar types: each stringifies to text.
private data class NumericScalars(
    @Scheme val scheme: String,
    @Host val host: String,
    @Query("c") val c: Char,
    @Query("by") val by: Byte,
    @Query("sh") val sh: Short,
    @Query("lo") val lo: Long,
    @Query("fl") val fl: Float,
)

// A templated root whose single hole is nullable and supplied as null: resolving the hole to null is a
// fail-fast bind error.
@PathTemplate("/u/{id}")
private class NullHole(
    @Path("id") val id: String?,
)

private class NullableIdCarrier(
    @Path("id") val id: String,
)

// A templated root whose `{id}` hole is provided across a nullable `@Url` merge set to null: the
// composed hole accessor short-circuits to null and the bind fails fast.
@PathTemplate("/u/{id}")
private class MergedNullHole(
    @BindUrl val carrier: NullableIdCarrier?,
)

// A catch-all `{p...}` hole fed a slash-delimited scalar string: it splits into whole segments.
@PathTemplate("/f/{p...}")
private class CatchAllScalar(
    @Scheme val scheme: String = "https",
    @Host val host: String = "h",
    @Path("p") val p: String,
)

private class HoleIdProvider(
    @Path("id") val id: String,
)

// Wraps the hole provider one merge level deeper, so the template hole is reached through two chained
// `@Url` merges rather than one.
private class HoleIdCarrier(
    @BindUrl val inner: HoleIdProvider,
)

// A templated root whose `{id}` hole is provided two merge levels down, with the OUTER merge member set
// to null: the composed accessor's upstream prefix short-circuits to null before the inner read, so the
// hole resolves to null and the bind fails fast — the null-prefix twin of MergedNullHole's null read.
@PathTemplate("/u/{id}")
private class NestedNullHole(
    @BindUrl val middle: HoleIdCarrier?,
)

// Issue #82 repro: a hole followed by a literal suffix sharing the same segment (`{id}.json`).
@PathTemplate("/reports/{id}.json")
private class ReportRequest(
    @Path("id") val id: Int,
)

// Issue #82 repro: a literal prefix followed by a hole sharing the same segment (`v{version}`).
@PathTemplate("v{version}")
private class VersionRequest(
    @Path("version") val version: Int,
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

    @Test
    fun `recurses an erased query leaf when its runtime value is bindable`() {
        val target = ErasedQueryHolder("https", "h", QueryFilters("red", "large"))
        assertEquals("https://h/?color=red&size=large", bind(target).build().toString())
    }

    @Test
    fun `skips a null member and a non-query leaf when recursing a scoped query object`() {
        val target = ScopedNullable("https", "h", NullableFilter())
        assertEquals("https://h/?b=y", bind(target).build().toString())
    }

    @Test
    fun `skips a query map entry with a null key`() {
        val target = NullKeyMap("https", "h", linkedMapOf(null to "x", "k" to "v"))
        assertEquals("https://h/?k=v", bind(target).build().toString())
    }

    @Test
    fun `emits a valueless parameter for a null query map value`() {
        val target = NullValueMap("https", "h", linkedMapOf("flag" to null, "k" to "v"))
        assertEquals("https://h/?flag&k=v", bind(target).build().toString())
    }

    @Test
    fun `emits a valueless parameter for a null element in a fanned out query list`() {
        val target = FanOutWithNull("https", "h", listOf("a", null, "b"))
        assertEquals("https://h/?t=a&t&t=b", bind(target).build().toString())
    }

    @Test
    fun `binds a password only object with an empty username`() {
        val url = bind(PasswordOnly("https", "h", "s3cret")).build()
        assertEquals("", url.username)
        assertEquals("s3cret", url.password)
    }

    @Test
    fun `pairs a username with a null password clearing the password slot`() {
        val url = bind(NullablePassword("https", "h", "bob", null)).build()
        assertEquals("bob", url.username)
        assertEquals("", url.password)
        assertEquals("https://bob@h/", url.toString())
    }

    @Test
    fun `recurses an erased path leaf when its runtime value is bindable`() {
        val target = ErasedPathHolder("https", "h", PathLatLon("40", "30"))
        assertEquals(listOf("40", "30"), bind(target).build().pathSegments)
    }

    @Test
    fun `stringifies an erased query leaf whose runtime value is a map`() {
        val target = ErasedMapQuery("https", "h", mapOf("a" to "1"))
        assertEquals(listOf("{a=1}"), bind(target).build().queryParameters.getAll("m"))
    }

    @Test
    fun `stringifies an erased query leaf whose runtime value is a non bindable object`() {
        val target = ErasedPlainQuery("https", "h", Plain())
        assertEquals(listOf("plainobj"), bind(target).build().queryParameters.getAll("p"))
    }

    @Test
    fun `binds char and numeric scalar query values via their text forms`() {
        val target = NumericScalars("https", "h", 'x', 1, 2, 3, 1.5f)
        assertEquals("c=x&by=1&sh=2&lo=3&fl=1.5", bind(target).build().query)
    }

    @Test
    fun `fails when a template hole resolves to null`() {
        assertFailsWith<KuriBindException> { bind(NullHole(null)) }
    }

    @Test
    fun `fails when a merged template hole provider is null`() {
        assertFailsWith<KuriBindException> { bind(MergedNullHole(null)) }
    }

    @Test
    fun `expands a catch-all hole fed a slash string into segments`() {
        assertEquals(listOf("f", "a", "b", "c"), bind(CatchAllScalar(p = "a/b/c")).build().pathSegments)
    }

    @Test
    fun `fails when an outer merge in a two-level template hole chain is null`() {
        // The inner hole accessor's upstream prefix returns null (the outer `middle` is null), so it
        // short-circuits before the inner read and the hole resolves to null — a fail-fast bind error.
        assertFailsWith<KuriBindException> { bind(NestedNullHole(null)) }
    }

    @Test
    fun `fails to bind a template whose hole shares a segment with a literal suffix`() {
        // Issue #82: "/reports/{id}.json" must not silently re-segment to ["reports", "5", ".json"].
        assertFailsWith<KuriBindException> { bind(ReportRequest(5)) }
    }

    @Test
    fun `fails to bind a template whose hole shares a segment with a literal prefix`() {
        // Issue #82: "v{version}" must not silently re-segment to ["v", "2"].
        assertFailsWith<KuriBindException> { bind(VersionRequest(2)) }
    }
}
