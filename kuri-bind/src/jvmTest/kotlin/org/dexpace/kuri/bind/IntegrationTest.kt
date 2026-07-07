/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.bind

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.dexpace.kuri.Url as KuriUrl

// A scalar-scoped `@Query` object: its own `@Query` members become sibling parameters of the parent.
private class PriceFilter(
    @Query("min") val min: Int,
    @Query("max") val max: Int,
)

// The spec headline: a templated path, a scalar query, a nested scoped-query object, and a query map.
@Url
@PathTemplate("/search/{category}/{tags...}")
private data class SearchRequest(
    @Scheme val scheme: String = "https",
    @Host val host: String = "example.com",
    @Path("category") val category: String,
    @Path("tags") val tags: List<String>,
    @Query("q") val term: String,
    @Query val filter: PriceFilter,
    @QueryMap val extra: Map<String, String>,
)

// A scheme-less/host-less root: it only contributes a path and a query, meant to append onto a base URL.
@Url
@PathTemplate("/search/{q}")
private class AppendedPath(
    @Path("q") val q: String,
    @Query("page") val page: Int,
)

// A shared `@Url` sub-object carrying host (+ optional port); merged into a parent under `@Url`.
@Url
private class Endpoint(
    @Host val host: String,
    @Port val port: Int? = null,
)

// Parent whose only host/port come from a nested `@Url` merge member.
@Url
private class MergeHostPort(
    @Scheme val scheme: String = "https",
    @Url val endpoint: Endpoint,
)

// Sibling split credentials on the URL profile.
@Url
private class UrlCreds(
    @Scheme val scheme: String = "https",
    @Host val host: String = "h",
    @Username val user: String = "u",
    @Password val pass: String = "p w",
)

// A whole userinfo token on the URI profile; split on the first ':' then re-encoded per part.
@Uri
private class UriCreds(
    @Scheme val scheme: String = "https",
    @Host val host: String = "h",
    @UserInfo val creds: String = "u:p w",
)

// A `@Uri` root with no scheme or authority: a relative reference carrying only a path.
@Uri
@PathTemplate("/a/{id}")
private class RelativeUri(
    @Path("id") val id: String,
)

// Positional collections: a list path (multiple segments) and a list query (repeated params).
@Url
private class WithCollections(
    @Scheme val scheme: String = "https",
    @Host val host: String = "h",
    @Path val more: List<String>,
    @Query("tag") val tags: List<String>,
)

// A query map whose values may themselves be collections (repeated params for that key).
@Url
private class WithQueryMap(
    @Scheme val scheme: String = "https",
    @Host val host: String = "h",
    @QueryMap val params: Map<String, Any>,
)

// A root with a null member alongside a present one: the null contributes nothing.
@Url
private class WithNulls(
    @Scheme val scheme: String = "https",
    @Host val host: String = "h",
    @Query("a") val a: String? = null,
    @Query("b") val b: String? = "yes",
)

// A parent host plus a merged child host: a single-valued conflict under first-writer-wins.
@Url
private class ParentWithHost(
    @Scheme val scheme: String = "https",
    @Host val host: String = "parent",
    @Url val child: Endpoint,
)

// A self-referential `@Url` merge: the walk must reject the cycle.
@Url
private class SelfNode(
    @Host val host: String = "h",
    @Url var self: SelfNode? = null,
)

// A linear `@Url` merge chain used to overflow a small `maxDepth`.
@Url
private class DepthChain(
    @Host val host: String = "h",
    @Url val next: DepthChain? = null,
)

// A `@Uri` root, used to prove that binding it through the URL entry point fails.
@Uri
private class UriOnly(
    @Scheme val scheme: String = "https",
    @Host val host: String = "h",
)

// A `Map` under `@Query` (rather than `@QueryMap`): rejected at compile time, fail-fast.
@Url
private class BadQuery(
    @Scheme val scheme: String = "https",
    @Host val host: String = "h",
    @Query("m") val m: Map<String, String>,
)

// A merge sub-object supplying a single-segment template hole.
@Url
private class IdCarrier(
    @Path("id") val id: String,
)

// A merge sub-object supplying a catch-all template hole.
@Url
private class FilesCarrier(
    @Path("path") val files: List<String>,
)

// A templated root whose `{id}` and `{path...}` holes are BOTH provided by `@Url`-merged
// sub-objects. The template owns the path, so the merge members contribute no positional segments.
@Url
@PathTemplate("/users/{id}/files/{path...}")
private class TemplatedMerge(
    @Scheme val scheme: String = "https",
    @Host val host: String = "h",
    @Url val ids: IdCarrier,
    @Url val paths: FilesCarrier,
)

// A templated root mixing a root-declared `{id}` hole with a catch-all `{path...}` from a merge.
@Url
@PathTemplate("/users/{id}/files/{path...}")
private class TemplatedMixedMerge(
    @Scheme val scheme: String = "https",
    @Host val host: String = "h",
    @Path("id") val id: String,
    @Url val paths: FilesCarrier,
)

// A fully-specified object bound onto a base: its own scheme/host override the base's values.
@Url
private class OverridingRequest(
    @Scheme val scheme: String = "http",
    @Host val host: String = "object.example",
)

// Leaf-like `@Path`/`@Query` members whose types carry no binding annotations: they stringify as one
// segment/parameter (toString) rather than recursing into nothing.
@Url
private class LeafLikeMembers(
    @Scheme val scheme: String = "https",
    @Host val host: String = "h",
    @Path val since: Instant,
    @Query("id") val id: UUID,
    @Query("amount") val amount: BigDecimal,
)

// A complex `@Query` member whose type DOES carry binding annotations: it must still recurse.
@Url
private class NestedQuery(
    @Scheme val scheme: String = "https",
    @Host val host: String = "h",
    @Query val filter: PriceFilter,
)

// A primitive array under `@Query`: each element becomes a repeated parameter.
@Url
private class ArrayQuery(
    @Scheme val scheme: String = "https",
    @Host val host: String = "h",
    @Query("b") val bytes: ByteArray,
)

// A single-segment `{id}` hole fed by a collection value: rejected fail-fast (it cannot be one segment).
@Url
@PathTemplate("/x/{id}")
private class SingleHoleFedCollection(
    @Scheme val scheme: String = "https",
    @Host val host: String = "h",
    @Path("id") val id: List<String>,
)

// A root whose only userinfo member is an empty username with no password: an all-empty userinfo is
// treated as no contribution rather than an error.
@Url
private class EmptyUserInfo(
    @Scheme val scheme: String = "https",
    @Host val host: String = "h",
    @Username val user: String = "",
)

// A query map whose entry key is the empty string: an empty query-parameter name is rejected fail-fast.
@Url
private class EmptyQueryKey(
    @Scheme val scheme: String = "https",
    @Host val host: String = "h",
    @QueryMap val params: Map<String, String>,
)

// A nested object whose own `@Path` leaves fill the path when it is bound as a complex `@Path` member.
private class LatLon(
    @Path val lat: String,
    @Path val lon: String,
)

// A complex `@Path` member: PATH-scoped recursion folds LatLon's own segments into the parent path.
@Url
private class GeoRequest(
    @Scheme val scheme: String = "https",
    @Host val host: String = "h",
    @Path val at: LatLon,
)

// Two positional scalar `@Path` members on a Kotlin data class: they bind in primary-constructor order
// (zeta then alpha), which a name-sorted order would reverse.
@Url
private data class TwoSegments(
    @Scheme val scheme: String = "https",
    @Host val host: String = "h",
    @Path val zeta: String,
    @Path val alpha: String,
)

/**
 * End-to-end coverage of the binding stack driven through the public [KuriBind] facade: the spec's
 * headline example plus the cross-cutting edge cases (merge, userinfo, collections, query maps, nulls,
 * strict conflicts, cycles, depth, profile mismatch). Behaviour is asserted through the built value's
 * `toString()` and read model.
 */
class IntegrationTest {
    @Test
    fun `binds the headline search request across path template query filter and query map`() {
        val request =
            SearchRequest(
                category = "shoes",
                tags = listOf("hiking", "trail"),
                term = "warm socks",
                filter = PriceFilter(min = 10, max = 50),
                extra = linkedMapOf("page" to "2", "sort" to "asc"),
            )
        val url = KuriBind.toUrl(request)
        val expected = "https://example.com/search/shoes/hiking/trail?q=warm%20socks&min=10&max=50&page=2&sort=asc"
        assertEquals(expected, url.toString())
        assertEquals(listOf("search", "shoes", "hiking", "trail"), url.pathSegments)
    }

    @Test
    fun `binds onto a base url keeping its scheme and host while appending path and query`() {
        val base = KuriUrl.parseOrThrow("https://api.example.com/v1")
        val url = KuriBind.bindInto(base.newBuilder(), AppendedPath(q = "foo", page = 2)).build()
        assertEquals("https://api.example.com/v1/search/foo?page=2", url.toString())
        assertEquals("api.example.com", url.host?.asText())
    }

    @Test
    fun `merges a nested url object host and port into the parent`() {
        val url = KuriBind.toUrl(MergeHostPort(endpoint = Endpoint(host = "h", port = 8443)))
        assertEquals("https://h:8443/", url.toString())
        assertEquals(8443, url.port)
    }

    @Test
    fun `pairs sibling username and password into split url userinfo`() {
        val url = KuriBind.toUrl(UrlCreds())
        assertEquals("u", url.username)
        assertEquals("p%20w", url.password)
        assertEquals("https://u:p%20w@h/", url.toString())
    }

    @Test
    fun `encodes a whole userinfo token on the uri profile`() {
        val uri = KuriBind.toUri(UriCreds())
        assertEquals("u:p%20w", uri.userInfo)
        assertEquals("https://u:p%20w@h", uri.toString())
    }

    @Test
    fun `binds a scheme-less uri as a rootless relative reference`() {
        val uri = KuriBind.toUri(RelativeUri(id = "7"))
        assertEquals("a/7", uri.toString())
        assertNull(uri.scheme)
    }

    @Test
    fun `expands list members into repeated path segments and query parameters`() {
        val url = KuriBind.toUrl(WithCollections(more = listOf("x", "y"), tags = listOf("a", "b")))
        assertEquals("https://h/x/y?tag=a&tag=b", url.toString())
        assertEquals(listOf("x", "y"), url.pathSegments)
    }

    @Test
    fun `expands a query map with a collection value and preserves entry order`() {
        val params = linkedMapOf<String, Any>("a" to listOf("1", "2"), "b" to "3")
        val url = KuriBind.toUrl(WithQueryMap(params = params))
        assertEquals("https://h/?a=1&a=2&b=3", url.toString())
        assertEquals("a=1&a=2&b=3", url.query)
    }

    @Test
    fun `skips a component when its member value is null`() {
        val url = KuriBind.toUrl(WithNulls())
        assertEquals("https://h/?b=yes", url.toString())
        assertEquals("b=yes", url.query)
    }

    @Test
    fun `strict mode rejects a conflicting merged host`() {
        val target = ParentWithHost(child = Endpoint(host = "child"))
        val failure =
            assertFailsWith<KuriBindException> {
                KuriBind.toUrl(target, BindOptions(strict = true))
            }
        assertTrue(failure.message.orEmpty().contains("conflicting"))
    }

    @Test
    fun `non-strict mode keeps the parent host on a merge conflict`() {
        val url = KuriBind.toUrl(ParentWithHost(child = Endpoint(host = "child")))
        assertEquals("https://parent/", url.toString())
        assertEquals("parent", url.host?.asText())
    }

    @Test
    fun `detects a self-referential merge cycle`() {
        val node = SelfNode()
        node.self = node
        val failure = assertFailsWith<KuriBindException> { KuriBind.toUrl(node) }
        assertTrue(failure.message.orEmpty().contains("cycle"))
    }

    @Test
    fun `rejects an object graph deeper than the depth bound`() {
        val deep = DepthChain(next = DepthChain(next = DepthChain()))
        val failure =
            assertFailsWith<KuriBindException> {
                KuriBind.toUrl(deep, BindOptions(maxDepth = 1))
            }
        assertTrue(failure.message.orEmpty().contains("depth"))
    }

    @Test
    fun `rejects a uri root bound through the url entry point`() {
        val failure = assertFailsWith<KuriBindException> { KuriBind.toUrl(UriOnly()) }
        assertTrue(failure.message.orEmpty().contains("@url"))
    }

    @Test
    fun `returns null for a uri root through toUrlOrNull but binds through the uri entry point`() {
        val target = UriOnly()
        assertNull(KuriBind.toUrlOrNull(target))
        assertNotNull(KuriBind.toUri(target))
    }

    @Test
    fun `rejects a map under query at bind time`() {
        val failure =
            assertFailsWith<KuriBindException> {
                KuriBind.toUrl(BadQuery(m = mapOf("a" to "b")))
            }
        assertTrue(failure.message.orEmpty().contains("@QueryMap"))
    }

    @Test
    fun `resolves template holes supplied across an @Url merge`() {
        val url = KuriBind.toUrl(TemplatedMerge(ids = IdCarrier("42"), paths = FilesCarrier(listOf("a", "b"))))
        assertEquals("https://h/users/42/files/a/b", url.toString())
        assertEquals(listOf("users", "42", "files", "a", "b"), url.pathSegments)
    }

    @Test
    fun `resolves a template hole from the root and a catch-all from a merge`() {
        val url = KuriBind.toUrl(TemplatedMixedMerge(id = "42", paths = FilesCarrier(listOf("a", "b"))))
        assertEquals("https://h/users/42/files/a/b", url.toString())
        assertEquals(listOf("users", "42", "files", "a", "b"), url.pathSegments)
    }

    @Test
    fun `object single-valued components override the base builder`() {
        val base = KuriUrl.parseOrThrow("https://base.example/v1")
        val url = KuriBind.bindInto(base.newBuilder(), OverridingRequest()).build()
        assertEquals("http", url.scheme)
        assertEquals("object.example", url.host?.asText())
        assertEquals("http://object.example/v1", url.toString())
    }

    @Test
    fun `renders leaf-like query and path members via toString`() {
        val id = UUID.fromString("00000000-0000-0000-0000-00000000002a")
        val since = Instant.parse("2020-01-02T03:04:05Z")
        val url = KuriBind.toUrl(LeafLikeMembers(since = since, id = id, amount = BigDecimal("12.50")))
        assertEquals(listOf(since.toString()), url.pathSegments)
        assertEquals("id=$id&amount=12.50", url.query)
    }

    @Test
    fun `a complex query member with annotated members still recurses`() {
        val url = KuriBind.toUrl(NestedQuery(filter = PriceFilter(min = 1, max = 2)))
        assertEquals("https://h/?min=1&max=2", url.toString())
    }

    @Test
    fun `expands a primitive byte array query into repeated parameters`() {
        val url = KuriBind.toUrl(ArrayQuery(bytes = byteArrayOf(1, 2, 3)))
        assertEquals("https://h/?b=1&b=2&b=3", url.toString())
    }

    @Test
    fun `rejects a single-segment template hole fed by a collection`() {
        val failure =
            assertFailsWith<KuriBindException> {
                KuriBind.toUrl(SingleHoleFedCollection(id = listOf("a", "b")))
            }
        assertTrue(failure.message.orEmpty().contains("single-segment"))
    }

    @Test
    fun `getter-only bean binds path members in a stable name-sorted order`() {
        val url = KuriBind.toUrl(OrderedBean())
        // Getters have no reliable reflective order, so the @Path segments sort by name.
        assertEquals(listOf("a", "m", "z"), url.pathSegments)
        assertEquals("https://h/a/m/z", url.toString())
    }

    @Test
    fun `treats an all-empty userinfo as no contribution`() {
        val url = KuriBind.toUrl(EmptyUserInfo())
        assertEquals("https://h/", url.toString())
        assertEquals("h", url.host?.asText())
    }

    @Test
    fun `rejects a query map entry with an empty key`() {
        val failure =
            assertFailsWith<KuriBindException> {
                KuriBind.toUrl(EmptyQueryKey(params = mapOf("" to "v")))
            }
        assertTrue(failure.message.orEmpty().contains("query parameter name"))
    }

    @Test
    fun `recurses a complex path member into its nested path segments`() {
        val url = KuriBind.toUrl(GeoRequest(at = LatLon(lat = "40", lon = "30")))
        assertEquals("https://h/40/30", url.toString())
        assertEquals(listOf("40", "30"), url.pathSegments)
    }

    @Test
    fun `binds two positional path scalars in primary-constructor order`() {
        // Constructor order is zeta then alpha; a name-sorted order would instead yield /a/z.
        val url = KuriBind.toUrl(TwoSegments(zeta = "z", alpha = "a"))
        assertEquals("https://h/z/a", url.toString())
        assertEquals(listOf("z", "a"), url.pathSegments)
    }
}
