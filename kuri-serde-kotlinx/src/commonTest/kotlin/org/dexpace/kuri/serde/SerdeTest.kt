/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.serde

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.dexpace.kuri.Uri
import org.dexpace.kuri.Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@Serializable
private data class Config(
    @Serializable(with = UrlSerializer::class) val endpoint: Url,
)

@Serializable
private data class UriConfig(
    @Serializable(with = UriSerializer::class) val target: Uri,
)

@Serializable
private enum class Sort { ASC, DESC }

@Serializable
private data class Search(
    val q: String,
    val page: Int = 1,
    val sort: Sort = Sort.ASC,
    val tags: List<String> = emptyList(),
)

@Serializable
private data class AllScalars(
    val text: String,
    val flag: Boolean,
    val byteValue: Byte,
    val shortValue: Short,
    val intValue: Int,
    val longValue: Long,
    val floatValue: Float,
    val doubleValue: Double,
    val charValue: Char,
    val sort: Sort,
)

@Serializable
private data class AllLists(
    val bytes: List<Byte>,
    val shorts: List<Short>,
    val ints: List<Int>,
    val longs: List<Long>,
    val floats: List<Float>,
    val doubles: List<Double>,
    val chars: List<Char>,
    val flags: List<Boolean>,
    val sorts: List<Sort>,
)

@Serializable
private data class Contact(
    val name: String,
    val nickname: String? = null,
)

@Serializable
private data class Nested(
    val inner: Search,
)

@Serializable
private data class Flag(
    val flag: Boolean,
)

@Serializable
private data class Flags(
    val flags: List<Boolean>,
)

class SerdeTest {
    @Test
    fun `url serializes as its canonical string in json`() {
        val json = Json.encodeToString(Config(Url.parseOrThrow("https://example.com/")))
        assertEquals("""{"endpoint":"https://example.com/"}""", json)
    }

    @Test
    fun `url round-trips through json`() {
        val original = Config(Url.parseOrThrow("https://example.com/a/b?q=1"))
        val restored = Json.decodeFromString<Config>(Json.encodeToString(original))
        assertEquals(original, restored)
    }

    @Test
    fun `encode a flat class to a query string`() {
        val query =
            QueryParametersFormat.encodeToQueryString(
                Search(q = "kotlin", page = 2, sort = Sort.DESC, tags = listOf("a", "b")),
            )
        assertEquals("q=kotlin&page=2&sort=DESC&tags=a&tags=b", query)
    }

    @Test
    fun `decode a flat class from a query string`() {
        val search = QueryParametersFormat.decodeFromQueryString<Search>("q=kotlin&page=2&sort=DESC&tags=a&tags=b")
        assertEquals(Search("kotlin", 2, Sort.DESC, listOf("a", "b")), search)
    }

    @Test
    fun `absent optional parameters fall back to declared defaults`() {
        val search = QueryParametersFormat.decodeFromQueryString<Search>("q=only")
        assertEquals(Search(q = "only"), search)
        assertEquals(1, search.page)
        assertEquals(Sort.ASC, search.sort)
        assertEquals(emptyList(), search.tags)
    }

    @Test
    fun `decode then encode is stable`() {
        // Every value here deliberately differs from its declared default, since encoding omits a
        // default-valued property — otherwise re-encoding sort=ASC (its default) would drop `sort` and
        // break the round-trip.
        val query = "q=kotlin&page=3&sort=DESC&tags=x"
        val roundTripped =
            QueryParametersFormat.encodeToQueryString(
                QueryParametersFormat.decodeFromQueryString<Search>(query),
            )
        assertEquals(query, roundTripped)
    }

    @Test
    fun `encoding omits a property left at its declared default`() {
        // Search(q = "only") leaves page, sort, and tags at their declared defaults (1, ASC, emptyList);
        // none of them should appear in the encoded query string.
        assertEquals("q=only", QueryParametersFormat.encodeToQueryString(Search(q = "only")))
    }

    @Test
    fun `an invalid url fails to deserialize`() {
        assertFailsWith<SerializationException> {
            Json.decodeFromString<Config>("""{"endpoint":"http://["}""")
        }
    }

    @Test
    fun `uri serializes as its canonical string in json`() {
        val json = Json.encodeToString(UriConfig(Uri.parseOrThrow("foo://h/p")))
        assertEquals("""{"target":"foo://h/p"}""", json)
    }

    @Test
    fun `uri round-trips through json`() {
        val original = UriConfig(Uri.parseOrThrow("foo://h/a/b?q=1"))
        val restored = Json.decodeFromString<UriConfig>(Json.encodeToString(original))
        assertEquals(original, restored)
    }

    @Test
    fun `an invalid uri fails to deserialize`() {
        assertFailsWith<SerializationException> {
            Json.decodeFromString<UriConfig>("""{"target":"http://h:99999999999999999999/p"}""")
        }
    }

    @Test
    fun `every scalar kind round-trips through the query format`() {
        val original =
            AllScalars(
                text = "kotlin",
                flag = true,
                byteValue = 7,
                shortValue = 42,
                intValue = 100,
                longValue = 123_456_789L,
                floatValue = 1.5f,
                doubleValue = 2.25,
                charValue = 'z',
                sort = Sort.DESC,
            )
        val query = QueryParametersFormat.encodeToQueryString(original)
        assertEquals(original, QueryParametersFormat.decodeFromQueryString<AllScalars>(query))
    }

    @Test
    fun `every list element kind round-trips through the query format`() {
        val original =
            AllLists(
                bytes = listOf(1, 2, 3),
                shorts = listOf(10, 20),
                ints = listOf(100, 200),
                longs = listOf(1_000L, 2_000L),
                floats = listOf(1.5f, 2.5f),
                doubles = listOf(1.1, 2.2),
                chars = listOf('a', 'b'),
                flags = listOf(true, false),
                sorts = listOf(Sort.ASC, Sort.DESC),
            )
        val query = QueryParametersFormat.encodeToQueryString(original)
        assertEquals(original, QueryParametersFormat.decodeFromQueryString<AllLists>(query))
    }

    @Test
    fun `a null optional value is omitted when encoding and decodes back to null`() {
        val withoutNickname = Contact(name = "ada", nickname = null)
        val query = QueryParametersFormat.encodeToQueryString(withoutNickname)
        assertEquals("name=ada", query)
        assertEquals(withoutNickname, QueryParametersFormat.decodeFromQueryString<Contact>(query))
    }

    @Test
    fun `a present optional value round-trips`() {
        val withNickname = Contact(name = "ada", nickname = "the countess")
        val query = QueryParametersFormat.encodeToQueryString(withNickname)
        assertEquals(withNickname, QueryParametersFormat.decodeFromQueryString<Contact>(query))
    }

    @Test
    fun `decoding an unrecognized enum value throws`() {
        assertFailsWith<SerializationException> {
            QueryParametersFormat.decodeFromQueryString<Search>("q=x&sort=SIDEWAYS")
        }
    }

    @Test
    fun `decoding a numeric boolean value throws instead of silently coercing to false`() {
        assertFailsWith<SerializationException> {
            QueryParametersFormat.decodeFromQueryString<Flag>("flag=1")
        }
    }

    @Test
    fun `decoding a word other than true or false throws instead of silently coercing to false`() {
        assertFailsWith<SerializationException> {
            QueryParametersFormat.decodeFromQueryString<Flag>("flag=yes")
        }
    }

    @Test
    fun `decoding a misspelled boolean value throws instead of silently coercing to false`() {
        assertFailsWith<SerializationException> {
            QueryParametersFormat.decodeFromQueryString<Flag>("flag=ture")
        }
    }

    @Test
    fun `decoding an invalid boolean list element throws instead of silently coercing to false`() {
        assertFailsWith<SerializationException> {
            QueryParametersFormat.decodeFromQueryString<Flags>("flags=true&flags=nope")
        }
    }

    @Test
    fun `decoding an empty boolean value throws instead of silently coercing to false`() {
        assertFailsWith<SerializationException> {
            QueryParametersFormat.decodeFromQueryString<Flag>("flag=")
        }
    }

    @Test
    fun `decoding a whitespace-padded boolean value throws instead of trimming`() {
        assertFailsWith<SerializationException> {
            QueryParametersFormat.decodeFromQueryString<Flag>("flag=%20true")
        }
    }

    @Test
    fun `decoding true and false boolean values still succeeds`() {
        assertEquals(Flag(flag = true), QueryParametersFormat.decodeFromQueryString<Flag>("flag=true"))
        assertEquals(Flag(flag = false), QueryParametersFormat.decodeFromQueryString<Flag>("flag=false"))
    }

    @Test
    fun `decoding a boolean value is case-insensitive`() {
        assertEquals(Flag(flag = true), QueryParametersFormat.decodeFromQueryString<Flag>("flag=TRUE"))
        assertEquals(Flag(flag = false), QueryParametersFormat.decodeFromQueryString<Flag>("flag=FALSE"))
    }

    @Test
    fun `a missing required parameter fails to decode`() {
        assertFailsWith<SerializationException> {
            QueryParametersFormat.decodeFromQueryString<Search>("page=1")
        }
    }

    @Test
    fun `a null list element fails to decode`() {
        assertFailsWith<SerializationException> {
            QueryParametersFormat.decodeFromQueryString<Search>("q=x&tags&tags=a")
        }
    }

    @Test
    fun `decoding a nested serializable object is rejected`() {
        assertFailsWith<SerializationException> {
            QueryParametersFormat.decodeFromQueryString<Nested>("inner=x")
        }
    }

    @Test
    fun `encoding a nested serializable object is rejected`() {
        assertFailsWith<SerializationException> {
            QueryParametersFormat.encodeToQueryParameters(Nested(Search(q = "x")))
        }
    }
}
