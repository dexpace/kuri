/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.kuri.idna

import org.dexpace.kuri.ParseProfile
import org.dexpace.kuri.error.ParseResult
import org.dexpace.kuri.error.UriParseError
import org.dexpace.kuri.host.HostParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Runs [Idna.domainToAscii] against the official WPT IDNA corpora (IdnaTestV2 + toascii) modelled as
 * [IDNA_CONFORMANCE_CASES]: a non-null [IdnaCase.expected] is the required ToASCII output, a null one
 * means the input must be rejected (SPEC §7.4, [HOST-26]/[HOST-28]). IdnaTestV2 is generated with
 * `--exclude-std3`; the hand-authored `toascii` corpus carries the explicit `CheckBidi` cases.
 *
 * kuri implements the full UTS-46 map / NFC / ContextJ `CheckJoiners` / leading-combining-mark /
 * RFC 5893 `CheckBidi` / decoded-A-label re-validation / Punycode / re-assemble pipeline against the
 * Unicode 17.0 tables. Every remaining mismatch with the corpus is a rejection the WHATWG host layer
 * performs on top of UTS-46 ToASCII (a forbidden host code point or the empty domain), never a defect
 * inside [Idna.domainToAscii]. Those inputs are the [IDNA_KNOWN_FAILURES] residual: they are
 * permanently out of scope for this engine harness because host parsing owns them, and the URL
 * conformance suites already cover their end-to-end rejection at full conformance.
 *
 * That scope is asserted directly, not merely documented: every residual input is shown to be accepted
 * by [Idna.domainToAscii] (so the corpus's required rejection is a host obligation, not a ToASCII one)
 * yet rejected by [HostParser] under the `Url` profile. The suite also ratchets: it fails if any other
 * case regresses, or if the residual drifts from the live failing set.
 */
class IdnaConformanceTest {
    private val residual: Set<String> = IDNA_KNOWN_FAILURES

    private fun caseMatches(case: IdnaCase): Boolean {
        val result = Idna.domainToAscii(case.input)
        return when (case.expected) {
            null -> result is ParseResult.Err
            else -> result is ParseResult.Ok && result.value == case.expected
        }
    }

    private fun liveFailingInputs(): Set<String> =
        IDNA_CONFORMANCE_CASES.filterNot { caseMatches(it) }.map { it.input }.toSet()

    @Test
    fun `every conformance case outside the host-layer residual passes UTS-46 ToASCII`() {
        val unexpected = liveFailingInputs() - residual
        assertTrue(unexpected.isEmpty(), "new IDNA conformance regressions (untracked failures): $unexpected")
    }

    @Test
    fun `the host-layer residual exactly equals the live failing set`() {
        // Ratchet: a brand-new failure, or a residual entry the engine somehow starts rejecting on its
        // own, breaks this until the baseline is regenerated, so the residual can never drift silently.
        assertEquals(residual, liveFailingInputs())
    }

    @Test
    fun `the corpus dwarfs the host-layer residual`() {
        assertTrue(IDNA_CONFORMANCE_CASES.size > residual.size, "passing cases should dwarf the residual")
        assertTrue(residual.isNotEmpty(), "the host-layer residual should yield a tracked baseline")
    }

    @Test
    fun `every host-layer residual is a required-rejection corpus case`() {
        // Each residual is a real corpus input the WPT data marks must-reject (expected == null); the
        // rejection is the host layer's responsibility, not the IDNA engine's.
        residual.forEach { input ->
            assertTrue(
                IDNA_CONFORMANCE_CASES.any { it.input == input && it.expected == null },
                "residual should be a required-rejection corpus case: <$input>",
            )
        }
    }

    @Test
    fun `the IDNA engine itself accepts every host-layer residual input`() {
        // The residual all produce ToASCII output (an empty domain, or a string carrying a forbidden
        // host code point), so the corpus's required rejection is a host duty, not a ToASCII one.
        residual.forEach { input ->
            assertTrue(
                Idna.domainToAscii(input) is ParseResult.Ok,
                "the pure IDNA engine should accept the residual input: <$input>",
            )
        }
    }

    @Test
    fun `the URL host layer rejects every host-layer residual input`() {
        // Host parsing owns these: each falls to the empty-host rule or the forbidden-host-code-point
        // check the WHATWG host parser applies on top of "domain to ASCII".
        residual.forEach { input ->
            val result = HostParser.parse(input, ParseProfile.URL, isSpecial = true)
            assertIs<ParseResult.Err>(result, "residual input should be rejected by the host layer: <$input>")
            assertTrue(
                result.error is UriParseError.EmptyHost || result.error is UriParseError.ForbiddenHostCodePoint,
                "expected an empty-host or forbidden-code-point rejection: <$input> (${result.error})",
            )
        }
    }
}
