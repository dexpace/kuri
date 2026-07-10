/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.parser

import org.dexpace.kuri.Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The stable key separator joining a [SetterCase]'s [SetterCase.component], [SetterCase.href],
 * and [SetterCase.newValue] into one baseline key. `U+0000` never occurs in a WPT setter fixture
 * after pre-processing, so it disambiguates the three parts without collision.
 */
private const val KEY_SEPARATOR: String = "\u0000"

/**
 * Runs every WPT `setters_tests.json` case (modelled as [SETTER_TEST_CASES]) through the `Url.with*`
 * setter family and ratchets a tracked known-failures baseline (WHATWG URL Living Standard §5, the
 * per-component setters).
 *
 * Each case parses [SetterCase.href], applies the setter named by [SetterCase.component] with
 * [SetterCase.newValue], and compares every getter named in [SetterCase.expected] against the
 * WHATWG getter adapter in [read]. The suite ratchets in both directions — a brand-new failure
 * breaks the untracked-regressions test, and a regression that repopulates the live set breaks the
 * baseline-equality test until the baseline is updated.
 */
class UrlSetterConformanceTest {
    /** Applies [case]'s component setter to a freshly parsed base URL. */
    private fun applySetter(case: SetterCase): Url? {
        val base = Url.parseOrThrow(case.href)
        return when (case.component) {
            "protocol" -> base.withProtocol(case.newValue)
            "username" -> base.withUsername(case.newValue)
            "password" -> base.withPassword(case.newValue)
            "host" -> base.withHost(case.newValue)
            "hostname" -> base.withHostname(case.newValue)
            "port" -> base.withPort(case.newValue)
            "pathname" -> base.withPathname(case.newValue)
            "search" -> base.withSearch(case.newValue)
            "hash" -> base.withHash(case.newValue)
            "href" -> Url.parseOrNull(case.newValue) // WHATWG href setter == full re-parse
            else -> null
        }
    }

    /** Reads WHATWG getter [key] off [url], translating to kuri's accessor names/formats. */
    private fun read(
        url: Url,
        key: String,
    ): String =
        when (key) {
            "protocol" -> url.scheme + ":"
            "username" -> url.username
            "password" -> url.password
            "host" -> url.hostName.orEmpty() + portSuffix(url)
            "hostname" -> url.hostName.orEmpty()
            "port" -> url.port?.toString().orEmpty()
            "pathname" -> url.encodedPath
            "search" -> prefixedOrEmpty(url.query, '?')
            "hash" -> prefixedOrEmpty(url.fragment, '#')
            "href" -> url.href
            else -> error("unknown getter: $key")
        }

    /** The `:port` suffix of the WHATWG `host` getter, or `""` when [url] carries no explicit port. */
    private fun portSuffix(url: Url): String = url.port?.let { ":$it" }.orEmpty()

    /** [prefix] followed by [value], or `""` for a `null`/empty [value] (WHATWG `search`/`hash`). */
    private fun prefixedOrEmpty(
        value: String?,
        prefix: Char,
    ): String = if (value.isNullOrEmpty()) "" else "$prefix$value"

    /** True when applying [case]'s setter yields every expected getter value. */
    private fun caseMatches(case: SetterCase): Boolean {
        val result = applySetter(case) ?: return false
        return case.expected.all { (key, value) -> read(result, key) == value }
    }

    /** The stable baseline key for [case]: its component, href, and new value joined by [KEY_SEPARATOR]. */
    private fun key(case: SetterCase): String =
        case.component + KEY_SEPARATOR + case.href + KEY_SEPARATOR + case.newValue

    /** The keys of every case the live setter family currently does not satisfy. */
    private fun liveFailingKeys(): Set<String> = SETTER_TEST_CASES.filterNot { caseMatches(it) }.map { key(it) }.toSet()

    @Test
    fun `every setter case outside the known-failures set matches WPT`() {
        val untracked = liveFailingKeys() - KNOWN_FAILURES
        assertTrue(untracked.isEmpty(), "new setter conformance regressions: $untracked")
    }

    @Test
    fun `the known-failures set exactly equals the live failing set`() {
        assertEquals(KNOWN_FAILURES, liveFailingKeys())
    }

    @Test
    fun `every tracked known failure is a real corpus case`() {
        val keys = SETTER_TEST_CASES.map { key(it) }.toSet()
        val orphans = KNOWN_FAILURES - keys
        assertTrue(orphans.isEmpty(), "known failures absent from the corpus: $orphans")
    }

    @Test
    fun `the setter corpus is substantial`() {
        assertTrue(SETTER_TEST_CASES.size > MIN_CORPUS_SIZE, "corpus too small: ${SETTER_TEST_CASES.size}")
    }

    private companion object {
        private const val MIN_CORPUS_SIZE: Int = 200

        /** Rebuilds a baseline key from its parts identically to [key], for a readable literal below. */
        private fun knownFailureKey(
            component: String,
            href: String,
            newValue: String,
        ): String = component + KEY_SEPARATOR + href + KEY_SEPARATOR + newValue

        // Target: empty. Populate ONLY with justified, genuinely-unfixable residue during TDD,
        // one commented entry per line explaining why kuri's immutable model diverges from the
        // JS mutable-setter fixture.
        //
        // Both entries are the same underlying gap, not a setter-plumbing bug: UrlHostParser accepts
        // the ACE label "xn--" (an `xn--` prefix with no encoded suffix, which Punycode decodes to
        // the empty string) as a plain RegName instead of rejecting it as an invalid domain label.
        // A *full* parse of "https://xn--/" reproduces the identical acceptance (verified directly
        // against UrlHostParser.parse and Url.parse, not just through the setter), so this is IDNA
        // domain-validity depth kuri's host pipeline doesn't yet cover -- not something introduced
        // or fixable by the setter/override plumbing this suite exercises. Fixing it means teaching
        // UrlHostParser/the domain-to-ASCII step to validate an `xn--` label's Punycode round-trip,
        // which is IDNA runtime this task is explicitly scoped out of touching.
        private val KNOWN_FAILURES: Set<String> =
            setOf(
                knownFailureKey(component = "host", href = "https://example.com/", newValue = "xn--"),
                knownFailureKey(component = "hostname", href = "https://example.com/", newValue = "xn--"),
            )
    }
}
