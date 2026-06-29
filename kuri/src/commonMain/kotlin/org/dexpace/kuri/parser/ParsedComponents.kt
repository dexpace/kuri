/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.parser

import org.dexpace.kuri.error.ValidationError
import org.dexpace.kuri.host.Host

/**
 * The flat, immutable output of the §8 parsing engine: one component per field,
 * each with its own presence state (SPEC §3.2–§3.9, §8; WHATWG basic URL parser
 * result, RFC 3986 §3). This is the shared intermediate produced by the single
 * profile-parameterized engine ([INTRO-1]) before the public `Uri`/`Url` facades
 * are built; it carries no behaviour.
 *
 * Presence/absence is encoded with nullability exactly as §3.2 prescribes, so an
 * absent component and a present-but-empty component stay distinct ([MODEL-5]):
 *
 * - [scheme] `null` only for a `Uri`-profile relative reference ([MODEL-8]); a
 *   parsed `Url` always carries a non-null, non-empty scheme.
 * - [host] `null` = no authority (`//` was absent); [Host.Empty] = an authority is
 *   present but the host is the empty string ([MODEL-15]).
 * - [port] `null` = unspecified or (`Url` profile) elided default ([MODEL-23],
 *   [MODEL-25]); never a `-1`/`0` sentinel.
 * - [path] never `null` ([MODEL-26]); the empty path is `UrlPath.Segments(emptyList())`
 *   (see [UrlPath] for the empty-vs-root convention).
 * - [query] `null` = no `?`; `""` = `?` present with empty content ([MODEL-30]).
 * - [fragment] `null` = no `#`; `""` = `#` present with empty content ([PARSE-8]).
 *
 * [username]/[password] are the decoded userinfo halves and default to `""`
 * (absent userinfo) rather than `null`; the `Uri`/`Url` facades map them to their
 * nullable public accessors ([MODEL-13]). Held in decoded form and re-encoded under
 * the userinfo percent-encode set on serialization (§5).
 *
 * As a value record this type performs no inline validation — the §7/§8 modules that
 * populate it own the component invariants (mirroring [Host] and [UrlPath]).
 *
 * @property scheme the parsed scheme (lowercased for storage in the `Url` profile),
 *   or `null` for a `Uri` relative reference.
 * @property username the decoded userinfo user, `""` when no userinfo is present.
 * @property password the decoded userinfo password, `""` when absent or empty.
 * @property host the parsed host, `null` when there is no authority, [Host.Empty]
 *   for an empty authority.
 * @property port the explicit port, or `null` when unspecified / default-elided.
 * @property path the always-present path; defaults to the empty path.
 * @property query the raw query without the leading `?`, or `null` when no `?` was
 *   present.
 * @property fragment the raw fragment without the leading `#`, or `null` when no
 *   `#` was present.
 * @property validationErrors the ordered non-fatal validation errors recorded
 *   during parsing (§12), empty when the input was conformant.
 */
internal data class ParsedComponents(
    val scheme: String? = null,
    val username: String = "",
    val password: String = "",
    val host: Host? = null,
    val port: Int? = null,
    val path: UrlPath = UrlPath.Segments(emptyList()),
    val query: String? = null,
    val fragment: String? = null,
    val validationErrors: List<ValidationError> = emptyList(),
)
