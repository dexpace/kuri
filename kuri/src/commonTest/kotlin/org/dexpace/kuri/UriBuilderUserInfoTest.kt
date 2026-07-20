/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * [Uri.Builder]'s userinfo handling: the "authority requires a host" guard shared by
 * [Uri.Builder.userInfo], [Uri.Builder.username], [Uri.Builder.password], and [Uri.Builder.port],
 * and the split ([Uri.Builder.username]/[Uri.Builder.password]) vs. verbatim ([Uri.Builder.userInfo])
 * mode transitions. Split out of `UriBuilderTest` to keep that class within the detekt `LargeClass`
 * size budget.
 */
class UriBuilderUserInfoTest {
    @Test
    fun `userInfo without a host is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            Uri
                .Builder()
                .userInfo("u")
                .encodedPath("/p")
                .build()
        }
    }

    @Test
    fun `a port without a host is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            Uri
                .Builder()
                .port(8080)
                .encodedPath("/p")
                .build()
        }
    }

    @Test
    fun `userInfo with an empty host is allowed`() {
        val uri =
            Uri
                .Builder()
                .userInfo("u")
                .host("")
                .encodedPath("/p")
                .build()

        assertEquals("//u@/p", uri.uriString)
    }

    @Test
    fun `username and password setters percent-encode userinfo and join with a colon`() {
        val uri =
            Uri
                .Builder()
                .host("h")
                .username("a b")
                .password("p:w")
                .encodedPath("/p")
                .build()

        assertEquals("a%20b:p%3Aw", uri.userInfo)
    }

    @Test
    fun `username alone yields userInfo with no trailing colon`() {
        val uri =
            Uri
                .Builder()
                .host("h")
                .username("bob")
                .encodedPath("/p")
                .build()

        assertEquals("bob", uri.userInfo)
    }

    @Test
    fun `password alone yields userInfo with an empty username`() {
        val uri =
            Uri
                .Builder()
                .host("h")
                .password("pw")
                .encodedPath("/p")
                .build()

        assertEquals(":pw", uri.userInfo)
    }

    @Test
    fun `userInfo after username and password switches back to raw mode and wins`() {
        val uri =
            Uri
                .Builder()
                .host("h")
                .username("bob")
                .password("pw")
                .userInfo("raw:verbatim")
                .encodedPath("/p")
                .build()

        assertEquals("raw:verbatim", uri.userInfo)
    }

    @Test
    fun `a trailing colon in raw userInfo is preserved as an empty-but-present password`() {
        // The empty password after the trailing ':' is a present-but-empty field, distinct from no
        // password at all (Uri.reconstructUserInfo / SerializeShared.preservedCredentialsPrefix), so
        // "user:" reads back as "user:" — the point of this case is that verbatim mode recomposes
        // and re-parses to that same value rather than collapsing the trailing colon away.
        val uri =
            Uri
                .Builder()
                .host("h")
                .userInfo("user:")
                .encodedPath("/p")
                .build()

        assertEquals("user:", uri.userInfo)
    }

    @Test
    fun `a literal percent sign in a decoded username is escaped rather than left bare`() {
        val uri =
            Uri
                .Builder()
                .host("h")
                .username("50%")
                .encodedPath("/p")
                .build()

        assertEquals("50%25", uri.userInfo)
    }

    @Test
    fun `a literal percent sign in a decoded password is escaped rather than left bare`() {
        val uri =
            Uri
                .Builder()
                .host("h")
                .username("bob")
                .password("50%")
                .encodedPath("/p")
                .build()

        assertEquals("bob:50%25", uri.userInfo)
    }

    @Test
    fun `username without a host is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            Uri
                .Builder()
                .username("bob")
                .encodedPath("/p")
                .build()
        }
    }

    @Test
    fun `password without a host is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            Uri
                .Builder()
                .password("pw")
                .encodedPath("/p")
                .build()
        }
    }

    @Test
    fun `username with an empty host is allowed`() {
        val uri =
            Uri
                .Builder()
                .username("u")
                .host("")
                .encodedPath("/p")
                .build()

        assertEquals("//u@/p", uri.uriString)
    }

    @Test
    fun `username on a builder seeded from a parsed Uri overrides the inherited verbatim userInfo`() {
        val source = Uri.parseOrThrow("foo://old:pw@h/p")

        val uri = source.newBuilder().username("new").build()

        assertEquals("new", uri.userInfo)
    }

    @Test
    fun `username after userInfo switches back to split mode and wins`() {
        val uri =
            Uri
                .Builder()
                .host("h")
                .userInfo("raw:verbatim")
                .username("bob")
                .password("pw")
                .encodedPath("/p")
                .build()

        assertEquals("bob:pw", uri.userInfo)
    }

    @Test
    fun `switching to verbatim and back to split via username alone does not leak the old password`() {
        val uri =
            Uri
                .Builder()
                .host("h")
                .username("bob")
                .password("secret")
                .userInfo("raw:verbatim")
                .username("newbob")
                .encodedPath("/p")
                .build()

        assertEquals("newbob", uri.userInfo)
    }

    @Test
    fun `switching to verbatim and back to split via password alone does not leak the old username`() {
        val uri =
            Uri
                .Builder()
                .host("h")
                .username("bob")
                .password("secret")
                .userInfo("raw:verbatim")
                .password("newsecret")
                .encodedPath("/p")
                .build()

        assertEquals(":newsecret", uri.userInfo)
    }

    @Test
    fun `username cleared to an empty string drops it from a split userinfo`() {
        val uri =
            Uri
                .Builder()
                .host("h")
                .username("bob")
                .password("pw")
                .username("")
                .encodedPath("/p")
                .build()

        assertEquals(":pw", uri.userInfo)
    }

    @Test
    fun `password cleared to an empty string drops it from a split userinfo`() {
        val uri =
            Uri
                .Builder()
                .host("h")
                .username("bob")
                .password("pw")
                .password("")
                .encodedPath("/p")
                .build()

        assertEquals("bob", uri.userInfo)
    }

    @Test
    fun `clearing both username and password to empty strings drops the userinfo entirely`() {
        val uri =
            Uri
                .Builder()
                .host("h")
                .username("bob")
                .password("pw")
                .username("")
                .password("")
                .encodedPath("/p")
                .build()

        assertNull(uri.userInfo)
    }
}
