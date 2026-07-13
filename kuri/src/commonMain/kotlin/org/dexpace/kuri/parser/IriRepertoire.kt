/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.parser

/** Separates the inclusive `<start>-<end>` records within one of this file's hex range blobs. */
private const val RECORD_SEPARATOR: Char = ';'

/** Separates the start and end hex bounds of one inclusive range record. */
private const val RANGE_SEPARATOR: Char = '-'

/** Radix the hex bounds and bidi-formatting code points are parsed in. */
private const val RADIX_HEX: Int = 16

/**
 * The RFC 3987 §2.2 `ucschar`/`iprivate` repertoire and the §4.1 bidi-formatting-character set.
 *
 * Both tables are fixed by the RFC's own ABNF (not tied to any Unicode version), so unlike the
 * generated IDNA/UTS-46 tables they never need regeneration. They are still stored as parsed hex
 * text rather than `IntRange` literals, mirroring the codegen'd IDNA range tables: it keeps every
 * bound traceable one-for-one to the ABNF quoted below without a wall of unnamed numeric literals.
 */
internal object IriRepertoire {
    /**
     * The `ucschar` ranges (RFC 3987 §2.2):
     * `%xA0-D7FF / %xF900-FDCF / %xFDF0-FFEF / %x10000-1FFFD / %x20000-2FFFD / %x30000-3FFFD /
     * %x40000-4FFFD / %x50000-5FFFD / %x60000-6FFFD / %x70000-7FFFD / %x80000-8FFFD /
     * %x90000-9FFFD / %xA0000-AFFFD / %xB0000-BFFFD / %xC0000-CFFFD / %xD0000-DFFFD /
     * %xE1000-EFFFD`. These are the code points [iunreserved] may hold beyond
     * `ALPHA`/`DIGIT`/`"-"`/`"."`/`"_"`/`"~"`, legal in every non-host component this facility maps.
     */
    private val ucscharRanges: List<IntRange> =
        decodeRanges(
            "A0-D7FF;F900-FDCF;FDF0-FFEF;10000-1FFFD;20000-2FFFD;30000-3FFFD;40000-4FFFD;" +
                "50000-5FFFD;60000-6FFFD;70000-7FFFD;80000-8FFFD;90000-9FFFD;A0000-AFFFD;" +
                "B0000-BFFFD;C0000-CFFFD;D0000-DFFFD;E1000-EFFFD",
        )

    /**
     * The `iprivate` ranges (RFC 3987 §2.2): `%xE000-F8FF / %xF0000-FFFFD / %x100000-10FFFD`. Per
     * the ABNF, `iprivate` appears only in `iquery` (`iquery = *( ipchar / iprivate / "/" / "?" )`) —
     * it is not part of `ipchar`, so it is illegal in userinfo, path, and fragment even though it is
     * legal in the query.
     */
    private val iprivateRanges: List<IntRange> = decodeRanges("E000-F8FF;F0000-FFFFD;100000-10FFFD")

    /**
     * The seven bidirectional formatting characters RFC 3987 §4.1 forbids anywhere in an IRI: LRM
     * (U+200E), RLM (U+200F), LRE (U+202A), RLE (U+202B), PDF (U+202C), LRO (U+202D), RLO (U+202E).
     * They affect visual rendering only and must never appear in the logical (stored/transmitted) form.
     */
    private val bidiFormattingCharacters: Set<Int> =
        "200E,200F,202A,202B,202C,202D,202E".split(',').map { it.toInt(RADIX_HEX) }.toSet()

    /**
     * True when [codePoint] is in one of the `ucschar` ranges (RFC 3987 §2.2).
     *
     * @param codePoint the Unicode scalar value to test.
     * @return `true` iff [codePoint] falls within a `ucschar` range.
     */
    internal fun isUcschar(codePoint: Int): Boolean = ucscharRanges.any { codePoint in it }

    /**
     * True when [codePoint] is in one of the `iprivate` ranges (RFC 3987 §2.2), legal only in the
     * query component.
     *
     * @param codePoint the Unicode scalar value to test.
     * @return `true` iff [codePoint] falls within an `iprivate` range.
     */
    internal fun isIprivate(codePoint: Int): Boolean = iprivateRanges.any { codePoint in it }

    /**
     * True when [codePoint] is one of the seven bidi formatting characters RFC 3987 §4.1 forbids.
     *
     * @param codePoint the Unicode scalar value to test.
     * @return `true` iff [codePoint] is LRM, RLM, LRE, RLE, PDF, LRO, or RLO.
     */
    internal fun isBidiFormattingCharacter(codePoint: Int): Boolean = codePoint in bidiFormattingCharacters

    /** Decodes a semicolon-joined `<start>-<end>` hex blob into its inclusive [IntRange]s. */
    private fun decodeRanges(blob: String): List<IntRange> =
        blob.split(RECORD_SEPARATOR).map { record ->
            val dash = record.indexOf(RANGE_SEPARATOR)
            check(dash > 0) { "malformed range record: $record" }
            record.substring(0, dash).toInt(RADIX_HEX)..record.substring(dash + 1).toInt(RADIX_HEX)
        }
}
