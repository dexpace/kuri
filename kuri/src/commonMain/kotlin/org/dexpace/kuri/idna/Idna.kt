/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.idna

import org.dexpace.kuri.error.HostError
import org.dexpace.kuri.error.ParseResult
import org.dexpace.kuri.error.UriParseError
import org.dexpace.kuri.error.map

/** ACE prefix marking a Punycode-encoded (`xn--`) label (RFC 5890 §2.3.2.1). */
private const val ACE_PREFIX: String = "xn--"

/** Length of [ACE_PREFIX]; the remainder after it is the raw Punycode payload. */
private const val ACE_PREFIX_LENGTH: Int = 4

/** Label separator inside a domain (U+002E FULL STOP); also the join separator. */
private const val LABEL_SEPARATOR: String = "."

/** First non-ASCII code point; a label with any point `>=` this needs ACE encoding. */
private const val NON_ASCII_MIN: Int = 0x80

/** Largest Unicode scalar value (U+10FFFF). */
private const val MAX_CODE_POINT: Int = 0x10FFFF

/** Largest code point that fits in a single UTF-16 code unit. */
private const val MAX_BMP_CODE_POINT: Int = 0xFFFF

/** First code point of the supplementary planes (needs a surrogate pair). */
private const val SUPPLEMENTARY_BASE: Int = 0x10000

/** Bit shift separating the high- and low-surrogate halves of a code point. */
private const val SURROGATE_SHIFT: Int = 10

/** Mask isolating the low-surrogate payload bits of a supplementary code point. */
private const val LOW_SURROGATE_MASK: Int = 0x3FF

/** First UTF-16 high-surrogate code unit (`U+D800`). */
private const val HIGH_SURROGATE_START: Int = 0xD800

/** First UTF-16 low-surrogate code unit (`U+DC00`). */
private const val LOW_SURROGATE_START: Int = 0xDC00

/**
 * UTS-46 ToASCII / ToUnicode for IDNA domains (SPEC §7.4, [HOST-26]) under the `Url`-profile
 * parameter set ([HOST-28]): `CheckHyphens = false`, `CheckBidi = true`, `CheckJoiners = true`,
 * `UseSTD3ASCIIRules = false`, `Transitional_Processing = false`, `VerifyDnsLength = false`,
 * `IgnoreInvalidPunycode = false`.
 *
 * Implementation status of the [HOST-26] steps:
 *  - **map** (step 2) — fully implemented via [IdnaMappingTable].
 *  - **NFC normalize** (step 3) — *deferred* (no normalization tables bundled yet); see
 *    [normalizeNfc]. Callers are assumed to supply NFC-normalized input this increment.
 *  - **label split / Punycode / re-assemble** (steps 4 & 6) — fully implemented via [Punycode].
 *  - **validate** (step 5) — a *light* form: it enforces "valid code points only" using the
 *    mapping table. `CheckBidi`/`CheckJoiners` and the leading-combining-mark rule need Unicode
 *    Bidi / General_Category data not yet bundled and are therefore not enforced here (the
 *    conformance corpus is run with `--exclude-bidi`).
 *
 * Both transforms are pure: input string in, value out, no shared mutable state.
 */
@Suppress("TooManyFunctions") // One cohesive UTS-46 algorithm; helpers stay small per the 60-line
// cap (map, split, per-label decode/validate/encode, code-point split/append). Precedent: Punycode.
internal object Idna {
    /**
     * Runs UTS-46 ToASCII over [domain], returning the ASCII domain or a fatal
     * [UriParseError.InvalidHost] with [HostError.IdnaFailed] on a disallowed code point, an
     * undecodable `xn--` label, an invalid label, or a label that fails to re-encode ([HOST-30]).
     *
     * @param domain the percent-decoded, assumed-NFC domain text.
     * @param beStrict reserved for the strict DNS-length checks of [HOST-31] (deferred); the UTS-46
     *   mapping/validation performed here is identical in both modes.
     * @return [ParseResult.Ok] with the ASCII domain, or [ParseResult.Err] on a UTS-46 failure.
     */
    @Suppress("UnusedParameter") // `beStrict` is the reserved switch for the deferred [HOST-31]
    // strict DNS-length checks; the parameter is part of the intended call contract and is wired
    // through now so adding those checks later is not a source-breaking signature change.
    internal fun domainToAscii(
        domain: String,
        beStrict: Boolean = false,
    ): ParseResult<String> {
        val mapped = mapAll(domain) ?: return idnaError(domain)
        val labels = splitLabels(normalizeNfc(mapped))
        return processLabels(labels, domain).map { it.joinToString(LABEL_SEPARATOR) }
    }

    /**
     * Runs the inverse display transform (UTS-46 ToUnicode) over [domain]: map, (deferred) NFC,
     * then Punycode-decode every `xn--` label. Best-effort — validity failures are non-fatal, so a
     * disallowed code point or an undecodable label yields the original text rather than an error.
     */
    internal fun domainToUnicode(domain: String): String {
        val mapped = mapAll(domain) ?: domain
        val labels = splitLabels(normalizeNfc(mapped))
        return labels.joinToString(LABEL_SEPARATOR) { decodeLabelForDisplay(it) }
    }

    /** NFC normalization is not yet implemented (tracked); inputs are assumed NFC. */
    private fun normalizeNfc(s: String): String = s

    /**
     * Applies the UTS-46 mapping step to every code point of [domain], returning the mapped text or
     * `null` on the first disallowed code point (the caller turns `null` into a fatal error).
     */
    private fun mapAll(domain: String): String? {
        val codePoints = codePointsOf(domain)
        val out = StringBuilder(domain.length)
        var ok = true
        var index = 0
        while (index < codePoints.size && ok) {
            ok = applyMapping(codePoints[index], out)
            index++
        }
        // On success every code point was consumed; a short-circuit means a disallowed point.
        check(!ok || index == codePoints.size) { "mapping loop ended early without failing" }
        return if (ok) out.toString() else null
    }

    /**
     * Applies the mapping outcome of one [codePoint] to [out], returning `false` only when the
     * point is [IdnaMapping.Disallowed]. Valid and deviation points are kept verbatim (the latter
     * because `Transitional_Processing = false`), mapped points are replaced, ignored points drop.
     */
    private fun applyMapping(
        codePoint: Int,
        out: StringBuilder,
    ): Boolean {
        require(codePoint in 0..MAX_CODE_POINT) { "code point out of range: $codePoint" }
        return when (val mapping = IdnaMappingTable.map(codePoint)) {
            is IdnaMapping.Valid, is IdnaMapping.Deviation -> {
                appendCodePoint(out, codePoint)
                true
            }
            is IdnaMapping.Ignored -> true
            is IdnaMapping.Mapped -> {
                out.append(mapping.replacement)
                true
            }
            is IdnaMapping.Disallowed -> false
        }
    }

    /** Splits [domain] into labels on [LABEL_SEPARATOR]; an empty domain yields one empty label. */
    private fun splitLabels(domain: String): List<String> = domain.split(LABEL_SEPARATOR)

    /**
     * Processes every label in order, short-circuiting on the first failure so the resulting
     * [ParseResult.Err] carries the original [domain] for diagnostics ([HOST-26] steps 4 & 5).
     */
    private fun processLabels(
        labels: List<String>,
        domain: String,
    ): ParseResult<List<String>> {
        val out = ArrayList<String>(labels.size)
        var failure: ParseResult.Err? = null
        var index = 0
        while (index < labels.size && failure == null) {
            when (val processed = processLabel(labels[index], domain)) {
                is ParseResult.Ok -> out.add(processed.value)
                is ParseResult.Err -> failure = processed
            }
            index++
        }
        return failure ?: ParseResult.Ok(out)
    }

    /**
     * Processes one already-mapped label: an `xn--` label is Punycode-decoded first, then every
     * label is validated and re-encoded to ASCII ([HOST-26] steps 4 & 5). `CheckHyphens = false`,
     * so a 3rd/4th-character hyphen is permitted.
     */
    private fun processLabel(
        label: String,
        domain: String,
    ): ParseResult<String> {
        val wasPunycode = label.startsWith(ACE_PREFIX)
        val decoded = if (wasPunycode) Punycode.decode(label.substring(ACE_PREFIX_LENGTH)) else label
        return when {
            decoded == null -> idnaError(domain)
            !validateLabel(decoded) -> idnaError(domain)
            else -> encodeLabel(decoded, domain)
        }
    }

    /**
     * Light UTS-46 validity check: every code point of [label] must map to [IdnaMapping.Valid] or
     * [IdnaMapping.Deviation]. Empty labels are accepted because `VerifyDnsLength = false` (DNS
     * length / emptiness is [HOST-31]); the leading-combining-mark and full `CheckBidi`/
     * `CheckJoiners` rules require Unicode category / Bidi data not yet bundled and are not enforced.
     */
    private fun validateLabel(label: String): Boolean = codePointsOf(label).all { isValidLabelCodePoint(it) }

    /** True when [codePoint] is permitted unchanged in a validated label (valid or deviation). */
    private fun isValidLabelCodePoint(codePoint: Int): Boolean =
        when (IdnaMappingTable.map(codePoint)) {
            is IdnaMapping.Valid, is IdnaMapping.Deviation -> true
            is IdnaMapping.Ignored, is IdnaMapping.Mapped, is IdnaMapping.Disallowed -> false
        }

    /**
     * Re-encodes a validated [label] to ASCII ([HOST-26] step 4): an all-ASCII label passes through;
     * a label carrying any non-ASCII code point becomes `xn--` + its Punycode form, or a fatal error
     * if Punycode encoding overflows.
     */
    private fun encodeLabel(
        label: String,
        domain: String,
    ): ParseResult<String> {
        if (label.all { it.code < NON_ASCII_MIN }) {
            return ParseResult.Ok(label)
        }
        return when (val encoded = Punycode.encode(label)) {
            null -> idnaError(domain)
            else -> ParseResult.Ok(ACE_PREFIX + encoded)
        }
    }

    /**
     * Best-effort ToUnicode for one label: Punycode-decodes an `xn--` label, returning the original
     * label unchanged when it has no ACE prefix or when decoding fails (non-fatal per ToUnicode).
     */
    private fun decodeLabelForDisplay(label: String): String {
        if (!label.startsWith(ACE_PREFIX)) {
            return label
        }
        return Punycode.decode(label.substring(ACE_PREFIX_LENGTH)) ?: label
    }

    /** Builds the fatal IDNA failure carrying the original [domain] ([HOST-26], [HOST-30]). */
    private fun idnaError(domain: String): ParseResult.Err =
        ParseResult.Err(UriParseError.InvalidHost(domain, HostError.IdnaFailed))

    /** Splits [input] into Unicode code points, combining well-formed surrogate pairs. */
    private fun codePointsOf(input: String): List<Int> {
        val result = ArrayList<Int>(input.length)
        var index = 0
        while (index < input.length) {
            val high = input[index]
            val low = if (index + 1 < input.length) input[index + 1] else null
            if (high.isHighSurrogate() && low != null && low.isLowSurrogate()) {
                result.add(toCodePoint(high, low))
                index += 2
            } else {
                result.add(high.code)
                index++
            }
        }
        return result
    }

    /** Combines a UTF-16 surrogate pair into a single supplementary-plane code point. */
    private fun toCodePoint(
        high: Char,
        low: Char,
    ): Int {
        require(high.isHighSurrogate()) { "expected high surrogate: $high" }
        require(low.isLowSurrogate()) { "expected low surrogate: $low" }
        val highBits = (high.code - HIGH_SURROGATE_START) shl SURROGATE_SHIFT
        return SUPPLEMENTARY_BASE + highBits + (low.code - LOW_SURROGATE_START)
    }

    /** Appends [codePoint] to [out], emitting a surrogate pair for supplementary values. */
    private fun appendCodePoint(
        out: StringBuilder,
        codePoint: Int,
    ) {
        require(codePoint in 0..MAX_CODE_POINT) { "code point out of range: $codePoint" }
        if (codePoint <= MAX_BMP_CODE_POINT) {
            out.append(codePoint.toChar())
        } else {
            val offset = codePoint - SUPPLEMENTARY_BASE
            out.append((HIGH_SURROGATE_START + (offset ushr SURROGATE_SHIFT)).toChar())
            out.append((LOW_SURROGATE_START + (offset and LOW_SURROGATE_MASK)).toChar())
        }
    }
}
