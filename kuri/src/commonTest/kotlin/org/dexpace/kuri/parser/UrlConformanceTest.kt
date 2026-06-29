/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.kuri.parser

import org.dexpace.kuri.error.ParseResult
import org.dexpace.kuri.host.Host
import org.dexpace.kuri.host.Ipv4
import org.dexpace.kuri.host.Ipv6
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The stable key separator joining a case's [UrlCase.input] and [UrlCase.base] into one
 * baseline key. `U+0000` never occurs in a WPT input or base after pre-processing, so it
 * disambiguates the two halves without collision.
 */
private const val KEY_SEPARATOR: String = "\u0000"

/**
 * Runs every in-scope WPT `urltestdata.json` case (modelled as [URL_TEST_CASES]) through
 * [UrlParser] under the `Url` profile and ratchets a tracked known-failures baseline (SPEC §8;
 * WHATWG "basic URL parser").
 *
 * Each case resolves [UrlCase.input] against the parsed [UrlCase.base] (the WPT bases are always
 * valid, so a base that fails to parse is surfaced as a real bug, not skipped). A [UrlCase.failure]
 * case must yield [ParseResult.Err]; otherwise the parsed [ParsedComponents] are reconstructed into
 * the WPT getter strings (`protocol`, `hostname`, `port`, `pathname`, `search`, `hash`) and compared.
 *
 * Reconstruction mirrors the WHATWG component serializers (§11) for the getters under test: a path
 * as the concatenation of `"/" + segment` (so the empty list renders `""` and the special root
 * renders `"/"`), and a `search`/`hash` as `?`/`#` plus the value, with absent or present-but-empty
 * both collapsing to `""` (the getter cannot tell them apart). The only residual divergence is the
 * deferred IDNA host validity tracked in [KNOWN_FAILURES]. The suite ratchets in both directions: a
 * brand-new failure breaks the untracked-regressions test, and a fixed gap breaks the
 * baseline-equality test until the baseline is updated.
 */
@Suppress("TooManyFunctions") // small single-purpose reconstruction helpers plus the four ratchet tests.
class UrlConformanceTest {
    /** Serializes a parsed [host] to its WPT `hostname` getter form (§7.9; empty/absent host -> `""`). */
    private fun serializeHost(host: Host?): String =
        when (host) {
            null -> ""
            is Host.Empty -> ""
            is Host.RegName -> host.value
            is Host.Opaque -> host.value
            is Host.Ipv4 -> Ipv4.serialize(host.value)
            is Host.Ipv6 -> "[" + Ipv6.serialize(host.pieces) + "]"
            is Host.IpFuture -> "[" + host.value + "]"
        }

    /**
     * Serializes a [path] to the WPT `pathname` getter (the WHATWG URL path serializer, §11): an
     * opaque path verbatim, a segment list as the concatenation of `"/" + segment`. The empty list
     * `Segments([])` therefore renders as `""` (a non-special authority with no path), while the
     * special-URL root `Segments([""])` renders as `"/"` -- matching WPT exactly.
     */
    private fun serializePath(path: UrlPath): String =
        when (path) {
            is UrlPath.Opaque -> path.path
            is UrlPath.Segments -> path.segments.joinToString(separator = "") { "/$it" }
        }

    /** Maps a WPT `port` string to the parsed [ParsedComponents.port] (`""` = default/none = null). */
    private fun expectedPort(port: String): Int? = if (port.isEmpty()) null else port.toInt()

    /**
     * Serializes a parsed `query`/`fragment` to its WPT `search`/`hash` getter form (§11): an absent
     * (null) or present-but-empty value yields `""`, otherwise the value prefixed with [prefix]
     * (`?`/`#`). The getter cannot distinguish null from `""`, so both collapse to `""` here.
     */
    private fun serializeOptional(
        value: String?,
        prefix: Char,
    ): String = if (value.isNullOrEmpty()) "" else prefix + value

    /** True when every reconstructed component of [components] equals the [case]'s expected WPT field. */
    private fun componentsMatch(
        components: ParsedComponents,
        case: UrlCase,
    ): Boolean {
        val checks =
            listOf(
                (components.scheme ?: "") + ":" == case.protocol,
                components.username == case.username,
                components.password == case.password,
                serializeHost(components.host) == case.hostname,
                components.port == expectedPort(case.port),
                serializePath(components.path) == case.pathname,
                serializeOptional(components.query, '?') == case.search,
                serializeOptional(components.fragment, '#') == case.hash,
            )
        return checks.all { it }
    }

    /** Parses a WPT [base], which must succeed; a failure is a genuine bug surfaced here (§8.3). */
    private fun parseBase(base: String): ParsedComponents =
        when (val result = UrlParser.parse(base)) {
            is ParseResult.Ok -> result.value
            is ParseResult.Err -> error("WPT base must parse but failed: $base (${result.error})")
        }

    /** True when [case] parses exactly as WPT prescribes (failure => Err; success => all fields match). */
    private fun caseMatches(case: UrlCase): Boolean {
        val base = case.base?.let { parseBase(it) }
        return when (val result = UrlParser.parse(case.input, base)) {
            is ParseResult.Err -> case.failure
            is ParseResult.Ok -> !case.failure && componentsMatch(result.value, case)
        }
    }

    /** The stable baseline key for [case]: its input and base joined by [KEY_SEPARATOR]. */
    private fun key(case: UrlCase): String = case.input + KEY_SEPARATOR + (case.base ?: "")

    /** The keys of every case the live parser currently does not satisfy. */
    private fun liveFailingKeys(): Set<String> = URL_TEST_CASES.filterNot { caseMatches(it) }.map { key(it) }.toSet()

    @Test
    fun `every conformance case outside the known-failures set parses per WPT`() {
        val untracked = liveFailingKeys() - KNOWN_FAILURES
        assertTrue(untracked.isEmpty(), "new URL conformance regressions (untracked failures): $untracked")
    }

    @Test
    fun `the known-failures set exactly equals the live failing set`() {
        // Ratchet: a fixed gap (tracked failure now passing) or a brand-new failure breaks this
        // until the baseline is regenerated, so the deferred debt can never drift silently.
        assertEquals(KNOWN_FAILURES, liveFailingKeys())
    }

    @Test
    fun `every tracked known failure is a real corpus case`() {
        val keys = URL_TEST_CASES.map { key(it) }.toSet()
        val orphans = KNOWN_FAILURES - keys
        assertTrue(orphans.isEmpty(), "known failures absent from the corpus: $orphans")
    }

    @Test
    fun `the corpus and known-failures set are non-trivially populated`() {
        assertTrue(URL_TEST_CASES.size > KNOWN_FAILURES.size * 2, "passing cases should dwarf known failures")
        assertTrue(KNOWN_FAILURES.isNotEmpty(), "deferred corners should yield a tracked baseline")
    }

    private companion object {
        /**
         * The tracked baseline of currently-failing case keys (`input + U+0000 + base`). The single
         * remaining category is a deferred dependency, not a §8 state-machine bug:
         *  - **deferred IDNA host validity (6):** a host that UTS-46 rejects (a soft-hyphen label
         *    that maps to empty, an invalid `xn--` A-label) is accepted because the same UTS-46
         *    validity steps deferred in `IdnaConformanceTest` are not yet applied, so the parse
         *    succeeds where WPT requires failure.
         *
         * None reflect a wrong parsed component; closing the IDNA-validity gap will empty this set,
         * which the ratcheting `the known-failures set exactly equals the live failing set` enforces.
         */
        private val KNOWN_FAILURES: Set<String> =
            setOf(
                // deferred IDNA host validity (host accepted where UTS-46 requires rejection)
                "file://\u00ad/p\u0000",
                "file://%C2%AD/p\u0000",
                "file://xn--/p\u0000",
                "https://\u00ad/\u0000",
                "https://%C2%AD/\u0000",
                "https://xn--/\u0000",
            )
    }
}
