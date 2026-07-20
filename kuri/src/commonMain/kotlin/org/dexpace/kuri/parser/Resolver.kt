/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.parser

import org.dexpace.kuri.ParseOptions
import org.dexpace.kuri.ZONE_ID_ENABLED
import org.dexpace.kuri.carriesZoneId
import org.dexpace.kuri.error.ParseResult
import org.dexpace.kuri.error.UriParseError
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
     * A pure string transform: the input buffer is consumed prefix by prefix (cases A–E) into the
     * output buffer, which is returned. Each iteration strictly shrinks the input, so the loop is
     * bounded by the input length.
     *
     * @param path the merged/extracted path to normalize; may be empty.
     * @param options supplies the [ParseOptions.expandedLength] bound this call asserts against;
     *   defaults to [ParseOptions.DEFAULT] for the many direct, non-parse-configured call sites
     *   (tests, and the structured resolve's own internal use).
     * @return the path with extraneous dot-segments removed.
     */
    internal fun removeDotSegments(
        path: String,
        options: ParseOptions = ParseOptions.DEFAULT,
    ): String {
        require(path.length <= options.expandedLength) { "path exceeds the supported length bound: ${path.length}" }
        val output = StringBuilder()
        var input = path
        val maxIterations = path.length + 1
        var iterations = 0
        while (input.isNotEmpty() && iterations <= maxIterations) {
            input = step(input, output)
            iterations++
        }
        check(input.isEmpty()) { "remove_dot_segments failed to consume the input buffer" }
        return output.toString()
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
     * @return [ParseResult.Ok] with the resolved target components, or [ParseResult.Err] when the
     *   recomposed target does not parse — e.g. dot-segment removal produced a `//`-leading, authority-less
     *   path that re-reads as an invalid authority.
     */
    internal fun resolve(
        base: ParsedComponents,
        reference: ParsedComponents,
    ): ParseResult<ParsedComponents> {
        require(base.scheme != null) { "structured resolution requires an absolute base scheme" }
        val options = structuredOptions(base, reference)
        return when (val target = transformReferences(partsOf(base), partsOf(reference), options)) {
            is ParseResult.Err -> target
            is ParseResult.Ok -> UriParser.parse(recompose(target.value, options), options)
        }
    }

    /** Derives the round-trip [ParseOptions] for a structured resolve: a zone id on either input opts in. */
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
            ref.path.isEmpty() -> ParseResult.Ok(Pair(base.path, ref.query ?: base.query))
            ref.path.startsWith(SLASH) ->
                boundedRemoveDotSegments(ref.path, options).map { Pair(it, ref.query) }
            else -> mergedPath(base, ref.path, options).map { Pair(removeDotSegments(it, options), ref.query) }
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
     * Guards a [removeDotSegments] call on [refPath] that is not routed through [mergedPath] (which
     * already guards its own concatenation). Every direct, non-merged reference path must be checked
     * the same way before it reaches [removeDotSegments]'s precondition.
     */
    private fun boundedRemoveDotSegments(
        refPath: String,
        options: ParseOptions,
    ): ParseResult<String> =
        if (refPath.length <= options.expandedLength) {
            ParseResult.Ok(removeDotSegments(refPath, options))
        } else {
            ParseResult.Err(UriParseError.InputTooLong(refPath.length, options.expandedLength))
        }

    // --- §5.2.4 Remove Dot Segments ---------------------------------------------------------------

    /** One §5.2.4 loop iteration: matches cases A–E and returns the remaining input buffer. */
    private fun step(
        input: String,
        output: StringBuilder,
    ): String =
        when {
            input.startsWith(PREFIX_DOTDOT_SLASH) -> input.substring(PREFIX_DOTDOT_SLASH.length)
            input.startsWith(PREFIX_DOT_SLASH) -> input.substring(PREFIX_DOT_SLASH.length)
            input.startsWith(PREFIX_SLASH_DOT_SLASH) -> SLASH + input.substring(PREFIX_SLASH_DOT_SLASH.length)
            input == SEGMENT_SLASH_DOT -> SLASH
            input.startsWith(PREFIX_SLASH_DOTDOT_SLASH) -> popAndReplace(output, input, PREFIX_SLASH_DOTDOT_SLASH)
            input == SEGMENT_SLASH_DOTDOT -> popAndReplace(output, input, input)
            input == SEGMENT_DOT || input == SEGMENT_DOTDOT -> ""
            else -> moveSegment(input, output)
        }

    /** §5.2.4 case C: drop the last output segment, then replace the matched [prefix] with `/`. */
    private fun popAndReplace(
        output: StringBuilder,
        input: String,
        prefix: String,
    ): String {
        require(input.length >= prefix.length) { "matched prefix is longer than the input buffer" }
        removeLastSegment(output)
        return SLASH + input.substring(prefix.length)
    }

    /** §5.2.4 case E: move the first path segment (with its leading `/`, if any) to the output buffer. */
    private fun moveSegment(
        input: String,
        output: StringBuilder,
    ): String {
        require(input.isNotEmpty()) { "moveSegment requires a non-empty input buffer" }
        val next = input.indexOf('/', 1)
        val end = if (next < 0) input.length else next
        output.append(input.substring(0, end))
        check(end in 1..input.length) { "segment boundary out of range: $end" }
        return input.substring(end)
    }

    /** §5.2.4 case C helper: removes the last segment and its preceding `/` (if any) from [output]. */
    private fun removeLastSegment(output: StringBuilder) {
        val slash = output.lastIndexOf('/')
        val cut = if (slash >= 0) slash else 0
        check(cut in 0..output.length) { "output cut index out of range: $cut" }
        output.setLength(cut)
    }

    // --- §5.3 Component Recomposition -------------------------------------------------------------

    /**
     * §5.3: assembles the five resolved components back into a URI-reference string, applying the
     * §3.3 `/.`-guard so an authority-less path produced by dot-segment removal never re-parses as
     * fabricating an authority (RFC 3986 §3.3/§5.2.2). The length invariant is a postcondition, not a
     * precondition: by the time [transformReferences] returns [ParseResult.Ok], its path is already
     * known to fit (the one path that can exceed [ParseOptions.expandedLength] — [merge]'s
     * concatenation — is caught earlier, in [mergedPath]), so a violation here means this class's own
     * invariant broke.
     */
    private fun recompose(
        parts: UriParts,
        options: ParseOptions,
    ): String {
        check(parts.path.length <= options.expandedLength) { "resolved path exceeds the supported length bound" }
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
            recompose(target, options)
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
