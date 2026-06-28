/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.parser

/**
 * The discrete states of the WHATWG basic URL parser, the `Url`-profile state machine of
 * SPEC §8.3 ([PARSE-12]–[PARSE-43]; WHATWG "basic URL parser").
 *
 * Each member is driven by exactly one state function (in [UrlParserStates] /
 * [UrlParserAuthority]). `FRAGMENT` is intentionally absent: the fragment is pruned in
 * pre-processing (§8.1 [PARSE-7]) and re-attached on finalize, so it is never a loop state
 * ([PARSE-43]).
 */
internal enum class UrlState {
    SCHEME_START,
    SCHEME,
    NO_SCHEME,
    SPECIAL_RELATIVE_OR_AUTHORITY,
    PATH_OR_AUTHORITY,
    RELATIVE,
    RELATIVE_SLASH,
    SPECIAL_AUTHORITY_SLASHES,
    SPECIAL_AUTHORITY_IGNORE_SLASHES,
    AUTHORITY,
    HOST,
    PORT,
    FILE,
    FILE_SLASH,
    FILE_HOST,
    PATH_START,
    PATH,
    OPAQUE_PATH,
    QUERY,
}
