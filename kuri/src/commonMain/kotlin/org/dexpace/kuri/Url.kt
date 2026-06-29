/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri

import org.dexpace.kuri.error.ParseResult
import org.dexpace.kuri.error.UriSyntaxException
import org.dexpace.kuri.error.fold
import org.dexpace.kuri.error.getOrNull
import org.dexpace.kuri.error.isOk
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
import org.dexpace.kuri.query.newBuilder
import org.dexpace.kuri.scheme.Scheme
import org.dexpace.kuri.serialize.Serializer
import kotlin.jvm.JvmName
import kotlin.jvm.JvmStatic

/** The sentinel [Url.effectivePort] returns when a port is neither stated nor defaulted by the scheme. */
private const val NO_DEFAULT_PORT: Int = -1

/** Inclusive upper bound of a WHATWG URL port (`0..65535`); a larger value is a parse failure. */
private const val MAX_PORT: Int = 65535

/** The `application/x-www-form-urlencoded`-free path-segment encode set: the path set plus `/`. */
private val PATH_SEGMENT_ENCODE_SET: PercentEncodeSet = PercentEncodeSets.PATH.including('/')

/**
 * An immutable, fully-canonical WHATWG URL value (SPEC §3, §11; WHATWG URL Living Standard).
 *
 * A `Url` wraps the eager-canonical output of the §8 parsing engine, so every accessor is a pure
 * projection of the stored components and never re-parses or performs I/O. A successfully parsed
 * `Url` always carries a non-null, non-empty [scheme] and — for a special scheme — a [host]
 * ([MODEL-8], [MODEL-49]). Construct one with the [parse] factories or, for programmatic assembly,
 * with [Builder].
 *
 * Equality and hashing are defined over the canonical [href] ([NORM-20]); no DNS resolution or other
 * I/O occurs, so a `Url` is a safe `Map`/`Set` key ([NORM-19], [NORM-23]).
 *
 * @sample org.dexpace.kuri.Url.Companion.parse
 */
@Suppress("TooManyFunctions") // Mirrors the WHATWG/okhttp accessor surface; each member is a thin projection.
public class Url internal constructor(
    internal val components: ParsedComponents,
) {
    /** The lower-cased scheme without its trailing `:`; never blank for a parsed `Url` ([MODEL-9]). */
    @get:JvmName("scheme")
    public val scheme: String
        get() = requireNotNull(components.scheme) { "a parsed Url always carries a scheme" }

    /** The percent-encoded userinfo username, or `""` when no credentials are present ([MODEL-48]). */
    @get:JvmName("username")
    public val username: String
        get() = components.username

    /** The percent-encoded userinfo password, or `""` when absent or empty ([MODEL-48]). */
    @get:JvmName("password")
    public val password: String
        get() = components.password

    /** The structured host, or `null` when the URL has no authority ([MODEL-14], [MODEL-15]). */
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

    /** The explicit port, or `null` when elided or equal to the scheme default ([MODEL-23], [MODEL-25]). */
    @get:JvmName("port")
    public val port: Int?
        get() = components.port

    /**
     * The port a consumer should connect to: the explicit [port], else the scheme default ([SCH-18]).
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

    /** The canonical encoded path string (e.g. `/a/b`, or the opaque path verbatim) ([NORM-15]). */
    @get:JvmName("encodedPath")
    public val encodedPath: String
        get() = serializeEncodedPath()

    /** The raw encoded query without its leading `?`, or `null` when no `?` was present ([MODEL-30]). */
    @get:JvmName("query")
    public val query: String?
        get() = components.query

    /** A decoded, immutable snapshot of the query's `name=value` pairs ([MODEL-31]); never live. */
    @get:JvmName("queryParameters")
    public val queryParameters: QueryParameters
        get() = components.query?.let { QueryParameters.parse(it) } ?: QueryParameters(emptyList())

    /** The raw encoded fragment without its leading `#`, or `null` when no `#` was present ([MODEL-33]). */
    @get:JvmName("fragment")
    public val fragment: String?
        get() = components.fragment

    /** Alias of [fragment]: the fragment is stored already-encoded, so both views coincide ([MODEL-33]). */
    @get:JvmName("encodedFragment")
    public val encodedFragment: String?
        get() = components.fragment

    /** The `userinfo@host:port` authority, or `null` when the URL has no authority ([MODEL-34]). */
    @get:JvmName("authority")
    public val authority: String?
        get() = components.host?.let { serializeAuthority(it) }

    /**
     * The WHATWG origin tuple serialized as `scheme://host[:port]` ([MODEL-34], §11).
     *
     * The port appears only when a non-default port is present; for a value without an authority the
     * result degrades to `scheme://`. The origin is a projection and is not guaranteed to round-trip.
     */
    @get:JvmName("origin")
    public val origin: String
        get() = "$scheme://${hostName ?: ""}${port?.let { ":$it" } ?: ""}"

    /** Cached canonical serialization, computed once ([NORM-23] permits caching an immutable value). */
    private val canonicalHref: String by lazy { Serializer.serialize(components, ParseProfile.URL) }

    /** The canonical serialized URL ([NORM-15]); the basis of [toString], [equals], and [hashCode]. */
    @get:JvmName("href")
    public val href: String
        get() = canonicalHref

    /**
     * Returns a [Builder] pre-filled with this URL's components, for producing a modified copy
     * ([MODEL-37]). `url.newBuilder().build()` reproduces an equal `Url` ([NORM-27]).
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

    /** The canonical [href]; a parsed `Url` round-trips through `toString` then [parse] ([NORM-25]). */
    override fun toString(): String = href

    /** Canonical-href equality ([NORM-20]); pure and I/O-free, so two equal URLs share a [hashCode]. */
    override fun equals(other: Any?): Boolean = other is Url && other.href == href

    /** The hash of the canonical [href], consistent with [equals] ([NORM-23]). */
    override fun hashCode(): Int = href.hashCode()

    /** Recomposes the encoded path, applying the no-authority leading-`/.` guard ([NORM-18]). */
    private fun serializeEncodedPath(): String =
        when (val path = components.path) {
            is UrlPath.Opaque -> path.path
            is UrlPath.Segments -> joinSegments(path.segments)
        }

    /** Joins encoded [segments] as `/`-prefixed runs, guarding a hostless `//`-opening path ([NORM-18]). */
    private fun joinSegments(segments: List<String>): String {
        val joined = segments.joinToString("") { "/$it" }
        val needsGuard = components.host == null && segments.size > 1 && segments[0] == ""
        return if (needsGuard) "/.$joined" else joined
    }

    /** Serializes `[userinfo@]host[:port]` for a present authority ([NORM-16], [NORM-30]). */
    private fun serializeAuthority(authorityHost: Host): String {
        val credentials = if (username.isEmpty() && password.isEmpty()) "" else credentialsPrefix()
        val portPart = components.port?.let { ":$it" } ?: ""
        return "$credentials${authorityHost.serialize()}$portPart"
    }

    /** The `user[:password]@` credentials prefix for a value that includes credentials ([MODEL-48]). */
    private fun credentialsPrefix(): String {
        val passwordPart = if (password.isNotEmpty()) ":$password" else ""
        return "$username$passwordPart@"
    }

    /** Parse factories for [Url] (SPEC §7.5, §12.1); each returns a value rather than throwing ([ERR-1]). */
    public companion object {
        /**
         * Parses [input] as an absolute WHATWG URL ([ERR-1], [ERR-2]).
         *
         * @param input the URL text to parse.
         * @return [ParseResult.Ok] with the canonical [Url], or [ParseResult.Err] on failure.
         */
        @JvmStatic
        public fun parse(input: String): ParseResult<Url> = UrlParser.parse(input).map { Url(it) }

        /**
         * Parses [input], resolving a relative reference against [base] when supplied (SPEC §9).
         *
         * @param input the (possibly relative) URL text to parse.
         * @param base the base URL for relative resolution, or `null` for an absolute parse.
         * @return [ParseResult.Ok] with the canonical [Url], or [ParseResult.Err] on failure.
         */
        @JvmStatic
        public fun parse(
            input: String,
            base: Url?,
        ): ParseResult<Url> = UrlParser.parse(input, base?.components).map { Url(it) }

        /**
         * Parses [input], punning a failure to `null` ([ERR-5]).
         *
         * @param input the URL text to parse.
         * @return the parsed [Url], or `null` when [input] is not a valid URL.
         */
        @JvmStatic
        public fun parseOrNull(input: String): Url? = parse(input).getOrNull()

        /**
         * Reports whether [input] parses as a URL without allocating the value ([ERR-5]).
         *
         * @param input the URL text to test.
         * @return `true` iff [parse] would succeed for [input].
         */
        @JvmStatic
        public fun canParse(input: String): Boolean = parse(input).isOk()
    }

    /**
     * A mutable, Java-constructible (`new Url.Builder()`) assembler for a [Url] ([MODEL-36]).
     *
     * Setters are fluent and accumulate component state; [build] recomposes that state into a URL
     * string and re-parses it through the engine, so the produced [Url] is always canonical
     * ([MODEL-38], [NORM-27]). Use [Url.newBuilder] for a pre-filled builder.
     */
    @Suppress("TooManyFunctions") // Paired decoded/encoded setters per component ([MODEL-39]); each is a one-liner.
    public class Builder {
        private var scheme: String = ""
        private var encodedUsername: String = ""
        private var encodedPassword: String = ""
        private var host: String? = null
        private var port: Int? = null
        private var encodedPath: String = ""
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
         * Sets the scheme (without its `:`); lower-casing happens at [build] ([SCH-15]).
         *
         * @param scheme a syntactically valid scheme ([SCH-7]).
         * @throws IllegalArgumentException when [scheme] is not a valid scheme (a programmer error, [ERR-6]).
         */
        public fun scheme(scheme: String): Builder {
            require(Scheme.isValidScheme(scheme)) { "invalid scheme: $scheme" }
            this.scheme = scheme
            return this
        }

        /**
         * Sets the userinfo username, percent-encoding it under the userinfo set ([MODEL-39]).
         *
         * @param username the decoded username; `""` clears the username.
         */
        public fun username(username: String): Builder {
            encodedUsername = PercentCodec.encode(username, PercentEncodeSets.USERINFO)
            return this
        }

        /**
         * Sets the userinfo password, percent-encoding it under the userinfo set ([MODEL-39]).
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
         * Sets or clears the explicit port ([MODEL-23]).
         *
         * @param port a port in `0..65535`, or `null` to elide it.
         * @throws IllegalArgumentException when [port] is out of range (a programmer error, [ERR-6]).
         */
        public fun port(port: Int?): Builder {
            require(port == null || port in 0..MAX_PORT) { "port out of range: $port" }
            this.port = port
            return this
        }

        /**
         * Replaces the entire encoded path ([MODEL-39]).
         *
         * @param encodedPath the already-encoded path (e.g. `/a/b`); validated at [build].
         */
        public fun encodedPath(encodedPath: String): Builder {
            this.encodedPath = encodedPath
            return this
        }

        /**
         * Appends one decoded path segment, percent-encoding it (including any `/`) ([MODEL-39]).
         *
         * @param segment the decoded segment to append.
         */
        public fun addPathSegment(segment: String): Builder =
            pushSegment(PercentCodec.encode(segment, PATH_SEGMENT_ENCODE_SET))

        /**
         * Appends one already-encoded path segment verbatim ([MODEL-39]).
         *
         * @param encodedSegment the encoded segment to append; kept as supplied.
         */
        public fun addEncodedPathSegment(encodedSegment: String): Builder = pushSegment(encodedSegment)

        /**
         * Sets or clears the raw encoded query (without its leading `?`) ([MODEL-30]).
         *
         * @param query the encoded query, or `null` to drop the `?` entirely.
         */
        public fun query(query: String?): Builder {
            encodedQuery = query
            return this
        }

        /**
         * Replace-first/remove-rest sets the query parameter [name] to [value] ([QUERY-16]).
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
         * Sets or clears the raw encoded fragment (without its leading `#`) ([MODEL-33]).
         *
         * @param fragment the encoded fragment, or `null` to drop the `#` entirely.
         */
        public fun fragment(fragment: String?): Builder {
            encodedFragment = fragment
            return this
        }

        /**
         * Recomposes the accumulated components and re-parses them into a canonical [Url] ([MODEL-38]).
         *
         * @return the canonical [Url] for the assembled components.
         * @throws UriSyntaxException when the components do not form a valid URL — a builder
         *   misuse is a programmer error rather than a recoverable parse failure ([ERR-6]).
         */
        public fun build(): Url =
            UrlParser.parse(recompose()).fold(
                onOk = { Url(it) },
                onErr = { throw UriSyntaxException(it) },
            )

        /** Appends [encodedSegment] to the encoded path, inserting a separating `/` when needed. */
        private fun pushSegment(encodedSegment: String): Builder {
            if (!encodedPath.endsWith("/")) encodedPath += "/"
            encodedPath += encodedSegment
            return this
        }

        /** Recomposes a parseable URL string from the accumulated components ([NORM-15]). */
        private fun recompose(): String {
            val sb = StringBuilder()
            sb.append(scheme).append(':')
            if (host != null) appendAuthority(sb)
            sb.append(encodedPath)
            if (encodedQuery != null) sb.append('?').append(encodedQuery)
            if (encodedFragment != null) sb.append('#').append(encodedFragment)
            return sb.toString()
        }

        /** Appends `//[userinfo@]host[:port]` for a present authority ([NORM-16]). */
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
