/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Differentially checks kuri (WHATWG URL) against `java.net.URI` (RFC 3986) on a small,
 * hand-curated input set. Where the two specs agree, [agreeing] asserts kuri and `java.net.URI`
 * produce the same scheme/host/path. Where they intentionally diverge, [divergences] documents
 * kuri's REQUIRED WHATWG output with a one-line rationale per row — this is the honesty layer:
 * it documents difference, it does not paper over it.
 */
class JavaNetDifferentialTest {
    /** Inputs where kuri (WHATWG) and java.net (RFC 3986) MUST agree on scheme + host + path. */
    private val agreeing: List<String> =
        listOf(
            "http://example.com/a/b",
            "https://user@host.example/p?q=1",
            "ftp://ftp.example.org/dir/file.txt",
        )

    /**
     * Inputs where the specs intentionally diverge; the value is kuri's REQUIRED WHATWG output,
     * with a one-line rationale. This table is the honesty layer — it documents difference, it
     * does not paper over it.
     */
    private val divergences: Map<String, String> =
        mapOf(
            // WHATWG lower-cases ASCII hosts for special schemes; java.net preserves the source case.
            "http://EXAMPLE.com/" to "http://example.com/",
            // WHATWG collapses backslashes to slashes for special schemes; RFC 3986 has no such rule.
            "http://h/a\\b" to "http://h/a/b",
        )

    @Test
    fun `kuri and java-net agree on scheme host and path for aligned inputs`() {
        agreeing.forEach { input ->
            val kuri = Url.parseOrThrow(input)
            val uri = URI(input)
            assertEquals(uri.scheme, kuri.scheme, "scheme mismatch for $input")
            assertEquals(uri.host, kuri.hostName, "host mismatch for $input")
            assertEquals(uri.rawPath, kuri.encodedPath, "path mismatch for $input")
        }
    }

    @Test
    fun `kuri produces the documented WHATWG value where the specs diverge`() {
        divergences.forEach { (input, expected) ->
            assertEquals(expected, Url.parseOrThrow(input).href, "divergence drift for $input")
        }
    }
}
