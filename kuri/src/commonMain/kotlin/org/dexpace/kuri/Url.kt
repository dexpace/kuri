/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri

import org.dexpace.kuri.error.ParseResult
import org.dexpace.kuri.error.UriSyntaxException
import org.dexpace.kuri.error.fold
import org.dexpace.kuri.error.map
import org.dexpace.kuri.host.Host
import org.dexpace.kuri.host.serialize
import org.dexpace.kuri.parser.ParsedComponents
import org.dexpace.kuri.parser.UrlParser
import org.dexpace.kuri.parser.UrlPath
import org.dexpace.kuri.percent.PercentCodec
import org.dexpace.kuri.percent.PercentEncodeSet
import org.dexpace.kuri.percent.PercentEncodeSets
import org.dexpace.kuri.query.QueryParameters
import org.dexpace.kuri.scheme.Scheme
import org.dexpace.kuri.serialize.Serializer
import kotlin.jvm.JvmName
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

/** The sentinel [Url.effectivePort] returns when a port is neither stated nor defaulted by the scheme. */
private const val NO_DEFAULT_PORT: Int = -1

/** Inclusive upper bound of a WHATWG URL port (`0..65535`); a larger value is a parse failure. */
private const val MAX_PORT: Int = 65535

/** The path-segment separator of a WHATWG URL path (SPEC §9). */
private const val SLASH: String = "/"

/** The raw code points a single encoded path segment must not carry; each would re-split it. */
private const val SEGMENT_DELIMITERS: String = "/\\?#"

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

    /** The percent-encoded userinfo username, or `""` when no credentials are present. */
    @get:JvmName("username")
    public val username: String
        get() = components.username

    /** The percent-encoded userinfo password, or `""` when absent or empty. */
    @get:JvmName("password")
    public val password: String
        get() = components.password

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
        get() = components.host?.serialize()

    /** The explicit port, or `null` when elided or equal to the scheme default. */
    @get:JvmName("port")
    public val port: Int?
        get() = components.port

    /**
     * The port a consumer should connect to: the explicit [port], else the scheme default.
     *
     * @return the stated or default port, or [NO_DEFAULT_PORT] (`-1`) when the scheme has no default
     *   (a non-special scheme, or `file`); the only case the caller must treat as "no port".
     */
    @get:JvmName("effectivePort")
    public val effectivePort: Int
        get() = components.port ?: Scheme.defaultPort(scheme) ?: NO_DEFAULT_PORT

    /** The decoded path segments in order (read-only); an opaque path yields its single decoded value. */
    @get:JvmName("pathSegments")
    public val pathSegments: List<String>
        get() =
            when (val path = components.path) {
                is UrlPath.Opaque -> listOf(PercentCodec.decode(path.path))
                is UrlPath.Segments -> path.segments.map { PercentCodec.decode(it) }
            }

    /** The canonical encoded path string (e.g. `/a/b`, or the opaque path verbatim). */
    @get:JvmName("encodedPath")
    public val encodedPath: String
        get() = serializeEncodedPath()

    /** The raw encoded query without its leading `?`, or `null` when no `?` was present. */
    @get:JvmName("query")
    public val query: String?
        get() = components.query

    /** A decoded, immutable snapshot of the query's `name=value` pairs; never live. */
    @get:JvmName("queryParameters")
    public val queryParameters: QueryParameters
        get() = components.query?.let { QueryParameters.parse(it) } ?: QueryParameters(emptyList())

    /** The raw encoded fragment without its leading `#`, or `null` when no `#` was present. */
    @get:JvmName("fragment")
    public val fragment: String?
        get() = components.fragment

    /** Alias of [fragment]: the fragment is stored already-encoded, so both views coincide. */
    @get:JvmName("encodedFragment")
    public val encodedFragment: String?
        get() = components.fragment

    /** The `userinfo@host:port` authority, or `null` when the URL has no authority. */
    @get:JvmName("authority")
    public val authority: String?
        get() = components.host?.let { serializeAuthority(it) }

    /**
     * The ASCII serialization of this URL's WHATWG origin (§11.6).
     *
     * Three cases apply: a **tuple origin** `scheme://host[:port]` (port only when non-null, userinfo
     * never included) for a special scheme other than `file`; for a `blob:` URL, the origin of the URL
     * parsed from its path when that inner scheme is `http`/`https`/`file`, otherwise opaque; and an
     * **opaque origin** for `file:` and every non-special scheme, serialized as the literal `"null"`.
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

    /** Cached canonical serialization, computed once (permits caching an immutable value). */
    private val canonicalHref: String by lazy { Serializer.serialize(components, ParseProfile.URL) }

    /**
     * The canonical serialized URL; the basis of [toString], [equals], and [hashCode].
     *
     * Equal to [toString].
     */
    @get:JvmName("href")
    public val href: String
        get() = canonicalHref

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
     * @param reference the (possibly relative) reference to resolve.
     * @return [ParseResult.Ok] with the resolved [Url], or [ParseResult.Err] when resolution fails.
     */
    public fun resolve(reference: String): ParseResult<Url> = parse(reference, this)

    /**
     * Converts this WHATWG URL to a generic RFC 3986 [Uri] (SPEC §11.5; profile bridge).
     *
     * This is near-lossless and does not fail: a canonical WHATWG [href] is always a valid RFC 3986
     * URI, so the conversion re-parses [href] under the `Uri` profile and returns the value directly
     * rather than a [ParseResult]. The resulting [Uri] preserves this URL's canonical form (the
     * WHATWG canonicalization has already been applied), so it carries a non-null `scheme`.
     *
     * @return the equivalent generic [Uri].
     */
    public fun toUri(): Uri = Uri.parse(href).getOrThrow()

    /** The canonical [href]; a parsed `Url` round-trips through `toString` then [parse]. */
    override fun toString(): String = href

    /** Canonical-href equality; pure and I/O-free, so two equal URLs share a [hashCode]. */
    override fun equals(other: Any?): Boolean = other is Url && other.href == href

    /** The hash of the canonical [href], consistent with [equals]. */
    override fun hashCode(): Int = href.hashCode()

    /** Recomposes the encoded path, applying the no-authority leading-`/.` guard. */
    private fun serializeEncodedPath(): String =
        when (val path = components.path) {
            is UrlPath.Opaque -> path.path
            is UrlPath.Segments -> joinSegments(path.segments)
        }

    /** Joins encoded [segments] as `/`-prefixed runs, guarding a hostless `//`-opening path. */
    private fun joinSegments(segments: List<String>): String {
        val joined = segments.joinToString("") { "/$it" }
        val needsGuard = components.host == null && segments.size > 1 && segments[0] == ""
        return if (needsGuard) "/.$joined" else joined
    }

    /** Serializes `[userinfo@]host[:port]` for a present authority. */
    private fun serializeAuthority(authorityHost: Host): String {
        val credentials = if (username.isEmpty() && password.isEmpty()) "" else credentialsPrefix()
        val portPart = components.port?.let { ":$it" } ?: ""
        return "$credentials${authorityHost.serialize()}$portPart"
    }

    /** The `user[:password]@` credentials prefix for a value that includes credentials. */
    private fun credentialsPrefix(): String {
        val passwordPart = if (password.isNotEmpty()) ":$password" else ""
        return "$username$passwordPart@"
    }

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
        private var encodedPath: String = ""

        /** True while [encodedPath] was assembled from empty by segment appends; its rooting is decided at [build]. */
        private var isSegmentBuiltPath: Boolean = false
        private var encodedQuery: String? = null
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
            encodedPath = source.encodedPath
            encodedQuery = source.query
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
         * @param username the decoded username; `""` clears the username.
         */
        public fun username(username: String): Builder {
            encodedUsername = PercentCodec.encode(username, PercentEncodeSets.USERINFO)
            return this
        }

        /**
         * Sets the userinfo password, percent-encoding it under the userinfo set.
         *
         * @param password the decoded password; `""` clears the password.
         */
        public fun password(password: String): Builder {
            encodedPassword = PercentCodec.encode(password, PercentEncodeSets.USERINFO)
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
            this.encodedPath = encodedPath
            isSegmentBuiltPath = false // a verbatim path owns its own (root or rootless) shape.
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
        public fun addPathSegment(segment: String): Builder =
            pushSegment(PercentCodec.encode(segment, URL_PATH_SEGMENT_ENCODE_SET))

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
        public fun addEncodedPathSegment(segment: String): Builder = pushSegment(segment)

        /**
         * Sets or clears the raw encoded query (without its leading `?`).
         *
         * @param query the encoded query, or `null` to drop the `?` entirely.
         */
        public fun query(query: String?): Builder {
            encodedQuery = query
            return this
        }

        /**
         * Replace-first/remove-rest sets the query parameter [name] to [value].
         *
         * @param name the decoded parameter name.
         * @param value the decoded parameter value, or `null` for a name with no `=`.
         */
        public fun setQueryParameter(
            name: String,
            value: String?,
        ): Builder {
            val current = encodedQuery?.let { QueryParameters.parse(it) } ?: QueryParameters(emptyList())
            encodedQuery =
                current
                    .newBuilder()
                    .set(name, value)
                    .build()
                    .toQueryString()
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
            require(host != null || !Scheme.isSpecial(scheme) || scheme.equals(FILE_SCHEME, ignoreCase = true)) {
                "a special scheme requires a host: set host(...) before build(): $scheme"
            }
            require(host == null || path.isEmpty() || path.startsWith(SLASH)) {
                "a path with an authority must be empty or start with '/': $path"
            }
            return UrlParser.parse(recompose(path)).fold(
                onOk = { Url(it) },
                onErr = { throw UriSyntaxException(it) },
            )
        }

        /**
         * Appends [encodedSegment] at the path's segment boundary; rooting is deferred to
         * [effectivePath].
         *
         * The path takes the segment directly when it is empty (a rootless first segment) or already
         * ends in `/` (an open final slot); otherwise a separating `/` is inserted first. A segment
         * must already be exactly one segment, so a raw `/`, `\`, `?`, or `#` is rejected rather than
         * silently re-split (RFC 3986 §3.3; SPEC [MODEL-39]).
         */
        private fun pushSegment(encodedSegment: String): Builder {
            val delimiter = encodedSegment.indexOfFirst { it in SEGMENT_DELIMITERS }
            require(delimiter < 0) {
                "a path segment must not contain '/', '\\', '?', or '#' " +
                    "(found '${encodedSegment[delimiter]}' at index $delimiter): $encodedSegment"
            }
            if (encodedPath.isEmpty()) isSegmentBuiltPath = true
            if (encodedPath.isNotEmpty() && !encodedPath.endsWith(SLASH)) encodedPath += SLASH
            encodedPath += encodedSegment
            check(encodedPath.endsWith(encodedSegment)) { "the pushed segment must terminate the path" }
            return this
        }

        /**
         * The path [recompose] serializes: a segment-built path gains its root `/` exactly when a
         * [host] is present, because a rootless path after an authority is invalid while the rootless
         * form is the only one a host-less opaque-path value can carry (SPEC §9).
         */
        private fun effectivePath(): String {
            if (!isSegmentBuiltPath || host == null) return encodedPath
            check(!encodedPath.startsWith(SLASH)) { "a segment-built path is stored rootless: $encodedPath" }
            return SLASH + encodedPath
        }

        /** Recomposes a parseable URL string from the accumulated components. */
        private fun recompose(path: String): String {
            val sb = StringBuilder()
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
    }
}
