/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.parser

import org.dexpace.kuri.error.ValidationError
import org.dexpace.kuri.host.Host
import org.dexpace.kuri.scheme.Scheme

/**
 * The single mutable work area threaded through the §8.3 state machine (WHATWG basic URL parser
 * locals: `buffer`, `atSignSeen`, and the URL being built).
 *
 * One non-decreasing [pos] is the only cursor ([PARSE-9]); EOF is the in-band sentinel
 * `pos == input.length` ([PARSE-10]). Components accumulate here in their `Url`-profile canonical
 * (percent-encoded) form and are snapshotted into an immutable [ParsedComponents] on success.
 * `insideBrackets` and `passwordTokenSeen` from the WHATWG locals are computed locally by the
 * forward-scanning host/userinfo helpers rather than carried as fields.
 *
 * @property input the pre-processed input (tab/newline stripped, trimmed, fragment pruned); the
 *   state machine never sees the original raw string.
 * @property base the optional base components for relative resolution (§8.3
 *   NO_SCHEME/RELATIVE/FILE).
 * @property fragmentRaw the pruned fragment text (without `#`), or `null` when the input had no
 *   `#` ([PARSE-7]).
 * @property errors the validation-error sink, pre-seeded with pre-processing anomalies (§8.1).
 */
internal class UrlParserState(
    val input: String,
    val base: ParsedComponents?,
    val fragmentRaw: String?,
    val errors: MutableList<ValidationError>,
) {
    /** The single non-decreasing pointer; `pos == input.length` is the EOF sentinel ([PARSE-10]). */
    var pos: Int = 0

    /** The current state; the loop dispatches on this each iteration. */
    var state: UrlState = UrlState.SCHEME_START

    /** The active setter override, or `null` for a full parse; gates the override-only branches. */
    var stateOverride: StateOverride? = null

    /** Scratch accumulator for the scheme and per-segment buffers (WHATWG `buffer`). */
    val buffer: StringBuilder = StringBuilder()

    /** The lowercased scheme, or `null` until SCHEME resolves it (or a relative copies a base). */
    var scheme: String? = null

    /** Whether the resolved scheme is one of the six special schemes (§6, [PARSE-51]). */
    var special: Boolean = false

    /** The encoded userinfo username (decoded-equivalent default `""`, §8.4 [PARSE-47]). */
    var username: String = ""

    /** The encoded userinfo password (`""` when absent, §8.4 [PARSE-45]). */
    var password: String = ""

    /** The parsed host, `null` until an authority is seen, [Host.Empty] for an empty authority. */
    var host: Host? = null

    /** The explicit port, or `null` when unspecified / default-elided ([PARSE-34]). */
    var port: Int? = null

    /** The path segment list (encoded), used when [isOpaque] is false. */
    val path: MutableList<String> = mutableListOf()

    /** Whether the path is opaque (cannot-be-a-base); [opaque] then holds its text ([PARSE-41]). */
    var isOpaque: Boolean = false

    /** The opaque path text (encoded, no leading `/`), valid only when [isOpaque]. */
    var opaque: String = ""

    /** The query without the leading `?`; `null` until a `?` is seen ([PARSE-42]). */
    var query: String? = null

    /** WHATWG `atSignSeen`: whether a userinfo `@` was consumed in the authority (§8.4). */
    var atSignSeen: Boolean = false

    /**
     * Seeds the work area from an existing [seed] for a setter [override] run (WHATWG "set url to
     * this's URL" then run the parser with a state override). The [value] is the component input;
     * [base] is always `null` and the fragment is carried by the setter (not re-parsed here).
     */
    internal constructor(
        value: String,
        seed: ParsedComponents,
        override: StateOverride,
        errors: MutableList<ValidationError>,
    ) : this(value, base = null, fragmentRaw = null, errors = errors) {
        scheme = seed.scheme
        special = seed.scheme?.let { Scheme.isSpecial(it) } ?: false
        username = seed.username
        password = seed.password
        host = seed.host
        port = seed.port
        when (val seedPath = seed.path) {
            is ComponentPath.Opaque -> {
                isOpaque = true
                opaque = seedPath.path
            }
            is ComponentPath.Segments -> path.addAll(seedPath.segments)
        }
        query = seed.query
        stateOverride = override
    }

    /** The current code point as a [Char], or `null` at EOF (`pos == length`, [PARSE-10]). */
    fun currentChar(): Char? = if (pos < input.length) input[pos] else null

    /** The code point [offset] positions after [pos], or `null` past EOF. */
    fun peek(offset: Int): Char? {
        require(offset >= 0) { "peek offset must be non-negative: $offset" }
        val at = pos + offset
        return if (at < input.length) input[at] else null
    }

    /** The substring strictly after the current code point (WHATWG "remaining"). */
    fun remaining(): String = input.substring((pos + 1).coerceAtMost(input.length))

    /** The substring from the current code point to the end (WHATWG "from pointer"). */
    fun fromCurrent(): String = input.substring(pos.coerceAtMost(input.length))
}
