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
import org.dexpace.kuri.text.charCount
import org.dexpace.kuri.text.codePointsOf
import org.dexpace.kuri.text.isAllAscii

/**
 * One fragment of a [Idna.traceMapping] result: the UTS-46 mapping-step output produced by a single
 * pre-mapping code point, paired with that code point's own value and position.
 *
 * Deliberately narrower than a full ToASCII trace (see [Idna.traceMapping] for the scope this
 * covers) — it exists solely so a caller can locate, in the *original* text, a code point whose
 * mapped output is known to matter (e.g. a forbidden-domain code point found in the final ASCII
 * domain, [HOST-37]).
 *
 * @property text the mapping step's output for [sourceCodePoint] (its own UTF-16 text for a kept
 *   code point, the replacement text for a mapped one; empty is never produced — an ignored code
 *   point contributes no [MappedUnit] at all).
 * @property sourceIndex the UTF-16 offset of [sourceCodePoint] in the pre-mapping text.
 * @property sourceCodePoint the original (pre-mapping) Unicode scalar value.
 */
internal data class MappedUnit(
    val text: String,
    val sourceIndex: Int,
    val sourceCodePoint: Int,
)

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
     * Decided on the **whole domain**, not per label ([HOST-48]): a [domain] that is all-ASCII
     * *before any mapping runs* is returned **lowercased verbatim**, since per the standard an
     * ASCII domain's Unicode ToASCII failure is only a validation error, never fatal, for web
     * compatibility — so an invalid `xn--` label such as `xn--pokxncvks` is kept as-is rather than
     * rejected. A [domain] carrying any non-ASCII code point — even a single non-ASCII sibling
     * label beside an otherwise-plain-ASCII one — runs the full UTS-46 pipeline via [domainToAscii]
     * over the *entire* domain, and that pipeline's failure is fatal for the whole domain: an
     * invalid label such as `xn--a` in `xn--a.bücher` is **not** rescued by its non-ASCII sibling,
     * matching the WHATWG "domain to ASCII" algorithm's `If domain is an ASCII string:` gate, which
     * is a whole-domain condition, not a per-label one. Either way, a result that collapses to the
     * empty string (e.g. a lone soft hyphen, which maps to nothing) is a failure ("if result is the
     * empty string, return failure"). The residual forbidden-code-point check is applied by the host
     * classifier, not here.
     *
     * @param domain the percent-decoded, assumed-NFC domain text.
     * @return [ParseResult.Ok] with the ASCII domain, or [ParseResult.Err] on a domain-to-ASCII failure.
     */
    internal fun domainToAsciiForUrl(domain: String): ParseResult<String> {
        val result = asciiLenientDomainToAscii(domain) ?: return idnaError(domain)
        return if (result.isEmpty()) idnaError(domain) else ParseResult.Ok(result)
    }

    /**
     * Resolves the whole, raw (pre-mapping) [domain] per [HOST-48]: a [domain] that is already
     * all-ASCII is lowercased and kept unconditionally, without ever running it through
     * [domainToAscii] — matching the existing intentional leniency, see [domainToAsciiForUrl]. A
     * [domain] carrying any non-ASCII code point runs the full [domainToAscii] pipeline over the
     * whole string (which performs the mapping, NFC, label split, and Punycode decode/validate
     * steps itself), and that pipeline's failure fails the whole domain — including a plain-ASCII
     * label that happens to sit beside the non-ASCII one that triggered the full pipeline.
     *
     * @return the ASCII domain, or `null` on a non-ASCII domain's UTS-46 failure.
     */
    private fun asciiLenientDomainToAscii(domain: String): String? =
        if (domain.isAllAscii()) {
            asciiLowercase(domain)
        } else {
            when (val result = domainToAscii(domain)) {
                is ParseResult.Ok -> result.value
                is ParseResult.Err -> null
            }
        }

    /**
     * Runs the inverse display transform (UTS-46 ToUnicode) over [domain]: map, NFC-normalize,
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

    /**
     * Traces the UTS-46 mapping step ([HOST-26] step 2, see [applyMapping]) over [domain] one source
     * code point at a time, pairing each output fragment with the pre-mapping code point and offset
     * that produced it.
     *
     * A **post-hoc diagnostic aid only**: it applies exactly the mapping table (byte-for-byte the
     * same decisions [mapAll] makes) but skips the NFC/Punycode/validation steps that follow it, so
     * it must be called only after [domainToAsciiForUrl] has already accepted the same [domain] — it
     * cannot itself decide success or failure. Used to locate a forbidden-domain code point's
     * original position ([HOST-37]) once the transformed ASCII domain is already known to contain
     * one.
     *
     * @param domain the percent-decoded, pre-IDNA domain text (the same argument
     *   [domainToAsciiForUrl] was called with).
     * @return the mapped fragments in source order; an ignored code point contributes no entry.
     */
    internal fun traceMapping(domain: String): List<MappedUnit> {
        val codePoints = codePointsOf(domain)
        val out = ArrayList<MappedUnit>(codePoints.size)
        var offset = 0
        for (codePoint in codePoints) {
            appendTracedUnit(out, codePoint, offset)
            offset += charCount(codePoint)
        }
        check(out.size <= codePoints.size) { "trace produced more units than source code points" }
        return out
    }

    /** Appends the traced mapping outcome of one [codePoint] at pre-mapping [offset] to [out] (see [traceMapping]). */
    private fun appendTracedUnit(
        out: MutableList<MappedUnit>,
        codePoint: Int,
        offset: Int,
    ) {
        require(offset >= 0) { "offset must not be negative: $offset" }
        when (val mapping = IdnaMappingTable.map(codePoint)) {
            is IdnaMapping.Valid, is IdnaMapping.Deviation ->
                out.add(MappedUnit(codePointText(codePoint), offset, codePoint))
            is IdnaMapping.Mapped -> out.add(MappedUnit(mapping.replacement, offset, codePoint))
            is IdnaMapping.Ignored -> Unit
            // Unreachable in the [traceMapping] contract: the caller already ran [domainToAsciiForUrl]
            // to a successful [ParseResult.Ok] over this same domain, which fails fast on the first
            // disallowed code point ([mapAll]) long before this trace runs.
            is IdnaMapping.Disallowed -> Unit
        }
    }

    /** Renders a single [codePoint] as its own UTF-16 text (one unit, or a surrogate pair above the BMP). */
    private fun codePointText(codePoint: Int): String = buildString { appendCodePoint(this, codePoint) }

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
