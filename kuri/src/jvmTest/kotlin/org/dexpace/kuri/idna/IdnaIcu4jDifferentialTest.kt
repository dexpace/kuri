/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.idna

import com.ibm.icu.text.IDNA
import org.dexpace.kuri.error.ParseResult
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Cross-checks [Idna.domainToAscii] against ICU4J's UTS-46 implementation over the WPT IDNA corpus
 * ([IDNA_CONFORMANCE_CASES]) — a genuinely third-party oracle, unlike `tools/internal/idnaref` (see
 * [IdnaConformanceTest]), which only re-derives kuri's own algorithm as a second implementation.
 *
 * kuri and ICU4J legitimately disagree in two structural, documented ways rather than everywhere they
 * differ being swallowed as noise:
 *  - ICU4J's UTS46 implementation has no switch to disable `CheckHyphens` or `VerifyDnsLength`: it always
 *    rejects a leading/trailing hyphen, a "??--" third/fourth-position hyphen, an empty label, or a
 *    length overflow ([structuralOptionErrors]). kuri's `Url` profile deliberately sets both to `false`
 *    (WHATWG requires the lenient defaults for URL hosts), so a case built to exercise one of those
 *    relaxations disagrees by construction, not by defect.
 *  - ICU4J 77.1 bundles Unicode 16.0 data; kuri bundles Unicode 17.0. A handful of corpus inputs carry
 *    CJK ideographs newly assigned in 17.0 that ICU4J's older tables still treat as unassigned
 *    ([unicodeVersionSkewInputs]), pinned by exact input and ratcheted so a Unicode-version change on
 *    either side forces a conscious update instead of a silent drift.
 *
 * Every other disagreement fails the suite: nothing outside those two documented causes is swallowed.
 */
class IdnaIcu4jDifferentialTest {
    private val icu: IDNA = IDNA.getUTS46Instance(IDNA.DEFAULT or IDNA.CHECK_BIDI or IDNA.CHECK_CONTEXTJ)

    /**
     * ICU4J [IDNA.Error] categories ICU4J's UTS46 implementation always enforces with no equivalent of
     * kuri's `Url`-profile `CheckHyphens = false` / `VerifyDnsLength = false` relaxations, each mapped to
     * the kuri profile rule that explains a case flagged with it.
     */
    private val structuralOptionErrors: Map<IDNA.Error, String> =
        mapOf(
            IDNA.Error.LEADING_HYPHEN to "CheckHyphens=false permits a leading hyphen; ICU4J has no such switch",
            IDNA.Error.TRAILING_HYPHEN to "CheckHyphens=false permits a trailing hyphen; ICU4J has no such switch",
            IDNA.Error.HYPHEN_3_4 to "CheckHyphens=false permits a 3rd/4th-position hyphen; ICU4J has no such switch",
            IDNA.Error.EMPTY_LABEL to "VerifyDnsLength=false permits an empty label; ICU4J always rejects one",
            IDNA.Error.LABEL_TOO_LONG to "VerifyDnsLength=false skips the 63-octet label cap ICU4J always enforces",
            IDNA.Error.DOMAIN_NAME_TOO_LONG to "VerifyDnsLength=false skips the 255-octet domain cap ICU4J enforces",
        )

    /**
     * Exact WPT inputs where kuri (Unicode 17.0) and ICU4J 77.1 (Unicode 16.0) disagree solely because
     * ICU4J's bundled tables still treat a CJK ideograph the input carries (U+32931 or U+32B9A) as
     * unassigned. Copied verbatim from [IDNA_CONFORMANCE_CASES] so a corpus regeneration cannot silently
     * rename them out from under this set.
     */
    private val unicodeVersionSkewInputs: Set<String> =
        setOf(
            "𲤱20.音.ꡦ1.", // U+32931 label; kuri accepts, ICU4J: DISALLOWED (unassigned)
            "xn--20-9802c.xn--0w5a.xn--1-eg4e.", // its ToASCII form, re-decoded and re-checked as an A-label
            "xn--9-i0j5967eg3qz.ss", // ToASCII form of the U+32B9A case below, re-checked as an A-label
            "𲮚9ꍩ៓.ss", // U+32B9A label; kuri accepts, ICU4J: DISALLOWED (unassigned)
            "𲮚9ꍩ៓.SS", // same, uppercase SS label (kuri case-folds before comparing)
        )

    private fun icuToAscii(input: String): Pair<String?, Set<IDNA.Error>> {
        val info = IDNA.Info()
        val out = StringBuilder()
        icu.nameToASCII(input, out, info)
        val value = if (info.hasErrors()) null else out.toString()
        return value to info.errors
    }

    private fun isStructuralOptionDifference(errors: Set<IDNA.Error>): Boolean =
        errors.isNotEmpty() && errors.all { it in structuralOptionErrors }

    private fun isExplainedDifference(
        input: String,
        icuErrors: Set<IDNA.Error>,
    ): Boolean = isStructuralOptionDifference(icuErrors) || input in unicodeVersionSkewInputs

    @Test
    fun `kuri and ICU4J agree on every WPT case outside the documented differences`() {
        val unexplained =
            IDNA_CONFORMANCE_CASES.mapNotNull { case ->
                val kuriValue = (Idna.domainToAscii(case.input) as? ParseResult.Ok)?.value
                val (icuValue, icuErrors) = icuToAscii(case.input)
                if (kuriValue == icuValue || isExplainedDifference(case.input, icuErrors)) {
                    null
                } else {
                    "input=<${case.input}> kuri=<$kuriValue> icu=<$icuValue> icuErrors=$icuErrors"
                }
            }
        assertTrue(unexplained.isEmpty(), "unexplained kuri/ICU4J disagreements:\n${unexplained.joinToString("\n")}")
    }

    @Test
    fun `every documented Unicode version skew input is a live kuri and ICU4J disagreement`() {
        // Ratchet: a kuri or ICU4J Unicode-version bump that resolves the skew makes kuri and ICU4J agree
        // again, and this then fails until the now-stale entry is removed from the documented set above.
        unicodeVersionSkewInputs.forEach { input ->
            val kuriValue = (Idna.domainToAscii(input) as? ParseResult.Ok)?.value
            val (icuValue, _) = icuToAscii(input)
            assertTrue(kuriValue != icuValue, "expected a live kuri/ICU4J disagreement for <$input>")
        }
    }

    @Test
    fun `the corpus dwarfs the documented Unicode version skew`() {
        assertTrue(IDNA_CONFORMANCE_CASES.size > 10 * unicodeVersionSkewInputs.size)
        assertTrue(unicodeVersionSkewInputs.isNotEmpty())
    }
}
