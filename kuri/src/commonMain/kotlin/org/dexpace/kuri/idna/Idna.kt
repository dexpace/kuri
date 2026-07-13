/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.idna

import org.dexpace.kuri.error.HostError
import org.dexpace.kuri.error.ParseResult
import org.dexpace.kuri.error.UriParseError
import org.dexpace.kuri.error.map
import org.dexpace.kuri.text.MAX_CODE_POINT
import org.dexpace.kuri.text.NON_ASCII_MIN
import org.dexpace.kuri.text.appendCodePoint
import org.dexpace.kuri.text.asciiLowercased
import org.dexpace.kuri.text.codePointsOf
import org.dexpace.kuri.text.isAllAscii

/** ACE prefix marking a Punycode-encoded (`xn--`) label (RFC 5890 §2.3.2.1). */
private const val ACE_PREFIX: String = "xn--"

/** Length of [ACE_PREFIX]; the remainder after it is the raw Punycode payload. */
private const val ACE_PREFIX_LENGTH: Int = 4

/** Label separator inside a domain (U+002E FULL STOP); also the join separator. */
private const val LABEL_SEPARATOR: String = "."

/**
 * UTS-46 ToASCII / ToUnicode for IDNA domains (SPEC §7.4, [HOST-26]) under the `Url`-profile
 * parameter set ([HOST-28]): `CheckHyphens = false`, `CheckBidi = true`, `CheckJoiners = true`,
 * `UseSTD3ASCIIRules = false`, `Transitional_Processing = false`, `VerifyDnsLength = false`,
 * `IgnoreInvalidPunycode = false`.
 *
 * Implementation status of the [HOST-26] steps:
 *  - **map** (step 2) — fully implemented via [IdnaMappingTable].
 *  - **NFC normalize** (step 3) — fully implemented via [Normalizer.nfc] (UAX #15), applied after
 *    mapping and before label splitting per the UTS-46 processing order.
 *  - **label split / Punycode / re-assemble** (steps 4 & 6) — fully implemented via [Punycode],
 *    including re-validation of a decoded `xn--` label as a fresh U-label ([isValidDecodedALabel]).
 *  - **validate** (step 5) — enforces "valid code points only" (mapping table) plus the
 *    leading-combining-mark rule, ContextJ `CheckJoiners`, and the `CheckBidi` RFC 5893 rule via
 *    [IdnaValidity].
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
        // UTS-46 step 3: canonical NFC normalization (UAX #15), applied post-mapping, pre-label-split.
        val labels = splitLabels(Normalizer.nfc(mapped))
        return processLabels(labels, domain).map { it.joinToString(LABEL_SEPARATOR) }
    }

    /**
     * WHATWG "domain to ASCII" for the `Url` profile (`beStrict = false`): the URL-layer wrapper over
     * the pure UTS-46 [domainToAscii] (SPEC §7.4, [HOST-26]).
     *
     * Decided **per label**, not per whole domain: an all-ASCII label is kept **lowercased
     * verbatim**, since per the standard an ASCII label's Unicode ToASCII failure is only a
     * validation error, never fatal, for web compatibility — so an invalid `xn--` label such as
     * `xn--pokxncvks` is kept as-is rather than rejected, regardless of whether a sibling label
     * elsewhere in the same domain happens to carry non-ASCII text. A label carrying any non-ASCII
     * code point runs the full UTS-46 pipeline via [domainToAscii] and its failure is fatal. Either
     * way, a result that collapses to the empty string (e.g. a lone soft hyphen, which maps to
     * nothing) is a failure ("if result is the empty string, return failure"). The residual
     * forbidden-code-point check is applied by the host classifier, not here.
     *
     * @param domain the percent-decoded, assumed-NFC domain text.
     * @return [ParseResult.Ok] with the ASCII domain, or [ParseResult.Err] on a domain-to-ASCII failure.
     */
    internal fun domainToAsciiForUrl(domain: String): ParseResult<String> {
        val result = asciiLenientDomainToAscii(domain) ?: return idnaError(domain)
        return if (result.isEmpty()) idnaError(domain) else ParseResult.Ok(result)
    }

    /**
     * Splits [domain] on [LABEL_SEPARATOR] and resolves each label independently: an all-ASCII label
     * is lowercased and kept unconditionally (never validated, matching the existing intentional
     * leniency — see [domainToAsciiForUrl]); a label carrying any non-ASCII code point runs the full
     * [domainToAscii] pipeline, and that label's failure fails the whole domain. Splitting first and
     * running NFC per label (inside [domainToAscii]) rather than over the whole domain is equivalent
     * here: `U+002E` never participates in a cross-label canonical composition.
     *
     * @return the joined ASCII domain, or `null` on the first non-ASCII label's UTS-46 failure.
     */
    private fun asciiLenientDomainToAscii(domain: String): String? {
        val labels = splitLabels(domain)
        val out = ArrayList<String>(labels.size)
        for (label in labels) {
            if (label.isAllAscii()) {
                out.add(asciiLowercase(label))
            } else {
                when (val result = domainToAscii(label)) {
                    is ParseResult.Ok -> out.add(result.value)
                    is ParseResult.Err -> return null
                }
            }
        }
        return out.joinToString(LABEL_SEPARATOR)
    }

    /**
     * Runs the inverse display transform (UTS-46 ToUnicode) over [domain]: map, (deferred) NFC,
     * then Punycode-decode every `xn--` label. Best-effort — validity failures are non-fatal, so a
     * disallowed code point or an undecodable label yields the original text rather than an error.
     */
    internal fun domainToUnicode(domain: String): String {
        val mapped = mapAll(domain) ?: domain
        // UTS-46 step 3: canonical NFC normalization (UAX #15), applied post-mapping, pre-label-split.
        val labels = splitLabels(Normalizer.nfc(mapped))
        return labels.joinToString(LABEL_SEPARATOR) { decodeLabelForDisplay(it) }
    }

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
     * Processes one already-mapped label: an `xn--` label is Punycode-decoded and re-validated as a
     * freshly produced U-label ([isValidDecodedALabel]) first, then every label is validated and
     * re-encoded to ASCII ([HOST-26] steps 4 & 5). `CheckHyphens = false`, so a 3rd/4th-character
     * hyphen is permitted.
     */
    private fun processLabel(
        label: String,
        domain: String,
    ): ParseResult<String> {
        val wasPunycode = label.startsWith(ACE_PREFIX)
        val decoded = if (wasPunycode) Punycode.decode(label.substring(ACE_PREFIX_LENGTH)) else label
        return when {
            decoded == null -> idnaError(domain)
            wasPunycode && !isValidDecodedALabel(decoded) -> idnaError(domain)
            !validateLabel(decoded) -> idnaError(domain)
            else -> encodeLabel(decoded, domain)
        }
    }

    /**
     * Re-validates a freshly Punycode-decoded A-label (UTS-46 V1 / P4): the decoded U-label must be
     * non-empty, must carry a non-ASCII code point (an all-ASCII result should never have been
     * ACE-encoded, whatwg/url#760), must already be in NFC (V1), and must not itself begin with the
     * ACE prefix (a double-encoded label, whatwg/url#803). The top-level NFC pass ([Normalizer.nfc])
     * runs before Punycode decoding, so this is the only point the decoded label's NFC form is checked.
     */
    internal fun isValidDecodedALabel(decoded: String): Boolean =
        decoded.isNotEmpty() &&
            decoded.any { it.code >= NON_ASCII_MIN } &&
            decoded == Normalizer.nfc(decoded) &&
            !decoded.startsWith(ACE_PREFIX)

    /**
     * UTS-46 validity check on the decoded Unicode [label] (SPEC §7.4): rejects a leading combining
     * mark, any RFC 5892 ContextJ violation, and any RFC 5893 Bidi-rule violation ([IdnaValidity]),
     * then requires every code point to map to [IdnaMapping.Valid] or [IdnaMapping.Deviation]. Empty
     * labels pass (`VerifyDnsLength = false`; DNS length / emptiness is [HOST-31]).
     */
    private fun validateLabel(label: String): Boolean =
        !IdnaValidity.startsWithCombiningMark(label) &&
            IdnaValidity.checkJoiners(label) &&
            codePointsOf(label).all { isValidLabelCodePoint(it) } &&
            IdnaValidity.checkBidi(label)

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
        if (label.isAllAscii()) {
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

    /** ASCII-lowercases [s] (`A`–`Z` -> `a`–`z`), leaving every other code unit unchanged. */
    private fun asciiLowercase(s: String): String =
        buildString(s.length) {
            for (c in s) append(c.asciiLowercased())
        }
}
