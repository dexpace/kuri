/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.parser

import org.dexpace.kuri.ParseOptions
import org.dexpace.kuri.ZONE_ID_ENABLED
import org.dexpace.kuri.carriesZoneId
import org.dexpace.kuri.error.ParseResult
import org.dexpace.kuri.error.ResourceLimit
import org.dexpace.kuri.error.UriParseError
import org.dexpace.kuri.error.flatMap
import org.dexpace.kuri.error.map
import org.dexpace.kuri.host.serializeHost
import org.dexpace.kuri.scheme.Scheme
import org.dexpace.kuri.scheme.schemeColonIndex
import org.dexpace.kuri.serialize.guardRecomposedUriPath

/** The path-segment separator, hoisted so the dot-segment prefix tests carry no bare string literals. */
private const val SLASH: String = "/"

/** The authority-introducing prefix (RFC 3986 §3.2); an authority opens only after exactly `//`. */
private const val DOUBLE_SLASH: String = "//"

/** §5.2.4 case A prefix: a leading `../` complete dot-segment that is simply discarded. */
private const val PREFIX_DOTDOT_SLASH: String = "../"

/** §5.2.4 case A prefix: a leading `./` complete dot-segment that is simply discarded. */
private const val PREFIX_DOT_SLASH: String = "./"

/** §5.2.4 case B prefix: a `/./` run replaced by a single `/`. */
private const val PREFIX_SLASH_DOT_SLASH: String = "/./"

/** §5.2.4 case C prefix: a `/../` run replaced by `/` after popping the last output segment. */
private const val PREFIX_SLASH_DOTDOT_SLASH: String = "/../"

/** §5.2.4 case B terminal: the whole remaining input is the complete segment `/.`. */
private const val SEGMENT_SLASH_DOT: String = "/."

/** §5.2.4 case C terminal: the whole remaining input is the complete segment `/..`. */
private const val SEGMENT_SLASH_DOTDOT: String = "/.."

/** §5.2.4 case D terminal: the whole remaining input is the bare complete segment `.`. */
private const val SEGMENT_DOT: String = "."

/** §5.2.4 case D terminal: the whole remaining input is the bare complete segment `..`. */
private const val SEGMENT_DOTDOT: String = ".."

/**
 * RFC 3986 §5 reference resolution — the SPEC §9 "resolve a relative reference against an absolute
 * base" operation.
 *
 * Implements the strict transform of §5.2.2 over a behaviour-free 5-tuple view of a URI
 * (`scheme`, `authority`, `path`, `query`, `fragment`), driving §5.2.3 path merging and §5.2.4
 * dot-segment removal, then recomposing per §5.3. The posture is always STRICT: a scheme present in
 * the reference is never elided even when it equals the base scheme (the §5.4.2 `http:g` loophole is
 * not taken).
 *
 * The string entry point splits each input into its raw five parts directly (Appendix B) and resolves
 * over those raw path strings, where the absolute (`/g`) versus rootless (`g`) distinction that is
 * load-bearing for §5.2.2 is plainly visible. [ComponentPath.Segments] now records that same distinction via
 * its `rooted` flag, so the structured component form preserves it too; the raw-string path is kept
 * because it operates directly on the input as Appendix B specifies and remains correct. [UriParser]
 * is still run on both inputs first so the resolution shares the parser's validation and absolute-base
 * check.
 *
 * The algorithm is a short ordered procedure split into single-purpose helpers, each well under the
 * line/return budgets; this legitimately exceeds the per-object function count.
 */
@Suppress("TooManyFunctions")
internal object Resolver {
    /**
     * Removes the complete `.`/`..` dot-segments from [path] per the §5.2.4 two-buffer algorithm.
     *
     * A pure string transform run in O(`path.length`): a single non-decreasing cursor walks the
     * input, and each matched prefix (cases A–E) appends at most one segment to the output buffer
     * with no per-iteration substring allocation. The output can grow past the input (case C's `/..`
     * terminal appends a `/` after popping), so the transform does not "strictly shrink"; it is
     * bounded because every iteration advances the cursor by at least one code unit.
     *
     * This entry point is unbounded by [ParseOptions] on purpose — the length and resolution-depth
     * gates live at the returning call sites ([boundedRemoveDotSegments]); it is used directly only
     * where the input is already bounded (§6.2.2.3 normalization of an already-parsed path) or is a
     * unit-test literal.
     *
     * @param path the merged/extracted path to normalize; may be empty.
     * @return the path with extraneous dot-segments removed.
     */
    internal fun removeDotSegments(path: String): String = collapseDotSegments(path).output

    /**
     * The pure §5.2.4 collapse of [path], returning the cleaned path and the number of loop
     * iterations it took — the measure [boundedRemoveDotSegments] gates against
     * [ParseOptions.resolutionDepth].
     */
    private fun collapseDotSegments(path: String): DotSegmentCollapse {
        val output = StringBuilder(path.length)
        var pos = 0
        var iterations = 0
        while (pos < path.length) {
            val next = step(path, pos, output)
            check(next > pos) { "remove_dot_segments made no progress at $pos" }
            pos = next
            iterations++
        }
        check(pos == path.length) { "remove_dot_segments overran the input to $pos" }
        return DotSegmentCollapse(output.toString(), iterations)
    }

    /**
     * Resolves [reference] against the absolute [baseUri] (§5.2), returning the target URI string.
     *
     * Both inputs are parsed with [UriParser] so the resolution inherits the parser's validation; an
     * unparsable base or reference is forwarded as [ParseResult.Err], and a base without a scheme is
     * rejected with [UriParseError.MissingScheme] since §5.2 requires an absolute base.
     *
     * @param baseUri the absolute base URI the reference is interpreted against.
     * @param reference the (possibly relative) URI reference to resolve.
     * @param options the opt-in parsing configuration applied to the validation parses of both
     *   inputs, so a base (or reference) carrying an RFC 6874 zone id is accepted ([HOST-18]); its
     *   [ParseOptions.expandedLength] also bounds the §5.2.3 path merge (`ResourceLimit.ExpandedLength`,
     *   [ERR-31]).
     * @return the recomposed target URI, or the first fatal parse error.
     */
    internal fun resolve(
        baseUri: String,
        reference: String,
        options: ParseOptions = ParseOptions.DEFAULT,
    ): ParseResult<String> {
        require(baseUri.isNotEmpty()) { "an absolute base URI is required for resolution" }
        val base = UriParser.parse(baseUri, options)
        val ref = UriParser.parse(reference, options)
        return resolveOutcome(baseUri, reference, base, ref, options)
    }

    /**
     * Resolves [reference] against the absolute [base] on the structured component model (§5.2),
     * the form the future public `Uri.resolve` builds on.
     *
     * [ComponentPath.Segments] records the absolute-vs-rootless distinction of a path via its `rooted` flag
     * (`/g` is rooted, `g` is not), so [toUriPathString] reproduces each form faithfully and this structured
     * resolution preserves the distinction through to the recomposed target.
     *
     * @param base the absolute base components; its scheme MUST be present.
     * @param reference the reference components to resolve.
     * @param options the effective parse configuration whose limits bound this resolve; defaults to
     *   [ParseOptions.DEFAULT], in which case [structuredOptions] derives the zone-id opt-in from the
     *   operands so a direct caller (not routing a stored `Uri`'s options through) still round-trips
     *   a zoned host.
     * @return [ParseResult.Ok] with the resolved target components, or [ParseResult.Err] when the
     *   recomposed target does not parse — e.g. dot-segment removal produced a `//`-leading, authority-less
     *   path that re-reads as an invalid authority.
     */
    internal fun resolve(
        base: ParsedComponents,
        reference: ParsedComponents,
        options: ParseOptions = ParseOptions.DEFAULT,
    ): ParseResult<ParsedComponents> {
        require(base.scheme != null) { "structured resolution requires an absolute base scheme" }
        val effective = if (options == ParseOptions.DEFAULT) structuredOptions(base, reference) else options
        return when (val target = transformReferences(partsOf(base), partsOf(reference), effective)) {
            is ParseResult.Err -> target
            is ParseResult.Ok -> UriParser.parse(recompose(target.value), effective)
        }
    }

    /**
     * Derives the round-trip [ParseOptions] for a structured resolve given only the operands: a zone
     * id on either input opts in. Used as the fallback when the caller passes [ParseOptions.DEFAULT]
     * (no stored options to honour), so a zoned host still re-parses through the recompose.
     */
    private fun structuredOptions(
        base: ParsedComponents,
        reference: ParsedComponents,
    ): ParseOptions =
        if (base.host.carriesZoneId() || reference.host.carriesZoneId()) ZONE_ID_ENABLED else ParseOptions.DEFAULT

    // --- §5.2.2 Transform References (STRICT) -----------------------------------------------------

    /** Applies the §5.2.2 transform: a defined reference scheme wins outright; otherwise the base fills in. */
    private fun transformReferences(
        base: UriParts,
        ref: UriParts,
        options: ParseOptions,
    ): ParseResult<UriParts> {
        require(base.scheme != null) { "reference resolution requires an absolute base scheme" }
        val resolved =
            when {
                ref.scheme != null ->
                    boundedRemoveDotSegments(ref.path, options).map {
                        UriParts(ref.scheme, ref.authority, it, ref.query, null)
                    }
                else -> resolveRelative(base, ref, options)
            }
        return resolved.map { part ->
            val withFragment = part.copy(fragment = ref.fragment)
            check(withFragment.scheme != null) { "a resolved target must carry the base scheme" }
            withFragment
        }
    }

    /** §5.2.2 with no reference scheme: a defined reference authority replaces the base authority. */
    private fun resolveRelative(
        base: UriParts,
        ref: UriParts,
        options: ParseOptions,
    ): ParseResult<UriParts> =
        when {
            ref.authority != null ->
                boundedRemoveDotSegments(ref.path, options).map {
                    UriParts(base.scheme, ref.authority, it, ref.query, null)
                }
            else -> resolveNoAuthority(base, ref, options)
        }

    /** §5.2.2 with neither reference scheme nor authority: the base authority is inherited. */
    private fun resolveNoAuthority(
        base: UriParts,
        ref: UriParts,
        options: ParseOptions,
    ): ParseResult<UriParts> =
        resolvePathAndQuery(base, ref, options)
            .map { (path, query) -> UriParts(base.scheme, base.authority, path, query, null) }

    /** §5.2.2 path/query selection: empty reference path keeps the base path (and base query if absent). */
    private fun resolvePathAndQuery(
        base: UriParts,
        ref: UriParts,
        options: ParseOptions,
    ): ParseResult<Pair<String, String?>> =
        when {
            ref.path.isEmpty() -> keepBasePath(base, ref, options)
            ref.path.startsWith(SLASH) ->
                boundedRemoveDotSegments(ref.path, options).map { Pair(it, ref.query) }
            else ->
                mergedPath(base, ref.path, options)
                    .flatMap { boundedRemoveDotSegments(it, options) }
                    .map { Pair(it, ref.query) }
        }

    /**
     * §5.2.2 empty-reference-path case: the base path is kept verbatim. [base.path] is bounded only
     * by the parser's [ParseOptions.inputLength], so with a lowered [ParseOptions.expandedLength] the
     * kept path can exceed the recompose bound; guarding it here folds that to a returning
     * [UriParseError.InputTooLong] ([ERR-31]) rather than letting [recompose]'s invariant throw.
     */
    private fun keepBasePath(
        base: UriParts,
        ref: UriParts,
        options: ParseOptions,
    ): ParseResult<Pair<String, String?>> =
        if (base.path.length <= options.expandedLength) {
            ParseResult.Ok(Pair(base.path, ref.query ?: base.query))
        } else {
            ParseResult.Err(UriParseError.InputTooLong(base.path.length, options.expandedLength))
        }

    // --- §5.2.3 Merge Paths -----------------------------------------------------------------------

    /** §5.2.3: prepend the base's directory prefix (or `/` for an empty authority path) to [refPath]. */
    private fun merge(
        base: UriParts,
        refPath: String,
    ): String {
        require(refPath.isNotEmpty()) { "merge applies only to a non-empty relative-path reference" }
        require(!refPath.startsWith(SLASH)) { "merge applies only to a relative-path reference" }
        return when {
            base.authority != null && base.path.isEmpty() -> SLASH + refPath
            else -> mergeOntoBase(base.path, refPath)
        }
    }

    /** Appends [refPath] to all-but-the-last segment of [basePath] (everything up to the right-most `/`). */
    private fun mergeOntoBase(
        basePath: String,
        refPath: String,
    ): String {
        val slash = basePath.lastIndexOf('/')
        val prefix = if (slash >= 0) basePath.substring(0, slash + 1) else ""
        return prefix + refPath
    }

    /**
     * Guards [merge]'s concatenation: the only point two independently length-bounded paths (the
     * base's directory prefix and the reference path) combine into something that can exceed
     * [ParseOptions.expandedLength] (`ResourceLimit.ExpandedLength`, [ERR-31]), since every
     * already-parsed single path is bounded by the parser's own [ParseOptions.inputLength] cap.
     */
    private fun mergedPath(
        base: UriParts,
        refPath: String,
        options: ParseOptions,
    ): ParseResult<String> {
        val merged = merge(base, refPath)
        return if (merged.length <= options.expandedLength) {
            ParseResult.Ok(merged)
        } else {
            ParseResult.Err(UriParseError.InputTooLong(merged.length, options.expandedLength))
        }
    }

    /**
     * The bounded §5.2.4 entry every resolve path routes through: it enforces both
     * [ParseOptions.expandedLength] (a path longer than the bound → [UriParseError.InputTooLong],
     * [ERR-31]) and [ParseOptions.resolutionDepth] (more collapse iterations than the bound →
     * [UriParseError.LimitExceeded] carrying [ResourceLimit.ResolutionDepth], [ERR-33]) before
     * returning the cleaned path. The pure [removeDotSegments] carries neither gate, so no reachable
     * resolve call can exceed a limit unnoticed nor throw on one.
     */
    private fun boundedRemoveDotSegments(
        refPath: String,
        options: ParseOptions,
    ): ParseResult<String> {
        if (refPath.length > options.expandedLength) {
            return ParseResult.Err(UriParseError.InputTooLong(refPath.length, options.expandedLength))
        }
        val collapsed = collapseDotSegments(refPath)
        check(collapsed.iterations <= refPath.length + 1) { "iteration count exceeds the structural bound" }
        val depth = options.resolutionDepth
        return if (collapsed.iterations > depth) {
            ParseResult.Err(UriParseError.LimitExceeded(ResourceLimit.ResolutionDepth, collapsed.iterations, depth))
        } else {
            ParseResult.Ok(collapsed.output)
        }
    }

    // --- §5.2.4 Remove Dot Segments ---------------------------------------------------------------

    /**
     * One §5.2.4 loop iteration over the cursor: matches cases A–E on [path] starting at [pos],
     * appends at most one segment to [output], and returns the advanced cursor (always `> pos`).
     *
     * Cases B/C rewrite the buffer to a leading `/`; over an index cursor that `/` is simply the
     * last matched code unit left unconsumed (`pos + 2`/`pos + 3`), so no prefix is materialized —
     * except the `/.`/`/..` terminals, which have no trailing `/` to reuse and instead append the
     * single resulting `/` directly.
     */
    private fun step(
        path: String,
        pos: Int,
        output: StringBuilder,
    ): Int {
        require(pos in path.indices) { "cursor out of range: $pos" }
        return when {
            path.startsWith(PREFIX_DOTDOT_SLASH, pos) -> pos + PREFIX_DOTDOT_SLASH.length
            path.startsWith(PREFIX_DOT_SLASH, pos) -> pos + PREFIX_DOT_SLASH.length
            path.startsWith(PREFIX_SLASH_DOT_SLASH, pos) -> pos + SEGMENT_SLASH_DOT.length
            isTerminal(path, pos, SEGMENT_SLASH_DOT) -> appendSlashTerminal(output, path.length)
            path.startsWith(PREFIX_SLASH_DOTDOT_SLASH, pos) -> popSegment(output, pos + SEGMENT_SLASH_DOTDOT.length)
            isTerminal(path, pos, SEGMENT_SLASH_DOTDOT) -> popThenSlashTerminal(output, path.length)
            isTerminal(path, pos, SEGMENT_DOT) || isTerminal(path, pos, SEGMENT_DOTDOT) -> path.length
            else -> moveSegment(path, pos, output)
        }
    }

    /** True when the whole remaining input of [path] from [pos] equals [segment] (a §5.2.4 terminal). */
    private fun isTerminal(
        path: String,
        pos: Int,
        segment: String,
    ): Boolean = pos + segment.length == path.length && path.startsWith(segment, pos)

    /** §5.2.4 case B `/.` terminal: the collapsed buffer is a lone `/`, appended straight to [output]. */
    private fun appendSlashTerminal(
        output: StringBuilder,
        end: Int,
    ): Int {
        output.append('/')
        return end
    }

    /** §5.2.4 case C `/..` terminal: pop the last output segment, then append the resulting lone `/`. */
    private fun popThenSlashTerminal(
        output: StringBuilder,
        end: Int,
    ): Int {
        removeLastSegment(output)
        output.append('/')
        return end
    }

    /** §5.2.4 case C: drop the last output segment and advance the cursor to the rewritten leading `/`. */
    private fun popSegment(
        output: StringBuilder,
        next: Int,
    ): Int {
        removeLastSegment(output)
        return next
    }

    /**
     * §5.2.4 case E: move the first path segment (its leading `/`, if any, then up to the next `/`)
     * from [path] at [pos] to [output], returning the cursor at that next `/` or end-of-input.
     */
    private fun moveSegment(
        path: String,
        pos: Int,
        output: StringBuilder,
    ): Int {
        require(pos in path.indices) { "cursor out of range: $pos" }
        val next = path.indexOf('/', pos + 1)
        val end = if (next < 0) path.length else next
        check(end > pos) { "segment boundary did not advance past $pos" }
        output.appendRange(path, pos, end)
        return end
    }

    /** §5.2.4 case C helper: removes the last segment and its preceding `/` (if any) from [output]. */
    private fun removeLastSegment(output: StringBuilder) {
        val slash = output.lastIndexOf('/')
        val cut = if (slash >= 0) slash else 0
        // setLength's own precondition: the cut can only shorten the buffer, never grow it.
        check(cut <= output.length) { "output cut index past the buffer end: $cut > ${output.length}" }
        output.setLength(cut)
    }

    /** The cleaned path produced by [collapseDotSegments] and the iteration count that produced it. */
    private data class DotSegmentCollapse(
        val output: String,
        val iterations: Int,
    )

    // --- §5.3 Component Recomposition -------------------------------------------------------------

    /**
     * §5.3: assembles the five resolved components back into a URI-reference string, applying the
     * §3.3 `/.`-guard so an authority-less path produced by dot-segment removal never re-parses as
     * fabricating an authority (RFC 3986 §3.3/§5.2.2).
     *
     * Total by construction: every path reaching here has already been bounded by a returning call
     * site ([keepBasePath] for the empty-reference case, [boundedRemoveDotSegments] for every
     * dot-segment-collapsed path, [mergedPath] for the §5.2.3 concatenation), so no length check is
     * needed and this can never throw on a length overflow.
     */
    private fun recompose(parts: UriParts): String {
        val sb = StringBuilder()
        if (parts.scheme != null) sb.append(parts.scheme).append(':')
        if (parts.authority != null) sb.append(DOUBLE_SLASH).append(parts.authority)
        sb.append(guardRecomposedUriPath(parts.scheme, parts.authority != null, parts.path))
        if (parts.query != null) sb.append('?').append(parts.query)
        if (parts.fragment != null) sb.append('#').append(parts.fragment)
        check(sb.length >= parts.path.length) { "recomposition dropped path characters" }
        return sb.toString()
    }

    // --- string-form derivation (Appendix B raw split) --------------------------------------------

    /** Routes the validated parse results to [ParseResult.Err] on failure / missing scheme, else resolves. */
    private fun resolveOutcome(
        baseUri: String,
        reference: String,
        base: ParseResult<ParsedComponents>,
        ref: ParseResult<ParsedComponents>,
        options: ParseOptions,
    ): ParseResult<String> =
        when {
            base is ParseResult.Err -> base
            ref is ParseResult.Err -> ref
            base is ParseResult.Ok && base.value.scheme == null -> ParseResult.Err(UriParseError.MissingScheme)
            else -> resolveStrings(baseUri, reference, options)
        }

    /** Splits both strings into raw 5-tuples, transforms, and recomposes the target URI string. */
    private fun resolveStrings(
        baseUri: String,
        reference: String,
        options: ParseOptions,
    ): ParseResult<String> {
        val baseParts = splitUri(baseUri)
        require(baseParts.scheme != null) { "an absolute base must carry a scheme" }
        return transformReferences(baseParts, splitUri(reference), options).map { target ->
            check(target.scheme != null) { "the resolved target must be absolute" }
            recompose(target)
        }
    }

    /** Appendix B split into the raw five parts, peeling fragment then query then the hierarchical part. */
    private fun splitUri(uri: String): UriParts {
        val hash = uri.indexOf('#')
        val fragment = if (hash < 0) null else uri.substring(hash + 1)
        val beforeFragment = if (hash < 0) uri else uri.substring(0, hash)
        val mark = beforeFragment.indexOf('?')
        val query = if (mark < 0) null else beforeFragment.substring(mark + 1)
        val hier = if (mark < 0) beforeFragment else beforeFragment.substring(0, mark)
        return splitHier(hier, query, fragment)
    }

    /** Splits the hierarchical part into scheme / authority / raw path (the path keeps any leading `/`). */
    private fun splitHier(
        hier: String,
        query: String?,
        fragment: String?,
    ): UriParts {
        val (scheme, rest) = splitSchemePrefix(hier)
        val (authority, path) = splitAuthorityPath(rest)
        return UriParts(scheme, authority, path, query, fragment)
    }

    /** Detects a leading `scheme:` exactly as [UriParser] does (a `:` before any `/`, valid per §3.1). */
    private fun splitSchemePrefix(hier: String): Pair<String?, String> {
        val colon = schemeColonIndex(hier)
        return when {
            colon >= 0 && Scheme.isValidScheme(hier.substring(0, colon)) ->
                Pair(hier.substring(0, colon), hier.substring(colon + 1))

            else -> Pair(null, hier)
        }
    }

    /** Separates a leading `//authority` from the raw path; absent `//` makes the whole remainder the path. */
    private fun splitAuthorityPath(rest: String): Pair<String?, String> =
        when {
            rest.startsWith(DOUBLE_SLASH) -> splitAfterAuthority(rest)
            else -> Pair(null, rest)
        }

    /** Authority runs from after `//` to the next `/` (which begins the path), or to end-of-input. */
    private fun splitAfterAuthority(rest: String): Pair<String?, String> {
        require(rest.startsWith(DOUBLE_SLASH)) { "authority split needs a leading //" }
        val slash = rest.indexOf('/', DOUBLE_SLASH.length)
        val end = if (slash < 0) rest.length else slash
        val authority = rest.substring(DOUBLE_SLASH.length, end)
        val path = if (slash < 0) "" else rest.substring(slash)
        return Pair(authority, path)
    }

    // --- structured-form derivation (ParsedComponents) --------------------------------------------

    /** Derives the behaviour-free 5-tuple from parsed components (path via [toUriPathString]). */
    private fun partsOf(c: ParsedComponents): UriParts =
        UriParts(c.scheme, authorityOf(c), c.path.toUriPathString(), c.query, c.fragment)

    /** Re-serializes the authority (`userinfo@host:port`) from components, or `null` when none is present. */
    private fun authorityOf(c: ParsedComponents): String? {
        val host = c.host ?: return null
        val port = if (c.port != null) ":${c.port}" else ""
        return userinfoPrefix(c) + serializeHost(host) + port
    }

    /**
     * Builds the `userinfo@` prefix from the decoded credentials, or `""` when no userinfo is
     * present. `Resolver` serves only the `Uri` profile, so this follows [NORM-16]'s null/empty
     * rule: presence is tracked by nullability, not emptiness, so a present-but-empty username
     * or password still contributes its `@` or `:` ([MODEL-11]).
     */
    private fun userinfoPrefix(c: ParsedComponents): String {
        val username = c.username
        val password = c.password
        if (username == null && password == null) return ""
        val passwordPart = if (password != null) ":$password" else ""
        return "${username.orEmpty()}$passwordPart@"
    }

    /** The behaviour-free five-component view the §5 algorithm operates on (each component nullable per §5.3). */
    private data class UriParts(
        val scheme: String?,
        val authority: String?,
        val path: String,
        val query: String?,
        val fragment: String?,
    )
}
