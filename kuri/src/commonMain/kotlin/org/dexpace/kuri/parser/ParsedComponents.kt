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
 * - [path] never `null` ([MODEL-26]); the empty path is `ComponentPath.Segments(emptyList())`
 *   (see [ComponentPath] for the empty-vs-root convention).
 * - [query] `null` = no `?`; `""` = `?` present with empty content ([MODEL-30]).
 * - [fragment] `null` = no `#`; `""` = `#` present with empty content ([PARSE-8]).
 *
 * [username]/[password] are independently nullable ([MODEL-10], [MODEL-11]) so that an
 * absent userinfo/password stays distinct from a present-but-empty one all the way
 * through to serialization: `username == null` means no userinfo at all (no `@` in the
 * source), `username == ""` means an `@` was present with nothing before it; likewise
 * `password == null` means no `:` was present within the userinfo, `password == ""`
 * means a `:` was present with nothing after it. A non-null `password` therefore never
 * pairs with a `null` `username` ([MODEL-13]) — the `Uri`/`Url` facades map the fields to
 * their nullable/non-null public accessors respectively. Their encoding is
 * profile-specific: for `Uri` they are a raw, unmodified pass-through of whatever the
 * input contained — split off the userinfo span verbatim, with no decode and no encode,
 * the same treatment as every other `Uri`-profile component; for `Url` they are never
 * `null` (the WHATWG profile has no absent/present-empty userinfo distinction, [NORM-30])
 * and are already percent-encoded under the userinfo percent-encode set at parse time
 * (§5), matching `Url.username`/`Url.password`'s own documented contract.
 *
 * As a value record this type performs no inline validation — the §7/§8 modules that
 * populate it own the component invariants (mirroring [Host] and [ComponentPath]).
 *
 * @property scheme the parsed scheme (lowercased for storage in the `Url` profile),
 *   or `null` for a `Uri` relative reference.
 * @property username the userinfo user (raw for `Uri`, percent-encoded and never `null`
 *   for `Url`); `null` when no userinfo (no `@`) is present, `""` when an `@` was
 *   present with an empty user.
 * @property password the userinfo password (raw for `Uri`, percent-encoded and never
 *   `null` for `Url`); `null` when no `:` was present in the userinfo, `""` when a `:`
 *   was present with nothing after it.
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
    val username: String? = null,
    val password: String? = null,
    val host: Host? = null,
    val port: Int? = null,
    val path: ComponentPath = ComponentPath.Segments(emptyList()),
    val query: String? = null,
    val fragment: String? = null,
    val validationErrors: List<ValidationError> = emptyList(),
) {
    init {
        check(username != null || password == null) {
            "a non-null password requires a non-null username (MODEL-13)"
        }
    }
}
