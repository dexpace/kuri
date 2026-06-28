/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.kuri.idna

/**
 * Inputs that [Idna.domainToAscii] still fails the WPT corpora (IdnaTestV2 + toascii) on,
 * tracked so [IdnaConformanceTest] ratchets: the live failing set must equal this set
 * exactly, so closing a gap (or a regression) breaks the build until the baseline is updated.
 *
 * After NFC, ContextJ `CheckJoiners`, the leading-combining-mark rule, and the Unicode 16.0
 * tables landed, the residual is small and contains no Punycode/mapping/NFC output bug. Each
 * entry is a UTS-46 criterion this profile does not apply, or a corpus/version corner:
 *  - CheckBidi (RFC 5893): 12
 *  - A-label re-validation (UTS-46 P4/V1/V4): 7
 *  - Unicode-version skew (corpus newer than 16.0): 5
 *  - host-layer forbidden code points: 3
 *  - empty domain: 1
 */
private object IdnaKnownFailureData {
    fun all(): Set<String> =
        setOf(
            // CheckBidi (RFC 5893): mixed LTR/RTL labels the Url profile would reject; CheckBidi is
            // intentionally unimplemented (the corpus is run --exclude-bidi).
            "\u064aa",
            "a\u05d0",
            "a\u0627",
            "a\u0661",
            "a\u05d0\u05d1",
            "a\u0301\u05d0",
            "a\u05d0\u0301",
            "1\u0627",
            "\u0660",
            "\u0660\u0627",
            "xn--a-yoc",
            "look\u05beout.net",
            // A-label re-validation: a decoded `xn--` label that is non-NFC, empty, or all-ASCII
            // (UTS-46 P4/V1/V4). The Punycode arithmetic is correct; the extra reject filter
            // on already-decoded A-labels is not part of this step.
            "xn--u-ccb",
            "xn--xn--a--gua.pt",
            "xn--xn---epa",
            "xn--",
            "xn---",
            "xn--ASCII-",
            "xn--unicode-.org",
            // Unicode-version skew: CJK ideographs (U+32931, U+32B9A) unassigned -> disallowed in the
            // bundled Unicode 16.0.0 UCD, which the corpus (a newer Unicode) treats as valid.
            "xn--20-9802c.xn--0w5a.xn--1-eg4e.",
            "xn--9-i0j5967eg3qz.ss",
            "\ud88a\udd3120.\u97f3.\ua8661.",
            "\ud88a\udf9a9\ua369\u17d3.ss",
            "\ud88a\udf9a9\ua369\u17d3.SS",
            // Host-layer forbidden code points (NBSP, C0 control, the `::=` mapping of U+2A74): mapped
            // correctly here, then rejected by host parsing, not by UTS-46 ToASCII.
            "www.lookout.net\u2a7480",
            "www\u00a0.lookout.net",
            "\u001flookout.net",
            // Empty domain: VerifyDnsLength / empty-label enforcement is deferred to the host layer.
            "",
        )
}

/** Tracked baseline of currently-failing conformance inputs (see [IdnaKnownFailureData]). */
internal val IDNA_KNOWN_FAILURES: Set<String> = IdnaKnownFailureData.all()
