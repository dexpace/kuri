/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.parser

import org.dexpace.kuri.error.ParseResult
import org.dexpace.kuri.error.UriParseError
import org.dexpace.kuri.error.ValidationError
import org.dexpace.kuri.error.ValidationErrorKind
import org.dexpace.kuri.percent.PercentCodec
import org.dexpace.kuri.percent.PercentEncodeSets
import org.dexpace.kuri.text.isC0ControlOrSpace

/**
 * The maximum number of state re-entries permitted per input code point, a fixed loop bound
 * ([PARSE-9]; "limits on everything"). Each code point is visited by a bounded number of
 * non-consuming (reconsume) states before a consuming transition, so this safely caps the loop.
 */
private const val MAX_REENTRIES_PER_CODE_POINT: Int = 40

/**
 * The WHATWG **basic URL parser** for the `Url` profile — the §8 state machine for special and
 * non-special schemes (SPEC §8; WHATWG "basic URL parser"; ada `src/parser.cpp`).
 *
 * Pre-processing (§8.1) strips tab/LF/CR and trims C0-or-space, then prunes the fragment; the
 * §8.3 state machine runs over a single non-decreasing pointer ([PARSE-9]/[PARSE-10]) with one
 * private function per state (in `UrlParserStates.kt` and `UrlParserAuthority.kt`), dispatched
 * through [STATE_HANDLERS]. Components accumulate in their eager-canonical (percent-encoded)
 * `Url`-profile form and are snapshotted into an immutable [ParsedComponents] on success.
 *
 * Failures are returned as [ParseResult.Err] values, never thrown ([ERR-1]).
 */
internal object UrlParser {
    /**
     * Parses [input] under the `Url` profile, optionally resolving a relative reference against
     * [base] (SPEC §8; WHATWG basic URL parser).
     *
     * @param input the URL string to parse; pre-processing repairs are recorded as validation
     *   errors rather than rejected (§8.1).
     * @param base the optional base components for relative resolution (§8.3); `null` for an
     *   absolute parse.
     * @return [ParseResult.Ok] with the decomposed [ParsedComponents] (carrying any validation
     *   errors), or [ParseResult.Err] with the first fatal [UriParseError].
     */
    internal fun parse(
        input: String,
        base: ParsedComponents? = null,
    ): ParseResult<ParsedComponents> {
        val errors = mutableListOf<ValidationError>()
        val (trimmed, trimStart) = trimC0OrSpace(input, errors)
        val stripped = stripTabAndNewline(trimmed, trimStart, errors)
        val (body, fragmentRaw) = pruneFragment(stripped)
        val state = UrlParserState(body, base, fragmentRaw, errors)
        val failure = runStateMachine(state)
        return if (failure != null) ParseResult.Err(failure) else ParseResult.Ok(finalize(state))
    }

    /**
     * Runs the state machine with a setter [override] over [value], seeded from [seed] (WHATWG
     * URL §5 "basic URL parser with … state override"). Unlike [parse], the leading/trailing
     * C0-or-space trim and the fragment prune are skipped (the value is a single component); only
     * tab/newline removal applies. Returns [ParseResult.Err] on a fatal component error and — via
     * the [UrlTransition.Abort] path — signals a WHATWG no-op by returning the seed unchanged.
     */
    internal fun parseWithOverride(
        value: String,
        seed: ParsedComponents,
        override: StateOverride,
    ): ParseResult<ParsedComponents> {
        val errors = mutableListOf<ValidationError>()
        val stripped = stripTabAndNewline(value, originalOffset = 0, errors)
        val state = UrlParserState(stripped, seed, override, errors)
        // WHATWG pathname setter step "empty this's URL's path" before re-entering path-start
        // (WHATWG URL §5); the seeded segments must not survive into the re-parse.
        if (override == StateOverride.PATHNAME) state.path.clear()
        // WHATWG search setter step "set this's URL's query to the empty string" (URL §5); the
        // QUERY state handler appends to the seeded query, so it must be reset here or a
        // withSearch call would concatenate onto the old query instead of replacing it.
        if (override == StateOverride.QUERY) state.query = ""
        // Map each override to the UrlState the basic URL parser re-enters (WHATWG URL §5 setters).
        state.state =
            when (override) {
                StateOverride.PROTOCOL -> UrlState.SCHEME_START
                StateOverride.HOST, StateOverride.HOSTNAME -> UrlState.HOST
                StateOverride.PORT -> UrlState.PORT
                StateOverride.PATHNAME -> UrlState.PATH_START
                StateOverride.QUERY -> UrlState.QUERY
            }
        // The finalized components carry only the validation errors from this override run, not the
        // seed URL's original parse errors -- `errors` is a fresh list, and validationErrors is a
        // per-operation diagnostic, so a setter result's errors reflect the setter, never a cumulative
        // history. (The fragment is untouched by every override, so it is carried straight from seed.)
        return when (val outcome = runOverride(state)) {
            OverrideOutcome.ABORT -> ParseResult.Ok(seed)
            OverrideOutcome.OK -> ParseResult.Ok(finalize(state).copy(fragment = seed.fragment))
            is OverrideOutcome.Fail -> ParseResult.Err(outcome.error)
        }
    }

    /** The three terminal outcomes of an override run. */
    private sealed interface OverrideOutcome {
        data object OK : OverrideOutcome

        data object ABORT : OverrideOutcome

        data class Fail(
            val error: UriParseError,
        ) : OverrideOutcome
    }

    /**
     * Drives the override loop. Terminates on [UrlTransition.Done]/[UrlTransition.Abort], on a
     * fatal [UrlTransition.Fail], or when an [UrlTransition.Advance]/[UrlTransition.Reconsume]
     * reaches EOF — the natural end of a single-component value. A fixed iteration bound guards any
     * reconsume chain.
     */
    private fun runOverride(state: UrlParserState): OverrideOutcome {
        val maxIterations = (state.input.length + 1).toLong() * MAX_REENTRIES_PER_CODE_POINT
        var iterations = 0L
        var outcome: OverrideOutcome? = null
        while (outcome == null) {
            check(iterations++ < maxIterations) { "override state machine exceeded its iteration bound" }
            outcome = stepOverride(state, STATE_HANDLERS.getValue(state.state)(state))
        }
        return outcome
    }

    /**
     * Applies one [transition] under an override run, returning the terminal [OverrideOutcome] or
     * `null` to continue the loop. Mirrors [runStateMachine]'s handling exactly: [Reconsume] never
     * inspects [UrlParserState.pos] itself — it only changes state and lets the loop re-invoke the
     * new state's handler, even at EOF, since that handler is what finalizes a component (e.g. PATH
     * appending the root segment, or FILE_HOST clearing an empty host). Only [Advance] terminates on
     * EOF (after the *current* state has already run once, having just consumed the final code
     * point), the natural end of a single-component value.
     */
    private fun stepOverride(
        state: UrlParserState,
        transition: UrlTransition,
    ): OverrideOutcome? =
        when (transition) {
            is UrlTransition.Done -> OverrideOutcome.OK
            is UrlTransition.Abort -> OverrideOutcome.ABORT
            is UrlTransition.Fail -> OverrideOutcome.Fail(transition.error)
            is UrlTransition.Reconsume -> {
                state.state = transition.next
                null
            }
            is UrlTransition.Advance -> {
                state.state = transition.next
                when {
                    state.pos >= state.input.length -> OverrideOutcome.OK
                    else -> {
                        state.pos += 1
                        null
                    }
                }
            }
        }

    /** The dispatch table mapping each [UrlState] to its single state function (§8.3). */
    private val STATE_HANDLERS: Map<UrlState, (UrlParserState) -> UrlTransition> =
        mapOf(
            UrlState.SCHEME_START to UrlParserStates::schemeStartState,
            UrlState.SCHEME to UrlParserStates::schemeState,
            UrlState.NO_SCHEME to UrlParserStates::noSchemeState,
            UrlState.SPECIAL_RELATIVE_OR_AUTHORITY to UrlParserStates::specialRelativeOrAuthorityState,
            UrlState.PATH_OR_AUTHORITY to UrlParserStates::pathOrAuthorityState,
            UrlState.RELATIVE to UrlParserStates::relativeState,
            UrlState.RELATIVE_SLASH to UrlParserStates::relativeSlashState,
            UrlState.SPECIAL_AUTHORITY_SLASHES to UrlParserStates::specialAuthoritySlashesState,
            UrlState.SPECIAL_AUTHORITY_IGNORE_SLASHES to UrlParserStates::specialAuthorityIgnoreSlashesState,
            UrlState.AUTHORITY to UrlParserAuthority::authorityState,
            UrlState.HOST to UrlParserAuthority::hostState,
            UrlState.PORT to UrlParserAuthority::portState,
            UrlState.FILE to UrlParserAuthority::fileState,
            UrlState.FILE_SLASH to UrlParserAuthority::fileSlashState,
            UrlState.FILE_HOST to UrlParserAuthority::fileHostState,
            UrlState.PATH_START to UrlParserStates::pathStartState,
            UrlState.PATH to UrlParserStates::pathState,
            UrlState.OPAQUE_PATH to UrlParserStates::opaquePathState,
            UrlState.QUERY to UrlParserStates::queryState,
        )

    /**
     * Drives the §8.3 state loop until termination at EOF or a fatal failure (§8.2).
     *
     * Each iteration runs the current state's function: [UrlTransition.Advance] consumes the
     * code point (terminating when already at EOF), [UrlTransition.Reconsume] re-runs without
     * advancing ([PARSE-11]), and [UrlTransition.Fail] aborts. A fixed iteration bound guards
     * against any non-terminating reconsume chain.
     *
     * @return the fatal [UriParseError], or `null` on successful termination.
     */
    private fun runStateMachine(state: UrlParserState): UriParseError? {
        val maxIterations = (state.input.length + 1).toLong() * MAX_REENTRIES_PER_CODE_POINT
        var iterations = 0L
        while (true) {
            check(iterations++ < maxIterations) { "state machine exceeded its iteration bound" }
            val handler = STATE_HANDLERS.getValue(state.state)
            when (val transition = handler(state)) {
                is UrlTransition.Fail -> return transition.error
                is UrlTransition.Reconsume -> state.state = transition.next
                is UrlTransition.Advance -> {
                    state.state = transition.next
                    if (state.pos == state.input.length) return null
                    state.pos += 1
                }
                is UrlTransition.Done, is UrlTransition.Abort ->
                    error("Done/Abort are override-only transitions")
            }
        }
    }

    /** Snapshots the mutable [state] into the immutable [ParsedComponents] result (§3, §8). */
    private fun finalize(state: UrlParserState): ParsedComponents {
        val path =
            if (state.isOpaque) ComponentPath.Opaque(state.opaque) else ComponentPath.Segments(state.path.toList())
        val fragment = state.fragmentRaw?.let { raw -> finalizeFragment(state, raw) }
        return ParsedComponents(
            scheme = state.scheme,
            username = state.username,
            password = state.password,
            host = state.host,
            port = state.port,
            path = path,
            query = state.query,
            fragment = fragment,
            validationErrors = state.errors.toList(),
        )
    }

    /**
     * Percent-encodes [raw] under the fragment set and records [ValidationErrorKind.INVALID_URL_UNIT]
     * for any out-of-repertoire code point it contains ([PARSE-59]). The fragment sits just past the
     * pruned `#` in [state]'s pre-processed input, so its offsets continue that coordinate space:
     * `state.input.length` is the `#` itself, `+ 1` is the fragment's first code unit.
     */
    private fun finalizeFragment(
        state: UrlParserState,
        raw: String,
    ): String {
        recordInvalidUrlCodePoints(state, raw, textOffset = state.input.length + 1)
        return PercentCodec.encode(raw, PercentEncodeSets.FRAGMENT)
    }

    /**
     * Trims leading/trailing C0-control-or-space, recording one validation error if any (§8.1
     * [PARSE-5]) at the offset of the first trimmed code point in [input] (leading trim always
     * starts at `0`; a trailing-only trim is recorded at the first trailing code point removed).
     *
     * @return the trimmed text paired with how many leading code units were removed, so callers
     *   downstream of the trim can translate their own local offsets back into [input]'s coordinates.
     */
    private fun trimC0OrSpace(
        input: String,
        errors: MutableList<ValidationError>,
    ): Pair<String, Int> {
        var start = 0
        var end = input.length
        while (start < end && input[start].isC0ControlOrSpace()) {
            start++
        }
        while (end > start && input[end - 1].isC0ControlOrSpace()) {
            end--
        }
        val trimmed = input.substring(start, end)
        if (trimmed.length != input.length) {
            val at = if (start > 0) 0 else end
            errors.add(ValidationError(ValidationErrorKind.LEADING_OR_TRAILING_C0_CONTROL_OR_SPACE, at))
        }
        return trimmed to start
    }

    /**
     * Removes every tab/LF/CR, recording one validation error if any was present (§8.1 [PARSE-3])
     * at the offset of the first one removed. [originalOffset] is how many code units [input]
     * itself is already shifted from the *original* input (the coordinate space [ValidationError.at]
     * uses for this pre-processing-stage kind, per [trimC0OrSpace]); `0` when no trim preceded this.
     */
    private fun stripTabAndNewline(
        input: String,
        originalOffset: Int,
        errors: MutableList<ValidationError>,
    ): String {
        require(originalOffset >= 0) { "original offset must be non-negative: $originalOffset" }
        val out = StringBuilder(input.length)
        var firstRemovedAt = -1
        for (i in input.indices) {
            val ch = input[i]
            if (ch == '\t' || ch == '\n' || ch == '\r') {
                if (firstRemovedAt < 0) firstRemovedAt = originalOffset + i
            } else {
                out.append(ch)
            }
        }
        if (firstRemovedAt >= 0) {
            errors.add(ValidationError(ValidationErrorKind.TAB_OR_NEWLINE_REMOVED, at = firstRemovedAt))
        }
        return out.toString()
    }

    /** Splits off the fragment at the first `#` (§8.1 [PARSE-7]); the body is parsed, the fragment re-attached. */
    private fun pruneFragment(input: String): Pair<String, String?> {
        val hash = input.indexOf('#')
        return if (hash < 0) {
            input to null
        } else {
            input.substring(0, hash) to input.substring(hash + 1)
        }
    }
}
