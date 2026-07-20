/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri

import org.dexpace.kuri.error.ParseResult
import org.dexpace.kuri.error.UriSyntaxException
import org.dexpace.kuri.error.ValidationError
import org.dexpace.kuri.error.map
import org.dexpace.kuri.host.Host
import org.dexpace.kuri.parser.BuilderPath
import org.dexpace.kuri.parser.ComponentPath
import org.dexpace.kuri.parser.ParsedComponents
import org.dexpace.kuri.parser.StateOverride
import org.dexpace.kuri.parser.UrlParser
import org.dexpace.kuri.parser.decodedSegments
import org.dexpace.kuri.parser.fileExtensionOf
import org.dexpace.kuri.parser.fileNameOf
import org.dexpace.kuri.parser.isDirectoryPath
import org.dexpace.kuri.percent.PercentCodec
import org.dexpace.kuri.percent.PercentEncodeSet
import org.dexpace.kuri.percent.PercentEncodeSets
import org.dexpace.kuri.query.QueryParameters
import org.dexpace.kuri.query.QueryParametersBuilder
import org.dexpace.kuri.query.QueryState
import org.dexpace.kuri.query.applyParameterEdit
import org.dexpace.kuri.scheme.Scheme
import org.dexpace.kuri.serialize.UrlSerializer
import org.dexpace.kuri.serialize.serializeAuthority
import org.dexpace.kuri.serialize.serializeUrlPath
import org.dexpace.kuri.text.hasPercentHexPairAt
import kotlin.jvm.JvmName
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

/** Inclusive upper bound of a WHATWG URL port (`0..65535`); a larger value is a parse failure. */
private const val MAX_PORT: Int = 65535

/** The path-segment separator of a WHATWG URL path (SPEC §9). */
private const val SLASH: String = "/"

/**
 * The encode set applied to a decoded segment added via [Url.Builder.addPathSegment]: the WHATWG
 * path set plus `/` (the segment separator), `\` (equally a separator in a special-scheme path),
 * and `%` (the escape introducer), so an added segment always survives [Url.Builder.build] as a
 * single segment and [Url.pathSegments] decodes it back to exactly the value supplied (SPEC [PATH-6]).
 */
private val URL_PATH_SEGMENT_ENCODE_SET: PercentEncodeSet = PercentEncodeSets.PATH.including('/', '\\', '%')

/** The ASCII serialization of an opaque origin: the literal `"null"` (WHATWG "opaque origin"). */
private const val OPAQUE_ORIGIN: String = "null"

/** The `blob` scheme, whose origin is that of the URL parsed from its path. */
private const val BLOB_SCHEME: String = "blob"

/** The `file` scheme: special, yet its origin is opaque like a non-special scheme. */
private const val FILE_SCHEME: String = "file"

/** The inner schemes whose origin a `blob:` URL adopts; any other inner scheme is opaque. */
private val BLOB_INNER_ORIGIN_SCHEMES: Set<String> = setOf("http", "https", FILE_SCHEME)

/** The canonical escape for a literal `%`, substituted for a `%` that fails to introduce a triplet. */
private const val ESCAPED_PERCENT: String = "%25"

/** The net length growth from replacing one malformed `%` with the 3-character [ESCAPED_PERCENT]. */
private const val PERCENT_ESCAPE_GROWTH: Int = 2

/**
 * Rewrites every `%` in [input] that fails to introduce a valid `%XX` triplet to the literal
 * escape [ESCAPED_PERCENT], leaving every already-valid triplet untouched (RFC 3986 §2.1).
 *
 * None of the WHATWG percent-encode sets reserve `%` itself (see `PercentEncodeSets`), so a
 * WHATWG-canonical [Url.href] can carry a bare `%` or a triplet whose next two code units are not
 * both ASCII hex digits — a value the strict RFC 3986 [Uri] parser rejects outright. [Url.toUri]
 * runs [input] through this repair first so the profile bridge from [Url] to [Uri] is total.
 *
 * @param input the href-derived string to repair before handing it to the [Uri] parser.
 * @return [input] unchanged when it contains no malformed `%`, otherwise a copy with each
 *   malformed `%` replaced by [ESCAPED_PERCENT].
 */
private fun escapeMalformedPercent(input: String): String {
    if (input.indexOf('%') < 0) return input
    val repaired = StringBuilder(input.length)
    var malformedCount = 0
    for (index in input.indices) {
        val c = input[index]
        if (c == '%' && !hasPercentHexPairAt(input, index)) {
            repaired.append(ESCAPED_PERCENT)
            malformedCount++
        } else {
            repaired.append(c)
        }
    }
    check(repaired.length == input.length + malformedCount * PERCENT_ESCAPE_GROWTH) {
        "repair must grow the input by exactly $PERCENT_ESCAPE_GROWTH characters per malformed percent"
    }
    return repaired.toString()
}

/**
 * An immutable, fully-canonical WHATWG URL value (SPEC §3, §11; WHATWG URL Living Standard).
 *
 * A `Url` wraps the eager-canonical output of the §8 parsing engine, so every accessor is a pure
 * projection of the stored components and never re-parses or performs I/O. A successfully parsed
 * `Url` always carries a non-null, non-empty [scheme] and — for a special scheme — a [host].
 * Construct one with the [parse] factories or, for programmatic assembly,
 * with [Builder].
 *
 * Equality and hashing are defined over the canonical [href]; no DNS resolution or other
 * I/O occurs, so a `Url` is a safe `Map`/`Set` key.
 *
 * Because a `Url` is eager-canonical, [equals] over the already-normalized [href] is the
 * `Url`-profile analogue of [Uri.normalizedEquals]: there is no separate `normalized()` step to
 * apply. IPv6 zone identifiers are a `Uri`-profile feature only — the WHATWG host parser rejects
 * them — so `Url` exposes no `allowIpv6ZoneId` option.
 *
 * @sample org.dexpace.kuri.Url.Companion.parse
 */
@Suppress("TooManyFunctions") // Mirrors the WHATWG/okhttp accessor surface; each member is a thin projection.
public class Url internal constructor(
    internal val components: ParsedComponents,
) {
    /** The lower-cased scheme without its trailing `:`; never blank for a parsed `Url`. */
    @get:JvmName("scheme")
    public val scheme: String
        get() = requireNotNull(components.scheme) { "a parsed Url always carries a scheme" }

    /**
     * The percent-encoded userinfo username, or `""` when no credentials are present.
     *
     * The `Url` profile has no absent-vs-present-empty distinction ([NORM-30]): [components] never
     * actually stores a `null` username for a parsed `Url`, but the `?:` guard keeps this accessor
     * total against the shared, nullable [ParsedComponents] shape.
     */
    @get:JvmName("username")
    public val username: String
        get() = components.username ?: ""

    /**
     * The decoded userinfo username (percent-decoded [username]), `""` when absent or empty.
     *
     * Round-trips a value set via [Builder.username] or obtained from [parse] (both escape a
     * literal `%` first). A value set via the [withUsername] WHATWG setter does not get that
     * escape — matching real browsers' own `username` setter — so a literal `%` there followed by
     * two hex digits is misread as a percent-triplet on decode.
     */
    @get:JvmName("decodedUsername")
    public val decodedUsername: String
        get() = decodedUsernameValue

    /**
     * The percent-encoded userinfo password, or `""` when absent or empty.
     *
     * As [username], the `?:` guard keeps this accessor total; [components] never actually stores
     * a `null` password for a parsed `Url` ([NORM-30]).
     */
    @get:JvmName("password")
    public val password: String
        get() = components.password ?: ""

    /**
     * The decoded userinfo password (percent-decoded [password]), `""` when absent or empty.
     *
     * Round-trips a value set via [Builder.password] or obtained from [parse]; as [decodedUsername],
     * it does not round-trip a value set via the [withPassword] WHATWG setter when that value
     * contains a literal `%`.
     */
    @get:JvmName("decodedPassword")
    public val decodedPassword: String
        get() = decodedPasswordValue

    /** The structured host, or `null` when the URL has no authority. */
    @get:JvmName("host")
    public val host: Host?
        get() = components.host

    /**
     * The serialized host text (brackets included for IPv6), or `null` when there is no authority.
     *
     * [Host.Empty] serializes to `""` (an empty authority such as `file:///x`), which stays distinct
     * from the `null` returned when [host] is absent.
     */
    @get:JvmName("hostName")
    public val hostName: String?
        get() = components.host?.asText()

    /**
     * The explicit port, or `null` when the port was elided or equals the scheme default.
     *
     * A `null` here collapses two cases the WHATWG parser treats alike: no port was written, and a
     * port written but equal to the scheme's default (which canonicalization drops). Use
     * [effectivePort] for the port a consumer should actually connect to.
     */
    @get:JvmName("port")
    public val port: Int?
        get() = components.port

    /**
     * The port a consumer should connect to: the explicit [port], else the scheme default.
     *
     * Falls back to the scheme's registered default port when no [port] is stated, and to `null`
     * when the port is neither stated nor defaulted — a non-special scheme, or `file` (special, yet
     * portless), per SPEC §6.4 ([SCH-18]). Matches [Uri.effectivePort]'s sentinel-free contract
     * rather than the `java.net` `-1` convention [MODEL-23] bans.
     *
     * @return the stated or default port, or `null` when the scheme has no default.
     */
    @get:JvmName("effectivePort")
    public val effectivePort: Int?
        get() = components.port ?: Scheme.defaultPort(scheme)

    /** The decoded path segments in order (read-only); an opaque path yields its single decoded value. */
    @get:JvmName("pathSegments")
    public val pathSegments: List<String>
        get() = decodedPathSegments

    /** The canonical encoded path string (e.g. `/a/b`, or the opaque path verbatim). */
    @get:JvmName("encodedPath")
    public val encodedPath: String
        get() = encodedPathText

    /**
     * The raw encoded query without its leading `?`, or `null` when no `?` was present.
     *
     * `null` (no `?`) stays distinct from `""` (a `?` with nothing after it): `http://h/` has a
     * `null` query, `http://h/?` an empty one.
     */
    @get:JvmName("query")
    public val query: String?
        get() = components.query

    /** A decoded, immutable snapshot of the query's `name=value` pairs; never live. */
    @get:JvmName("queryParameters")
    public val queryParameters: QueryParameters
        get() = queryParameterSnapshot

    /**
     * The raw encoded fragment without its leading `#`, or `null` when no `#` was present.
     *
     * `null` (no `#`) stays distinct from `""` (a `#` with nothing after it): `http://h/` has a
     * `null` fragment, `http://h/#` an empty one.
     */
    @get:JvmName("fragment")
    public val fragment: String?
        get() = components.fragment

    /** Alias of [fragment]: the fragment is stored already-encoded, so both views coincide. */
    @get:JvmName("encodedFragment")
    public val encodedFragment: String?
        get() = components.fragment

    /**
     * The decoded fragment (percent-decoded [fragment]), or `null` when no `#` was present.
     *
     * Round-trips a value obtained from [parse]. A value set via the [withHash] WHATWG setter is
     * not pre-escaped for a literal `%` (matching real browsers' own `hash` setter), so such a
     * fragment does not round-trip through this accessor.
     */
    @get:JvmName("decodedFragment")
    public val decodedFragment: String?
        get() = decodedFragmentValue

    /** The `userinfo@host:port` authority, or `null` when the URL has no authority. */
    @get:JvmName("authority")
    public val authority: String?
        get() = if (components.host == null) null else serializeAuthority(components)

    /**
     * The ASCII serialization of this URL's WHATWG origin (§11.6).
     *
     * **Trap:** for an opaque origin this returns the literal string `"null"` — the four characters
     * `n`, `u`, `l`, `l`, not a `null` reference. An opaque origin arises for `file:` and every
     * non-special scheme; test [hasOpaqueOrigin] rather than comparing against `null`.
     *
     * Otherwise it is a **tuple origin** `scheme://host[:port]` (the port only when non-null; userinfo
     * is never included) for a special scheme other than `file`; and, for a `blob:` URL, the origin of
     * the URL parsed from its path when that inner scheme is `http`/`https`/`file`, otherwise opaque.
     * The origin is a derived projection, not stored, and is not guaranteed to round-trip.
     */
    @get:JvmName("origin")
    public val origin: String
        get() =
            when {
                scheme == BLOB_SCHEME -> blobOrigin()
                Scheme.isSpecial(scheme) && scheme != FILE_SCHEME -> tupleOrigin()
                else -> OPAQUE_ORIGIN
            }

    /**
     * True when this URL's [origin] is opaque — i.e. [origin] is the literal string `"null"`.
     *
     * The reliable opaque-origin test: [origin] is the four-character string `"null"` (not a `null`
     * reference) for a `file:` URL and every non-special scheme, so this predicate detects that case
     * without a caller comparing [origin] against that sentinel by hand. Its answer is derived from
     * [origin] directly — the single classification source — so the two can never drift apart.
     *
     * @return `true` iff [origin] serializes to the opaque `"null"`.
     */
    public fun hasOpaqueOrigin(): Boolean = origin == OPAQUE_ORIGIN

    /** Cached canonical serialization, computed once (permits caching an immutable value). */
    private val canonicalHref: String by lazy { UrlSerializer.serialize(components) }

    /** Path/query projections, each computed once; every value is immutable, mirroring [canonicalHref]. */
    private val decodedPathSegments: List<String> by lazy {
        decodedSegments(components.path) { PercentCodec.decode(it) }
    }

    // guardAgainstAuthority = false: this is the standalone `pathname` getter, never concatenated
    // onto a bare `scheme:` the way the full `href` is, so the NORM-18 `/.` anti-authority guard
    // (needed only to keep href re-parseable) does not belong in its output (SPEC §11.2).
    private val encodedPathText: String by lazy { serializeUrlPath(components, guardAgainstAuthority = false) }
    private val queryParameterSnapshot: QueryParameters by lazy { QueryParameters.parseOrEmpty(components.query) }

    /** Decoded fragment/userinfo projections, each computed once; immutable, mirroring [canonicalHref]. */
    private val decodedFragmentValue: String? by lazy { fragment?.let { PercentCodec.decode(it) } }
    private val decodedUsernameValue: String by lazy { PercentCodec.decode(username) }
    private val decodedPasswordValue: String by lazy { PercentCodec.decode(password) }

    /**
     * The canonical serialized URL; the basis of [toString], [equals], and [hashCode].
     *
     * Equal to [toString]. This is the `Url`-profile counterpart of [Uri.uriString]; because a `Url`
     * is eager-canonical, [href] is already normalized, so no separate normalized form is exposed.
     *
     * @see Uri.uriString
     */
    @get:JvmName("href")
    public val href: String
        get() = canonicalHref

    /**
     * The WHATWG non-fatal validation warnings recorded while parsing this URL (§12.3).
     *
     * WHATWG parsing is a lenient repair process: it accepts and silently corrects inputs a strict
     * reader would reject (a `\` read as `/`, a missing authority slash, an out-of-set code point),
     * and records each correction as a [ValidationError] without failing the parse. Each entry
     * carries its [ValidationError.kind], the offset [ValidationError.at] it occurred at, and
     * [ValidationError.isFailure]. The list is ordered by first occurrence and empty for a fully
     * conformant input; it is useful for linting or telemetry, never for control flow (a validation
     * error never downgrades a successful parse).
     *
     * @return a read-only, ordered copy of the validation warnings; empty for a clean parse.
     */
    public fun validationErrors(): List<ValidationError> = components.validationErrors.toList()

    /**
     * Returns a [Builder] pre-filled with this URL's components, for producing a modified copy.
     * `url.newBuilder().build()` reproduces an equal `Url`.
     *
     * @return a builder seeded with every stored component of this value.
     */
    public fun newBuilder(): Builder = Builder(this)

    /**
     * Resolves [reference] against this URL as its base (SPEC §9; WHATWG basic URL parser with base).
     *
     * `url.resolve(ref)` is exactly `Url.parse(ref, base = url)`; `resolve` is the canonical spelling
     * for the base-relative operation. Use [resolveOrNull]/[resolveOrThrow] for the punned/throwing
     * variants.
     *
     * @param reference the (possibly relative) reference to resolve.
     * @return [ParseResult.Ok] with the resolved [Url], or [ParseResult.Err] when resolution fails.
     */
    public fun resolve(reference: String): ParseResult<Url> = parse(reference, this)

    /**
     * Resolves [reference] against this URL, punning a resolution failure to `null` (SPEC §9).
     *
     * The `null`-returning counterpart of [resolve], equivalent to `resolve(reference).getOrNull()`.
     *
     * @param reference the (possibly relative) reference to resolve.
     * @return the resolved [Url], or `null` when [reference] does not resolve to a valid URL.
     */
    public fun resolveOrNull(reference: String): Url? = resolve(reference).getOrNull()

    /**
     * Resolves [reference] against this URL, throwing when resolution fails (SPEC §9).
     *
     * The throwing counterpart of [resolve], equivalent to `resolve(reference).getOrThrow()`; the
     * thrown [UriSyntaxException.error] is the same structured error [resolve] would report.
     *
     * @param reference the (possibly relative) reference to resolve.
     * @return the resolved [Url].
     * @throws UriSyntaxException when [reference] does not resolve to a valid URL.
     */
    public fun resolveOrThrow(reference: String): Url = resolve(reference).getOrThrow()

    /**
     * Converts this WHATWG URL to a generic RFC 3986 [Uri] (SPEC §11.5; profile bridge).
     *
     * This is total and does not fail: a canonical WHATWG [href] is always a valid RFC 3986 URI
     * once one WHATWG/RFC 3986 mismatch is reconciled first. The WHATWG percent-encode sets never
     * reserve `%` itself, so [href] can carry a bare `%` or a triplet whose next two code units
     * are not both hex digits — a byte sequence the strict RFC 3986 `Uri` engine would otherwise
     * reject as a malformed percent-encoding. [escapeMalformedPercent] rewrites exactly that case
     * to `%25` before the re-parse, so every other byte of [href] — and thus the resulting [Uri]'s
     * canonical form — is unchanged. The result carries a non-null `scheme`.
     *
     * @return the equivalent generic [Uri].
     */
    public fun toUri(): Uri = Uri.parse(escapeMalformedPercent(href)).getOrThrow()

    /**
     * Computes a relative reference that [resolve]s back to [target], or `null` when the two share no
     * origin — the inverse of [resolve] (SPEC §9; RFC 3986 §5.3).
     *
     * Relativization only succeeds within a single origin: when [target] has the same scheme and
     * authority as this URL, the result is the shortest relative-reference string (path, query, and
     * fragment) for which `this.resolve(result)` yields [target]. When they differ in scheme or
     * authority — or [target] is otherwise not reachable as a relative suffix of this URL's path —
     * there is no relative form and the result is `null` (mirroring the Rust `url` crate's
     * `make_relative`). Builds the candidate relative reference over the profile-bridged [toUri] values,
     * so RFC 3986 dot-segment semantics shape it, then verifies it under this profile's own (WHATWG)
     * [resolve] — the only resolution authoritative for a `Url` — returning `null` rather than a
     * reference that would not reproduce [target].
     *
     * @param target the absolute URL to express relative to this one.
     * @return the relative-reference string that resolves to [target], or `null` when none does.
     */
    public fun relativize(target: Url): String? {
        // Build the candidate relative reference over the URI profile (shared RFC 3986 dot-segment
        // semantics) but skip its URI-level round-trip check: only the WHATWG resolve below is
        // authoritative for a Url, so a single verification here removes a redundant resolve.
        val reference = toUri().relativeReference(target.toUri())?.toString() ?: return null
        return if (resolveOrNull(reference) == target) reference else null
    }

    /**
     * True when this URL's [scheme] is one of the six WHATWG special schemes (SPEC §6.1.1).
     *
     * The special schemes are `http`, `https`, `ws`, `wss`, `ftp`, and `file`; special-ness governs
     * host parsing, the `\`-as-`/` rule, and default-port handling. `file` is special yet portless, so
     * [effectivePort] can still be `null` for a special URL.
     *
     * @return `true` iff [scheme] is a special scheme.
     */
    public fun isSpecial(): Boolean = Scheme.isSpecial(scheme)

    /**
     * The last non-empty decoded path segment, or `""` when the path has none (SPEC §3.3).
     *
     * The "file name" of the path: `http://h/a/b.txt` yields `b.txt`, and a directory-style path
     * ending in `/` (`http://h/a/`) skips the trailing empty segment to yield `a`. Empty for a path
     * that is empty, `/`, or made only of empty segments. An opaque path (a non-special scheme such
     * as `mailto:` or `foo:bar/baz`) has no hierarchical file name, so it too returns `""`.
     *
     * Because the segment is percent-decoded, a source segment holding an encoded `/` (`%2F`) yields
     * a name containing a literal `/` — e.g. `http://h/docs/a%2Fb.txt` returns `"a/b.txt"` — so a
     * caller must not use the result directly as a filesystem name without its own sanitization.
     *
     * @return the last non-empty decoded segment, or `""` when there is none or the path is opaque.
     */
    public fun fileName(): String = if (components.path is ComponentPath.Opaque) "" else fileNameOf(pathSegments)

    /**
     * The extension of [fileName]: the text after its last `.`, or `""` when it has none (SPEC §3.3).
     *
     * Derived purely from [fileName]: `photo.jpeg` yields `jpeg` and `archive.tar.gz` yields `gz`. A
     * name with no interior dot has no extension — a leading dot marks a dotfile (`.bashrc` -> `""`)
     * and a trailing dot leaves nothing after it (`file.` -> `""`).
     *
     * @return the decoded extension without its leading `.`, or `""` when [fileName] has none.
     */
    public fun fileExtension(): String = fileExtensionOf(fileName())

    /**
     * Returns a copy of this URL with its explicit [port] set (or elided when `null`).
     *
     * A convenience for `newBuilder().port(port).build()`. Only the port changes, so a value obtained
     * from [parse] always rebuilds; a port outside `0..65535` is a programmer error, not a parse
     * failure. When a port cannot attach — a URL with no host, an empty host, or the `file` scheme
     * (which never carries a port) — the receiver is returned unchanged, matching WHATWG's port setter.
     *
     * @param port a port in `0..65535`, or `null` to elide it (so [effectivePort] falls back to the default).
     * @return a new [Url] identical to this one but for its port, or this URL unchanged when a port
     *   cannot attach.
     * @throws IllegalArgumentException when [port] is outside `0..65535` and a port can attach.
     */
    public fun withPort(port: Int?): Url {
        if (!canHaveCredentialsOrPort()) return this
        return newBuilder().port(port).build()
    }

    /**
     * The WHATWG `port` setter (URL §5): returns a copy whose port is parsed from the leading
     * digits of [value] (trailing non-digits are ignored, per the port state), or with the port
     * removed when [value] is empty. A no-op when the URL cannot have a port (no host, empty host,
     * or `file` scheme). Never throws on invalid input — an unparseable value is a no-op.
     *
     * @param value the new port as text; `""` removes the port.
     * @return the updated [Url], or `this` when the setter is a WHATWG no-op.
     */
    public fun withPort(value: String): Url {
        // No pre-check on value[0]: the WHATWG pre-processing strips tab/newline from `value`
        // before the port state ever sees it (e.g. "\t8080" is a valid port once stripped), and an
        // invalid leading character is itself a no-op the port state machine already resolves.
        return when {
            !canHaveCredentialsOrPort() -> this
            value.isEmpty() -> withComponents(components.copy(port = null))
            else -> applyOverride(value, StateOverride.PORT)
        }
    }

    /**
     * The WHATWG `pathname` setter (URL §5): returns a copy whose path is parsed from [value]
     * (the existing path is discarded first), or `this` when the URL has an opaque path. Never
     * throws on invalid input — an unparseable value is a no-op.
     *
     * @param value the new path text.
     * @return the updated [Url], or `this` when the setter is a WHATWG no-op.
     */
    public fun withPathname(value: String): Url {
        if (components.path is ComponentPath.Opaque) return this
        return applyOverride(value, StateOverride.PATHNAME)
    }

    /**
     * The WHATWG `search` setter (URL §5): returns a copy whose query is [value] with a single
     * leading `?` stripped and percent-encoded with the (special-)query set, or with the query
     * removed when [value] is empty. Never throws on invalid input — an unparseable value is a no-op.
     *
     * @param value the new query text, with or without a leading `?`; `""` removes the query.
     * @return the updated [Url], or `this` when the setter is a WHATWG no-op.
     */
    public fun withSearch(value: String): Url {
        if (value.isEmpty()) return withComponents(components.copy(query = null))
        val stripped = if (value.startsWith('?')) value.substring(1) else value
        return applyOverride(stripped, StateOverride.QUERY)
    }

    /**
     * The WHATWG `hash` setter (URL §5): returns a copy whose fragment is [value] with a single
     * leading `#` stripped and percent-encoded with the fragment set, or with the fragment
     * removed when [value] is empty. Never throws.
     *
     * Matches real browsers' `hash` setter in not escaping a literal `%` first, so [value] does
     * not necessarily round-trip through [decodedFragment] — see its KDoc.
     *
     * @param value the new fragment text, with or without a leading `#`; `""` removes the fragment.
     * @return the updated [Url].
     */
    public fun withHash(value: String): Url {
        if (value.isEmpty()) return withComponents(components.copy(fragment = null))
        val withoutHash = if (value.startsWith('#')) value.substring(1) else value
        // WHATWG's hash setter basic-URL-parses this input with fragment state as the override,
        // which unconditionally strips every ASCII tab/LF/CR first (SPEC §8.1); withHash bypasses
        // the shared engine (there is no FRAGMENT StateOverride), so it must replicate that step
        // itself rather than percent-encode a literal tab/newline into the fragment.
        val stripped = withoutHash.filterNot { it == '\t' || it == '\n' || it == '\r' }
        val encoded = PercentCodec.encode(stripped, PercentEncodeSets.FRAGMENT)
        return withComponents(components.copy(fragment = encoded))
    }

    /**
     * Returns a copy of this URL with its [fragment] set (or removed when `null`).
     *
     * A convenience for `newBuilder().fragment(fragment).build()`. A non-`null` `""` keeps a present-
     * but-empty fragment (a trailing `#`); `null` drops the `#` entirely. Only the fragment changes,
     * so a value obtained from [parse] always rebuilds and this never throws.
     *
     * @param fragment the encoded fragment without its leading `#`, or `null` to remove it.
     * @return a new [Url] identical to this one but for its fragment.
     */
    public fun withFragment(fragment: String?): Url = newBuilder().fragment(fragment).build()

    /**
     * Returns a copy of this URL with its fragment removed (the `#` dropped entirely).
     *
     * Shorthand for `withFragment(null)`; a URL that already had no fragment is unchanged in value.
     *
     * @return a new [Url] with no fragment.
     */
    public fun withoutFragment(): Url = withFragment(null)

    /**
     * Returns a copy of this URL with its userinfo (username and password), query, and fragment
     * removed, leaving the [scheme], [host], [port], and [encodedPath] intact (SPEC [CONF-120]).
     *
     * A convenience for logging or telemetry: it strips exactly the components WHATWG/RFC 3986
     * treat as sensitive or context-dependent (credentials, the query string, and the fragment)
     * while every other component — including a credential-less authority — is preserved
     * verbatim. For an opaque-path URL such as `mailto:user@example.com`, the `user@` text is part
     * of the opaque path rather than real userinfo (there is no authority at all) and so is left
     * untouched; only the actual query and fragment are stripped. A URL that already carries none
     * of the three is returned equal in value (though [newBuilder] always rebuilds, so the result
     * is not necessarily the same reference).
     *
     * @return a new `Url` with no username, password, query, or fragment.
     */
    public fun redact(): Url =
        newBuilder()
            .username("")
            .password("")
            .query(null)
            .fragment(null)
            .build()

    /**
     * Reports whether this URL's path denotes a directory — its [encodedPath] ends in `/` (SPEC
     * [PATH-3], [CONF-85]).
     *
     * True for a trailing-slash path such as `/a/` and for the root path `/` (a single empty
     * segment) — which every special-scheme URL with an otherwise-empty path canonicalizes to, so
     * e.g. `https://h` and `https://h/` both report `true`. `false` for a path with content after
     * its last segment (`/a`). [hasTrailingSlash] is an exact alias, for a call site that prefers
     * the WHATWG "trailing slash" phrasing over the filesystem-style "directory" term.
     *
     * @return `true` iff [encodedPath] ends in `/`.
     */
    public fun isDirectory(): Boolean = components.path.isDirectoryPath()

    /**
     * Alias of [isDirectory] (SPEC [PATH-3], [CONF-85]); both accessors report the same condition.
     *
     * @return `true` iff [encodedPath] ends in `/`.
     */
    public fun hasTrailingSlash(): Boolean = isDirectory()

    /**
     * The WHATWG `protocol` setter (URL §5): returns a copy with [value]'s scheme, or this URL
     * unchanged when the change is not permitted (special↔non-special, or an invalid `file`
     * transition). Never throws on invalid input — an invalid scheme is a no-op.
     *
     * @param value the new scheme, with or without a trailing `:`.
     * @return the updated [Url], or `this` when the setter is a WHATWG no-op.
     */
    public fun withProtocol(value: String): Url {
        // No pre-validation here: WHATWG appends `:` to the raw value and basic-URL-parses it with
        // scheme-start state as the override, so the state machine itself (which strips tab/newline
        // first) decides validity and reports an invalid scheme as a no-op (SCHEME_START/SCHEME
        // failing under an override; see UrlParserStates). Pre-checking the unstripped value here
        // previously rejected e.g. "h\r\ntt\tps" even though it strips to the valid scheme "https".
        return applyOverride("$value:", StateOverride.PROTOCOL)
    }

    /**
     * The WHATWG `username` setter (URL §5): returns a copy whose userinfo user is [value]
     * percent-encoded with the userinfo set, or `this` when the URL cannot have credentials
     * (no host, empty host, or a `file` scheme). Never throws.
     *
     * Matches real browsers' `username` setter in not escaping a literal `%` first, so [value]
     * does not necessarily round-trip through [decodedUsername] — see its KDoc.
     *
     * @param value the decoded username to set.
     * @return the updated [Url], or `this` when the setter is a WHATWG no-op.
     */
    public fun withUsername(value: String): Url {
        if (!canHaveCredentialsOrPort()) return this
        val encoded = PercentCodec.encode(value, PercentEncodeSets.USERINFO)
        return withComponents(components.copy(username = encoded))
    }

    /**
     * The WHATWG `password` setter (URL §5): as [withUsername], for the password half — including
     * not round-tripping a literal `%` through [decodedPassword].
     *
     * @param value the decoded password to set.
     * @return the updated [Url], or `this` when the setter is a WHATWG no-op.
     */
    public fun withPassword(value: String): Url {
        if (!canHaveCredentialsOrPort()) return this
        val encoded = PercentCodec.encode(value, PercentEncodeSets.USERINFO)
        return withComponents(components.copy(password = encoded))
    }

    /**
     * The WHATWG `host` setter (URL §5): returns a copy with host and (optional) port parsed
     * from [value], or `this` when the URL has an opaque path or [value] is not a valid host.
     * Never throws on invalid input — an invalid host is a no-op.
     *
     * @param value the new host text, optionally followed by `:` and a port.
     * @return the updated [Url], or `this` when the setter is a WHATWG no-op.
     */
    public fun withHost(value: String): Url {
        if (components.path is ComponentPath.Opaque) return this
        return applyOverride(value, StateOverride.HOST)
    }

    /**
     * The WHATWG `hostname` setter (URL §5): as [withHost] but a `:` and anything after it are
     * ignored, so the existing port is preserved. Never throws on invalid input — an invalid host
     * is a no-op.
     *
     * @param value the new host text; any `:` and trailing text are ignored.
     * @return the updated [Url], or `this` when the setter is a WHATWG no-op.
     */
    public fun withHostname(value: String): Url {
        if (components.path is ComponentPath.Opaque) return this
        return applyOverride(value, StateOverride.HOSTNAME)
    }

    /** WHATWG "cannot have a username/password/port": no host, empty host, or `file` scheme. */
    private fun canHaveCredentialsOrPort(): Boolean {
        val currentHost = components.host
        return currentHost != null && currentHost != Host.Empty && !scheme.equals(FILE_SCHEME, ignoreCase = true)
    }

    /** A copy carrying [next], or `this` when [next] already equals the current components (a no-op). */
    private fun withComponents(next: ParsedComponents): Url {
        // The one invariant every setter funnels through here must preserve: a Url always carries a
        // scheme (the `scheme` getter and the Builder both rely on it), so no setter may strip it.
        check(next.scheme != null) { "a setter must never strip the scheme" }
        return if (next == components) this else Url(next)
    }

    /** Runs a setter [override] over [value], returning `this` on any error or WHATWG no-op. */
    private fun applyOverride(
        value: String,
        override: StateOverride,
    ): Url =
        when (val result = UrlParser.parseWithOverride(value, components, override)) {
            is ParseResult.Ok -> withComponents(result.value)
            is ParseResult.Err -> this
        }

    /** The canonical [href]; a parsed `Url` round-trips through `toString` then [parse]. */
    override fun toString(): String = href

    /** Canonical-href equality; pure and I/O-free, so two equal URLs share a [hashCode]. */
    override fun equals(other: Any?): Boolean = other is Url && other.href == href

    /** The hash of the canonical [href], consistent with [equals]. */
    override fun hashCode(): Int = href.hashCode()

    /** The tuple origin `scheme://host[:port]`: host elides to `""`, port and userinfo are omitted. */
    private fun tupleOrigin(): String = "$scheme://${hostName.orEmpty()}${port?.let { ":$it" } ?: ""}"

    /** A `blob:` URL's origin: that of the URL parsed from its path when unwrappable, else opaque. */
    private fun blobOrigin(): String {
        val inner = parse(encodedPath).getOrNull() ?: return OPAQUE_ORIGIN
        return if (inner.scheme in BLOB_INNER_ORIGIN_SCHEMES) inner.origin else OPAQUE_ORIGIN
    }

    /** Parse factories for [Url] (SPEC §7.5, §12.1); each returns a value rather than throwing. */
    public companion object {
        /**
         * Parses [input] as a WHATWG URL, resolving a relative reference against [base] when
         * supplied (SPEC §9).
         *
         * The WHATWG URL parser takes no configuration, so this factory accepts no [ParseOptions];
         * an IPv6 zone identifier is always rejected under the `Url` profile.
         *
         * @param input the (possibly relative) URL text to parse.
         * @param base the base URL for relative resolution, or `null` for an absolute parse.
         * @return [ParseResult.Ok] with the canonical [Url], or [ParseResult.Err] on failure.
         */
        @JvmStatic
        @JvmOverloads
        public fun parse(
            input: String,
            base: Url? = null,
        ): ParseResult<Url> = UrlParser.parse(input, base?.components).map { Url(it) }

        /**
         * Parses [input] (resolving against [base] when supplied), punning a failure to `null`.
         *
         * @param input the (possibly relative) URL text to parse.
         * @param base the base URL for relative resolution, or `null` for an absolute parse.
         * @return the parsed (or resolved) [Url], or `null` when [input] does not parse (or resolve)
         *   to a valid URL.
         */
        @JvmStatic
        @JvmOverloads
        public fun parseOrNull(
            input: String,
            base: Url? = null,
        ): Url? = parse(input, base).getOrNull()

        /**
         * Parses [input] (resolving against [base] when supplied), throwing when it is not a valid
         * URL.
         *
         * The throwing counterpart of [parse]/[parseOrNull], for a call site that prefers an
         * exception to a [ParseResult] branch; the thrown [UriSyntaxException.error] is the same
         * structured error [parse] would report.
         *
         * @param input the (possibly relative) URL text to parse.
         * @param base the base URL for relative resolution, or `null` for an absolute parse.
         * @return the parsed (or resolved) [Url].
         * @throws UriSyntaxException when [input] does not parse (or resolve) to a valid URL.
         */
        @JvmStatic
        @JvmOverloads
        public fun parseOrThrow(
            input: String,
            base: Url? = null,
        ): Url = parse(input, base).getOrThrow()

        /**
         * Reports whether [input] parses as a URL (resolving against [base] when supplied), without
         * allocating the value.
         *
         * @param input the (possibly relative) URL text to test.
         * @param base the base URL for relative resolution, or `null` to test an absolute parse.
         * @return `true` iff [parse] would succeed for [input] against [base].
         */
        @JvmStatic
        @JvmOverloads
        public fun canParse(
            input: String,
            base: Url? = null,
        ): Boolean = parse(input, base).isOk()
    }

    /**
     * A mutable, Java-constructible (`new Url.Builder()`) assembler for a [Url].
     *
     * Setters are fluent and accumulate component state; [build] recomposes that state into a URL
     * string and re-parses it through the engine, so the produced [Url] is always canonical.
     * Use [Url.newBuilder] for a pre-filled builder.
     */
    @Suppress("TooManyFunctions") // Paired decoded/encoded setters per component; each is a one-liner.
    public class Builder {
        private var scheme: String = ""
        private var encodedUsername: String = ""
        private var encodedPassword: String = ""
        private var host: String? = null
        private var port: Int? = null

        /**
         * The structured path — decoded segments plus their rooting — held as one immutable [BuilderPath]
         * that every path edit replaces wholesale. A segment-built path defers its rooting to [build]; the
         * "would re-root" condition is recomputed there by [BuilderPath.wellFormed], not tracked across edits.
         */
        private var path: BuilderPath = BuilderPath()

        /** The query: a verbatim [QueryState.Raw] string, or a structured [QueryState.Params] edit. */
        private var queryState: QueryState = QueryState.Raw(null)
        private var encodedFragment: String? = null

        /** Creates an empty builder; a [scheme] and (for special schemes) a [host] must be set before [build]. */
        public constructor()

        /** Creates a builder pre-filled from [source] so `source.newBuilder().build()` reproduces it. */
        internal constructor(source: Url) {
            scheme = source.scheme
            encodedUsername = source.username
            encodedPassword = source.password
            host = source.hostName
            port = source.port
            // Pre-fill from the guarded full-URL path serialization, not the `encodedPath` getter:
            // recompose concatenates the path directly onto `scheme:` with no authority, so an
            // authority-less `//`-leading path must keep the NORM-18 `/.` guard or the rebuilt string
            // re-parses with a spurious authority (the getter drops the guard per SPEC §11.2, which is
            // correct for a standalone pathname but wrong as recompose input).
            path = BuilderPath.verbatim(serializeUrlPath(source.components))
            queryState = QueryState.Raw(source.query)
            encodedFragment = source.fragment
        }

        /**
         * Sets the scheme (without its `:`); lower-casing happens at [build].
         *
         * @param scheme a syntactically valid scheme.
         * @throws IllegalArgumentException when [scheme] is not a valid scheme (a programmer error).
         */
        public fun scheme(scheme: String): Builder {
            require(Scheme.isValidScheme(scheme)) { "invalid scheme: $scheme" }
            this.scheme = scheme
            return this
        }

        /**
         * Sets the userinfo username, percent-encoding it under the userinfo set.
         *
         * A literal `%` in [username] is escaped to `%25` first, since the userinfo set does not
         * reserve `%` itself — see [PercentCodec.escapeLiteralPercent] for the rationale. This mirrors
         * [Uri.Builder.username], which applies the same escape.
         *
         * @param username the decoded username; `""` clears the username.
         */
        public fun username(username: String): Builder {
            val escaped = PercentCodec.escapeLiteralPercent(username)
            encodedUsername = PercentCodec.encode(escaped, PercentEncodeSets.USERINFO)
            return this
        }

        /**
         * Sets the userinfo password, percent-encoding it under the userinfo set.
         *
         * As [username], a literal `%` in [password] is escaped to `%25` first; see
         * [PercentCodec.escapeLiteralPercent] for the rationale.
         *
         * @param password the decoded password; `""` clears the password.
         */
        public fun password(password: String): Builder {
            val escaped = PercentCodec.escapeLiteralPercent(password)
            encodedPassword = PercentCodec.encode(escaped, PercentEncodeSets.USERINFO)
            return this
        }

        /**
         * Sets the host text; it is validated and canonicalized by the host pipeline at [build] (§7).
         *
         * @param host the host text (e.g. a domain, IPv4, or `[ipv6]`).
         */
        public fun host(host: String): Builder {
            this.host = host
            return this
        }

        /**
         * Sets the host from a structured [Host], rendering it to its canonical authority text.
         *
         * A convenience over [host] (`String`): the [Host] is serialized with [Host.asText] and then
         * re-validated and re-canonicalized by the host pipeline at [build], so a host taken from one
         * URL transfers to another without a manual round-trip through text.
         *
         * @param host the structured host to set.
         * @return this builder, for chaining.
         */
        public fun host(host: Host): Builder = host(host.asText())

        /**
         * Sets or clears the explicit port.
         *
         * @param port a port in `0..65535`, or `null` to elide it.
         * @throws IllegalArgumentException when [port] is out of range (a programmer error).
         */
        public fun port(port: Int?): Builder {
            require(port == null || port in 0..MAX_PORT) { "port out of range: $port" }
            this.port = port
            return this
        }

        /**
         * Replaces the entire encoded path verbatim.
         *
         * @param encodedPath the already-encoded path (e.g. `/a/b`); validated at [build].
         */
        public fun encodedPath(encodedPath: String): Builder {
            path = BuilderPath.verbatim(encodedPath)
            return this
        }

        /**
         * Appends one decoded path segment, percent-encoding it (including any `/`, `\`, or `%`) so
         * it always stays a single segment and [Url.pathSegments] decodes it back to exactly
         * [segment] (SPEC [PATH-6]; OkHttp `HttpUrl.Builder`).
         *
         * Segments join at the path's segment boundary: a path that is empty or already ends in `/`
         * has an open final slot the segment fills directly, and any other path first gains a
         * separating `/` — so `newBuilder()` of `http://h/` plus `"x"` builds `http://h/x`, matching
         * OkHttp's replace-trailing-empty rule (SPEC [PATH-3]). Pushing `""` leaves the final slot
         * open: on a path that already has content it is a real trailing empty segment (`/a/`, so
         * [Url.pathSegments] is `["a", ""]`); on a still-empty path it leaves the path empty, so a
         * lone `addPathSegment("")` serializes as the root `/` under a [host] and contributes nothing
         * without one. An interior empty segment (`/a//b`) is expressible only through [encodedPath].
         * A path assembled purely from segments stays rootless until [build], which prepends the root
         * `/` iff the value has a [host] — so an opaque-path `mailto:`/`urn:` URL builds segment-wise,
         * independent of setter order.
         *
         * Note: the dot segments `.`, `..`, and their percent-encoded forms remain subject to WHATWG
         * path shortening at [build] (OkHttp-consistent; SPEC [PATH-11]), so they are not preserved
         * as exactly one appended segment.
         *
         * @param segment the decoded segment to append.
         * @return this builder, for chaining.
         */
        public fun addPathSegment(segment: String): Builder {
            path = path.pushSegment(PercentCodec.encode(segment, URL_PATH_SEGMENT_ENCODE_SET))
            return this
        }

        /**
         * Appends one already-encoded path segment verbatim (WHATWG path segment).
         *
         * The caller owns the encoding: percent-escapes are preserved (WHATWG
         * validation-error-and-continue) and validated at [build]. Because a segment cannot contain a
         * component delimiter, [segment] must not carry a raw `/`, `\`, `?`, or `#` — those would
         * silently re-split the built value into an extra segment, a query, or a fragment (SPEC
         * [MODEL-39]); use [addPathSegment] to carry them as data. Separator and rooting rules are
         * those of [addPathSegment]: the segment joins at the path's segment boundary, and a path
         * built purely from segments is rooted at [build] iff the value has a [host].
         *
         * Note: the dot segments `.`, `..`, and their percent-encoded forms remain subject to WHATWG
         * path shortening at [build] (SPEC [PATH-11]), so they are not preserved as exactly one
         * appended segment.
         *
         * @param segment the encoded segment to append; kept exactly as supplied.
         * @return this builder, for chaining.
         * @throws IllegalArgumentException when [segment] contains a raw `/`, `\`, `?`, or `#`.
         */
        public fun addEncodedPathSegment(segment: String): Builder {
            path = path.pushSegment(segment)
            return this
        }

        /**
         * Appends each `/`-separated piece of [path] as one decoded segment (OkHttp
         * `HttpUrl.Builder.addPathSegments`).
         *
         * Every `/` delimits a segment, so each piece is percent-encoded (a `/` only ever separates
         * segments and never appears inside one; only `/` separates here — a `\` is treated as data and
         * encoded, unlike the parser's special-scheme `\`-as-`/` handling) and every empty piece is
         * preserved as a genuine empty segment: an interior or doubled `/` yields one
         * (`"a//b"` -> `["a", "", "b"]`) and a trailing `/` yields a trailing empty
         * (`"a/b/"` -> `["a", "b", ""]`). Appending onto a directory-style path (one ending in `/`)
         * fills that trailing slot rather than doubling it, so `newBuilder()` of `https://h/x/` plus
         * `"a"` builds `https://h/x/a`. When a path is built from scratch a leading `/` is ignored, since
         * a rootless path cannot begin with an empty segment; the root `/` is still added at [build] iff
         * the value has a [host].
         *
         * @param path the `/`-separated decoded path to append.
         * @return this builder, for chaining.
         */
        public fun addPathSegments(path: String): Builder {
            this.path = this.path.addSegments(path) { PercentCodec.encode(it, URL_PATH_SEGMENT_ENCODE_SET) }
            return this
        }

        /**
         * Replaces the decoded path segment at [index] with [segment], percent-encoding it.
         *
         * Segment positions are those of [Url.pathSegments]: for `http://h/a/b/c`, index `0` is `a`.
         * [segment] is encoded like [addPathSegment] (any `/`, `\`, or `%` escaped) so it stays a
         * single segment, and the path keeps its existing rooted/rootless shape: an edit that would
         * empty a rootless path's first segment — re-rooting it into an unrepresentable shape — is
         * rejected at [build] ([build] throws, [buildOrNull] returns `null`) rather than silently
         * changing the path.
         *
         * @param index the zero-based segment position to replace.
         * @param segment the decoded replacement segment.
         * @return this builder, for chaining.
         * @throws IndexOutOfBoundsException when [index] is negative or `>=` the current segment count.
         */
        public fun setPathSegment(
            index: Int,
            segment: String,
        ): Builder {
            path = path.setSegment(index, PercentCodec.encode(segment, URL_PATH_SEGMENT_ENCODE_SET))
            return this
        }

        /**
         * Removes the path segment at [index], preserving the order of the rest.
         *
         * Segment positions are those of [Url.pathSegments]. Removing the only segment of a rooted path
         * leaves the root `/`; the path keeps its existing rooted/rootless shape otherwise. As with
         * [setPathSegment], an edit that would empty a rootless path's first segment (re-rooting it) is
         * rejected at [build] ([build] throws, [buildOrNull] returns `null`).
         *
         * @param index the zero-based segment position to remove.
         * @return this builder, for chaining.
         * @throws IndexOutOfBoundsException when [index] is negative or `>=` the current segment count.
         */
        public fun removePathSegment(index: Int): Builder {
            path = path.removeSegment(index)
            return this
        }

        /**
         * Sets or clears the raw encoded query (without its leading `?`).
         *
         * @param query the encoded query, or `null` to drop the `?` entirely.
         */
        public fun query(query: String?): Builder {
            queryState = QueryState.Raw(query)
            return this
        }

        /**
         * Replace-first/remove-rest sets the query parameter [name] to [value].
         *
         * The first pair named [name] takes [value] in place, every later pair named [name] is
         * removed, and a pair is appended when none had the name; use [addQueryParameter] to keep
         * duplicates instead. The re-serialized query is written back through [query].
         *
         * @param name the decoded parameter name.
         * @param value the decoded parameter value, or `null` for a name with no `=`.
         * @return this builder, for chaining.
         */
        public fun setQueryParameter(
            name: String,
            value: String?,
        ): Builder = editQueryParameters { it.set(name, value) }

        /**
         * Appends the query parameter [name] = [value] after any existing pairs, preserving duplicates.
         *
         * Unlike [setQueryParameter], this never replaces or removes a same-named pair, so it can build
         * a multi-valued query (`?a=1&a=2`). The re-serialized query is written back through [query].
         *
         * @param name the decoded parameter name.
         * @param value the decoded parameter value, or `null` for a name with no `=`.
         * @return this builder, for chaining.
         */
        public fun addQueryParameter(
            name: String,
            value: String?,
        ): Builder = editQueryParameters { it.add(name, value) }

        /**
         * Removes every query parameter named [name], keeping the order of the rest (SPEC §10.3.2).
         *
         * A no-op when no pair carries [name]. When the last pair is removed the query becomes absent
         * (the `?` is dropped), matching a builder that never set a query; call `query("")` to keep a
         * present-but-empty query instead.
         *
         * @param name the decoded parameter name to remove, matched case-sensitively.
         * @return this builder, for chaining.
         */
        public fun removeAllQueryParameters(name: String): Builder = editQueryParameters { it.removeAll(name) }

        /**
         * Applies [edit] to the query parameters and stores the collapsed [QueryState], parsing a
         * verbatim query to a builder once (the `Url` profile drops the `?` when the last pair is
         * removed — `emptyBecomesNull = true`).
         */
        private fun editQueryParameters(edit: (QueryParametersBuilder) -> Unit): Builder {
            queryState = queryState.applyParameterEdit(emptyBecomesNull = true, edit)
            return this
        }

        /**
         * Sets or clears the raw encoded fragment (without its leading `#`).
         *
         * @param fragment the encoded fragment, or `null` to drop the `#` entirely.
         */
        public fun fragment(fragment: String?): Builder {
            encodedFragment = fragment
            return this
        }

        /**
         * Recomposes the accumulated components and re-parses them into a canonical [Url].
         *
         * @return the canonical [Url] for the assembled components.
         * @throws IllegalArgumentException when the components cannot be represented as a URL: a
         *   special (non-`file`) scheme with no [host] — whose first path segment the parser would
         *   otherwise read as the host — or an authority paired with a verbatim rootless [encodedPath].
         * @throws UriSyntaxException when the components do not form a valid URL — a builder
         *   misuse is a programmer error rather than a recoverable parse failure.
         */
        public fun build(): Url {
            val path = effectivePath()
            composabilityError(path)?.let { throw IllegalArgumentException(it) }
            return buildResult(path).getOrThrow()
        }

        /**
         * Recomposes the accumulated components into a canonical [Url], or `null` when they cannot
         * form one.
         *
         * The non-throwing counterpart of [build]: every condition [build] would signal with an
         * exception — a special (non-`file`) scheme with no [host], an authority paired with a
         * rootless path, or a recomposed string the engine rejects — instead yields `null`. It never
         * throws.
         *
         * @return the canonical [Url] for the assembled components, or `null` when they do not form a
         *   valid URL.
         */
        public fun buildOrNull(): Url? {
            val path = effectivePath()
            if (composabilityError(path) != null) return null
            return buildResult(path).getOrNull()
        }

        /**
         * Recomposes [path] with the accumulated components and re-parses it through the `Url` engine.
         *
         * The single recompose→parse projection [build] and [buildOrNull] share. Given a [path] it is
         * total — it never throws — so [buildOrNull] projecting it with [ParseResult.getOrNull] cannot
         * throw once the host/authority gate has passed, while [build] projects it with
         * [ParseResult.getOrThrow] to surface a parse failure as [UriSyntaxException].
         */
        private fun buildResult(path: String): ParseResult<Url> = UrlParser.parse(recompose(path)).map { Url(it) }

        /** The path [recompose] serializes, resolving the [BuilderPath] rooting against the current authority. */
        private fun effectivePath(): String = path.effectivePath(host != null)

        /** Recomposes a parseable URL string from the accumulated components. */
        private fun recompose(path: String): String {
            val sb = StringBuilder()
            val encodedQuery = queryState.resolve()
            sb.append(scheme).append(':')
            if (host != null) appendAuthority(sb)
            sb.append(path)
            if (encodedQuery != null) sb.append('?').append(encodedQuery)
            if (encodedFragment != null) sb.append('#').append(encodedFragment)
            return sb.toString()
        }

        /** Appends `//[userinfo@]host[:port]` for a present authority. */
        private fun appendAuthority(sb: StringBuilder) {
            val authorityHost = requireNotNull(host) { "authority requires a host" }
            sb.append("//")
            if (encodedUsername.isNotEmpty() || encodedPassword.isNotEmpty()) {
                sb.append(encodedUsername)
                if (encodedPassword.isNotEmpty()) sb.append(':').append(encodedPassword)
                sb.append('@')
            }
            sb.append(authorityHost)
            if (port != null) sb.append(':').append(port)
        }

        /** True when a host is present or the scheme does not require one (`file`, or any non-special scheme). */
        private fun hasRequiredHost(): Boolean =
            host != null || !Scheme.isSpecial(scheme) || scheme.equals(FILE_SCHEME, ignoreCase = true)

        /** True when [path] is compatible with the authority state: empty or rooted whenever a host is set. */
        private fun pathMatchesAuthority(path: String): Boolean =
            host == null || path.isEmpty() || path.startsWith(SLASH)

        /**
         * True unless an authority-less path begins with `//` (RFC 3986 §3.3): a host-less `//`-leading
         * path would re-parse with its `//` read as an authority, so it cannot be represented.
         */
        private fun pathAllowedWithoutAuthority(path: String): Boolean = host != null || !path.startsWith("//")

        /**
         * The message for the first component combination that cannot form a valid URL, or `null` when
         * the accumulated components are composable.
         *
         * The single ordered source of the composability rules [build] and [buildOrNull] share, so a
         * rule cannot drift between the throwing and the non-throwing path: a special (non-`file`)
         * scheme needs a host (whose absence the parser would misread as the first path segment), a
         * present authority forbids a rootless [path], and a rootless path must not re-root at build.
         */
        private fun composabilityError(path: String): String? =
            when {
                !hasRequiredHost() ->
                    "a special scheme requires a host: set host(...) before build(): $scheme"
                !pathMatchesAuthority(path) ->
                    "a path with an authority must be empty or start with '/': $path"
                !pathAllowedWithoutAuthority(path) ->
                    "an authority-less path cannot begin with '//': $path"
                !this.path.wellFormed() ->
                    "a rootless path cannot begin with an empty segment; the edit would re-root it: $path"
                else -> null
            }
    }
}
