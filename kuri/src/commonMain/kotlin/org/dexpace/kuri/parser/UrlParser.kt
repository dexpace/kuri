/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.parser

import org.dexpace.kuri.error.ParseResult
import org.dexpace.kuri.error.UriParseError
import org.dexpace.kuri.error.ValidationError
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
        val trimmed = trimC0OrSpace(input, errors)
        val stripped = stripTabAndNewline(trimmed, errors)
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
        val stripped = stripTabAndNewline(value, errors)
        val state = UrlParserState(stripped, seed, override, errors)
        // WHATWG pathname setter step "empty this's URL's path" before re-entering path-start
        // (WHATWG URL §5); the seeded segments must not survive into the re-parse.
        if (override == StateOverride.PATHNAME) state.path.clear()
        // Map each override to the UrlState the basic URL parser re-enters (WHATWG URL §5 setters).
        state.state =
            when (override) {
                StateOverride.PROTOCOL -> UrlState.SCHEME_START
                StateOverride.HOST, StateOverride.HOSTNAME -> UrlState.HOST
                StateOverride.PORT -> UrlState.PORT
                StateOverride.PATHNAME -> UrlState.PATH_START
                StateOverride.QUERY -> UrlState.QUERY
            }
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
     * `null` to continue the loop. [UrlTransition.Advance]/[UrlTransition.Reconsume] terminate with
     * [OverrideOutcome.OK] at EOF (the natural end of a single-component value).
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
                if (state.pos >= state.input.length && atComponentEnd(state)) OverrideOutcome.OK else null
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

    /** True when the override has finished its component and further states would over-run. */
    private fun atComponentEnd(state: UrlParserState): Boolean =
        when (state.stateOverride) {
            StateOverride.PATHNAME -> state.state == UrlState.PATH_START || state.state == UrlState.PATH
            else -> true
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
        val path = if (state.isOpaque) UrlPath.Opaque(state.opaque) else UrlPath.Segments(state.path.toList())
        val fragment = state.fragmentRaw?.let { PercentCodec.encode(it, PercentEncodeSets.FRAGMENT) }
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

    /** Trims leading/trailing C0-control-or-space, recording one validation error if any (§8.1 [PARSE-5]). */
    private fun trimC0OrSpace(
        input: String,
        errors: MutableList<ValidationError>,
    ): String {
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
            errors.add(ValidationError.LEADING_OR_TRAILING_C0_CONTROL_OR_SPACE)
        }
        return trimmed
    }

    /** Removes every tab/LF/CR, recording one validation error if any was present (§8.1 [PARSE-3]). */
    private fun stripTabAndNewline(
        input: String,
        errors: MutableList<ValidationError>,
    ): String {
        val out = StringBuilder(input.length)
        for (ch in input) {
            if (ch != '\t' && ch != '\n' && ch != '\r') out.append(ch)
        }
        if (out.length != input.length) {
            errors.add(ValidationError.TAB_OR_NEWLINE_REMOVED)
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
