/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.scheme

/** Default port for `http` and `ws` (SPEC §6.1, Table 6-1). */
private const val PORT_HTTP: Int = 80

/** Default port for `https` and `wss` (SPEC §6.1, Table 6-1). */
private const val PORT_HTTPS: Int = 443

/** Default port for `ftp` (SPEC §6.1, Table 6-1). */
private const val PORT_FTP: Int = 21

/**
 * The fixed, closed registry of WHATWG **special** schemes (SPEC §6.1, Table 6-1; [SCH-1]).
 *
 * Exactly these six schemes are special in both profiles; no other scheme — including
 * `mailto`, `gopher`, `data`, or any user-supplied scheme — is special ([SCH-1], [SCH-4]).
 * The set is not runtime-extensible, hence a closed enum rather than a registrable map.
 *
 * @property schemeName the canonical lowercase scheme name ([SCH-3]).
 * @property defaultPort the scheme's default port, or `null` when it has none. `file` is
 *   special yet portless ([SCH-2]).
 */
internal enum class SpecialScheme(
    val schemeName: String,
    val defaultPort: Int?,
) {
    FTP("ftp", PORT_FTP),
    FILE("file", null),
    HTTP("http", PORT_HTTP),
    HTTPS("https", PORT_HTTPS),
    WS("ws", PORT_HTTP),
    WSS("wss", PORT_HTTPS),
    ;

    internal companion object {
        /**
         * Returns the special scheme whose canonical name exactly equals [scheme], or `null`
         * when [scheme] is not special (SPEC §6.1.1, [SCH-5]; total function).
         *
         * The match is exact and case-sensitive: callers MUST pass an already-normalized
         * (lowercased per §6.3) scheme, so a scheme differing only in case still resolves
         * once normalized ([SCH-3]). Lookup is O(1) in registry size ([SCH-6]).
         *
         * @param scheme the candidate scheme; expected to be lowercase per [SCH-3].
         * @return the matching [SpecialScheme], or `null` when none matches.
         */
        fun fromName(scheme: String): SpecialScheme? {
            // No precondition on emptiness: an empty/odd scheme is simply "not special".
            return entries.firstOrNull { it.schemeName == scheme }
        }
    }
}
