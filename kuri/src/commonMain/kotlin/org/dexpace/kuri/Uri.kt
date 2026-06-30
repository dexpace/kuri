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
import org.dexpace.kuri.parser.Resolver
import org.dexpace.kuri.parser.UriParser
import org.dexpace.kuri.parser.UrlPath
import org.dexpace.kuri.parser.toUriPathString
import org.dexpace.kuri.percent.PercentCodec
import org.dexpace.kuri.scheme.Scheme
import org.dexpace.kuri.serialize.Serializer
import org.dexpace.kuri.serialize.UriNormalizer
import kotlin.jvm.JvmName
import kotlin.jvm.JvmStatic

/** The path-segment separator shared by the encoded-path projection (RFC 3986 §3.3). */
private const val SLASH: String = "/"

/**
 * An immutable, preserve-by-default RFC 3986 generic-URI value (SPEC §3, §11; RFC 3986).
 *
 * A `Uri` wraps the output of the `Uri`-profile §8 engine, so every accessor is a pure projection of
 * the stored components and never re-parses or performs I/O. Unlike [Url], the `Uri` profile is
 * *preserve-by-default* ([PARSE-52]): the scheme and host keep their original case, the path keeps
 * its dot-segments, and an explicit port is never elided. A `Uri` may be a relative reference, so its
 * [scheme] (and authority) can be absent — `null` — which a parsed [Url] never is. Construct one with
 * the [parse] factories or, for programmatic assembly, with [Builder].
 *
 * Equality and hashing are *structural* over the canonical-but-unnormalized [uriString] ([NORM-21]):
 * two `Uri` values are equal only when their preserved serializations match exactly, so
 * `HTTP://H/` and `http://h/` are distinct. Apply [normalized] (RFC 3986 §6.2) or [normalizedEquals]
 * to fold the §6.2 equivalences explicitly. A `Uri` is I/O-free and so a safe `Map`/`Set` key.
 *
 * @sample org.dexpace.kuri.Uri.Companion.parse
 */
@Suppress("TooManyFunctions") // Mirrors the RFC 3986 component surface; each member is a thin projection.
public class Uri internal constructor(
    internal val components: ParsedComponents,
) {
    /** The scheme without its trailing `:`, preserved with its original case, or `null` for a relative reference. */
    @get:JvmName("scheme")
    public val scheme: String?
        get() = components.scheme

    /**
     * The RFC 3986 `userinfo` component, reconstructed from the parsed credentials and preserved verbatim.
     *
     * `null` when the URI has no authority or no userinfo; otherwise `user` or `user:password`. An empty
     * userinfo (e.g. `//@h`) carries no credentials and is therefore reported as absent (`null`).
     */
    @get:JvmName("userInfo")
    public val userInfo: String?
        get() = reconstructUserInfo()

    /** The structured host, or `null` when the URI has no authority ([MODEL-14], [MODEL-15]). */
    @get:JvmName("host")
    public val host: Host?
        get() = components.host

    /** The serialized host text (brackets included for IPv6), or `null` when there is no authority. */
    @get:JvmName("hostName")
    public val hostName: String?
        get() = components.host?.serialize()

    /**
     * The explicit port, preserved exactly as parsed, or `null` when no port was present.
     *
     * Unlike [Url], the `Uri` profile applies no default-port elision and no `0..65535` range cap; a
     * port equal to the scheme default is retained ([PARSE-34]).
     */
    @get:JvmName("port")
    public val port: Int?
        get() = components.port

    /**
     * The encoded path, preserved verbatim ([NORM-15]).
     *
     * Dot-segments are NOT removed in the `Uri` profile, so `http://h/a/../b` keeps the path
     * `/a/../b`; use [normalized] to apply RFC 3986 §6.2.2.3 dot-segment removal.
     */
    @get:JvmName("path")
    public val path: String
        get() = components.path.toUriPathString()

    /** The decoded path segments in order (read-only); an opaque path yields its single decoded value. */
    @get:JvmName("pathSegments")
    public val pathSegments: List<String>
        get() =
            when (val storedPath = components.path) {
                is UrlPath.Opaque -> listOf(PercentCodec.decode(storedPath.path))
                is UrlPath.Segments -> storedPath.segments.map { PercentCodec.decode(it) }
            }

    /** The raw encoded query without its leading `?`, or `null` when no `?` was present ([MODEL-30]). */
    @get:JvmName("query")
    public val query: String?
        get() = components.query

    /** The raw encoded fragment without its leading `#`, or `null` when no `#` was present ([MODEL-33]). */
    @get:JvmName("fragment")
    public val fragment: String?
        get() = components.fragment

    /** The `[userinfo@]host[:port]` authority, or `null` when the URI has no authority ([MODEL-34]). */
    @get:JvmName("authority")
    public val authority: String?
        get() = reconstructAuthority()

    /** Cached canonical-but-unnormalized serialization, computed once ([NORM-23] permits caching). */
    private val canonicalUri: String by lazy { Serializer.serialize(components, ParseProfile.URI) }

    /** The canonical-but-UNNORMALIZED RFC 3986 §5.3 serialization ([NORM-21]); the basis of equality. */
    @get:JvmName("uriString")
    public val uriString: String
        get() = canonicalUri

    /**
     * Returns a [Builder] pre-filled with this URI's components, for producing a modified copy.
     *
     * `uri.newBuilder().build()` reproduces an equal `Uri`.
     *
     * @return a builder seeded with every stored component of this value.
     */
    public fun newBuilder(): Builder = Builder(this)

    /**
     * Resolves [reference] against this URI as its base (SPEC §9; RFC 3986 §5.2 strict transform).
     *
     * The transform is always strict — a scheme present in [reference] is never elided even when it
     * equals this URI's scheme. Resolution requires an absolute base: if this URI has no [scheme] the
     * result is a [ParseResult.Err].
     *
     * @param reference the (possibly relative) reference to resolve.
     * @return [ParseResult.Ok] with the resolved [Uri], or [ParseResult.Err] when resolution fails.
     */
    public fun resolve(reference: String): ParseResult<Uri> =
        when (val resolved = Resolver.resolve(uriString, reference)) {
            is ParseResult.Err -> resolved
            is ParseResult.Ok -> parse(resolved.value)
        }

    /**
     * Returns a copy with the full RFC 3986 §6.2 normalization applied (SPEC §11.1, [NORM-3]).
     *
     * Lowercases the scheme and reg-name host, uppercases percent triplets, decodes `unreserved`
     * triplets, removes dot-segments, and elides a default port. The receiver is left untouched.
     *
     * @return a new, fully normalized `Uri`.
     */
    public fun normalized(): Uri = Uri(UriNormalizer.normalize(components))

    /**
     * Reports whether this URI and [other] are equal after RFC 3986 §6.2 normalization (SPEC §11.3).
     *
     * This is the §6.2-aware counterpart to the structural [equals]: it folds case-only and
     * percent-encoding equivalences, so `HTTP://H/` and `http://h/` compare equal.
     *
     * @param other the URI to compare against, under normalization.
     * @return `true` iff `normalized()` of each value share the same canonical string.
     */
    public fun normalizedEquals(other: Uri): Boolean = normalized().uriString == other.normalized().uriString

    /**
     * Converts this generic URI to a WHATWG [Url] (SPEC §11.5; profile bridge).
     *
     * This MAY fail: a generic URI need not be a valid special-scheme URL (e.g. a relative reference
     * with no scheme, or a value the WHATWG host pipeline rejects), so the result is a [ParseResult].
     *
     * @return [ParseResult.Ok] with the equivalent [Url], or [ParseResult.Err] when [uriString] is
     *   not a valid WHATWG URL.
     */
    public fun toUrl(): ParseResult<Url> = Url.parse(uriString)

    /** The canonical [uriString]; a parsed `Uri` round-trips through `toString` then [parse]. */
    override fun toString(): String = uriString

    /** Structural canonical-string equality ([NORM-21]); pure and I/O-free, with a consistent [hashCode]. */
    override fun equals(other: Any?): Boolean = other is Uri && other.uriString == uriString

    /** The hash of the canonical [uriString], consistent with [equals] ([NORM-23]). */
    override fun hashCode(): Int = uriString.hashCode()

    /** Reconstructs the raw `userinfo` from the decoded credentials, or `null` when none is present. */
    private fun reconstructUserInfo(): String? =
        when {
            components.host == null -> null
            components.username.isEmpty() && components.password.isEmpty() -> null
            components.password.isEmpty() -> components.username
            else -> "${components.username}:${components.password}"
        }

    /** Reconstructs `[userinfo@]host[:port]` for a present authority, or `null` when none is present. */
    private fun reconstructAuthority(): String? {
        val authorityHost = components.host ?: return null
        val credentials = reconstructUserInfo()?.let { "$it@" } ?: ""
        val portPart = components.port?.let { ":$it" } ?: ""
        return "$credentials${authorityHost.serialize()}$portPart"
    }

    /** Parse factories for [Uri] (SPEC §7.5); each returns a value rather than throwing ([ERR-1]). */
    public companion object {
        /**
         * Parses [input] as an RFC 3986 URI-reference, preserve-by-default ([ERR-1], [ERR-2]).
         *
         * A relative reference (no scheme) is accepted, deferring base merging to [resolve] ([PARSE-19]).
         *
         * @param input the URI-reference text to parse, used exactly as supplied.
         * @return [ParseResult.Ok] with the [Uri], or [ParseResult.Err] on the first fatal error.
         */
        @JvmStatic
        public fun parse(input: String): ParseResult<Uri> = UriParser.parse(input).map { Uri(it) }

        /**
         * Parses [input], punning a failure to `null` ([ERR-5]).
         *
         * @param input the URI-reference text to parse.
         * @return the parsed [Uri], or `null` when [input] is not a valid URI-reference.
         */
        @JvmStatic
        public fun parseOrNull(input: String): Uri? = parse(input).getOrNull()

        /**
         * Reports whether [input] parses as a URI-reference without retaining the value ([ERR-5]).
         *
         * @param input the URI-reference text to test.
         * @return `true` iff [parse] would succeed for [input].
         */
        @JvmStatic
        public fun canParse(input: String): Boolean = parse(input).isOk()
    }

    /**
     * A mutable, Java-constructible (`new Uri.Builder()`) assembler for a [Uri].
     *
     * Setters are fluent and accumulate component state; [build] recomposes that state into a URI
     * string and re-parses it through the `Uri`-profile engine, so the produced [Uri] is always a
     * valid, canonical value. Use [Uri.newBuilder] for a pre-filled builder.
     */
    @Suppress("TooManyFunctions") // One setter per RFC 3986 component; each is a one-liner.
    public class Builder {
        private var scheme: String? = null
        private var userInfo: String? = null
        private var host: String? = null
        private var port: Int? = null
        private var encodedPath: String = ""
        private var query: String? = null
        private var fragment: String? = null

        /** Creates an empty builder; set at least a [host] or a non-empty [encodedPath] before [build]. */
        public constructor()

        /** Creates a builder pre-filled from [source] so `source.newBuilder().build()` reproduces it. */
        internal constructor(source: Uri) {
            scheme = source.scheme
            userInfo = source.userInfo
            host = source.hostName
            port = source.port
            encodedPath = source.path
            query = source.query
            fragment = source.fragment
        }

        /**
         * Sets or clears the scheme (without its `:`); the original case is preserved at [build].
         *
         * @param scheme a syntactically valid scheme ([SCH-7]), or `null` to make this a relative reference.
         * @throws IllegalArgumentException when a non-null [scheme] is not a valid scheme ([ERR-6]).
         */
        public fun scheme(scheme: String?): Builder {
            require(scheme == null || Scheme.isValidScheme(scheme)) { "invalid scheme: $scheme" }
            this.scheme = scheme
            return this
        }

        /**
         * Sets or clears the raw `userinfo` component (without its trailing `@`), kept verbatim.
         *
         * @param userInfo the encoded userinfo (e.g. `user` or `user:password`), or `null` to clear it.
         */
        public fun userInfo(userInfo: String?): Builder {
            this.userInfo = userInfo
            return this
        }

        /**
         * Sets or clears the host text; it is validated and canonicalized by the host pipeline at [build].
         *
         * @param host the host text (e.g. a reg-name, IPv4, or `[ipv6]`), or `null` to drop the authority.
         */
        public fun host(host: String?): Builder {
            this.host = host
            return this
        }

        /**
         * Sets or clears the explicit port ([MODEL-23]); the `Uri` profile applies no `0..65535` cap.
         *
         * @param port a non-negative port, or `null` to elide it.
         * @throws IllegalArgumentException when [port] is negative ([ERR-6]).
         */
        public fun port(port: Int?): Builder {
            require(port == null || port >= 0) { "port must be non-negative: $port" }
            this.port = port
            return this
        }

        /**
         * Replaces the entire encoded path verbatim ([MODEL-39]).
         *
         * @param encodedPath the already-encoded path (e.g. `/a/b`); validated at [build].
         */
        public fun encodedPath(encodedPath: String): Builder {
            this.encodedPath = encodedPath
            return this
        }

        /**
         * Replaces the entire encoded path verbatim; an alias of [encodedPath] for call-site ergonomics.
         *
         * @param path the already-encoded path (e.g. `/a/b`); validated at [build].
         */
        public fun path(path: String): Builder = encodedPath(path)

        /**
         * Sets or clears the raw encoded query (without its leading `?`) ([MODEL-30]).
         *
         * @param query the encoded query, or `null` to drop the `?` entirely.
         */
        public fun query(query: String?): Builder {
            this.query = query
            return this
        }

        /**
         * Sets or clears the raw encoded fragment (without its leading `#`) ([MODEL-33]).
         *
         * @param fragment the encoded fragment, or `null` to drop the `#` entirely.
         */
        public fun fragment(fragment: String?): Builder {
            this.fragment = fragment
            return this
        }

        /**
         * Recomposes the accumulated components and re-parses them into a canonical [Uri].
         *
         * @return the [Uri] for the assembled components.
         * @throws UriSyntaxException when the components do not form a valid URI — a builder misuse is
         *   a programmer error rather than a recoverable parse failure ([ERR-6]).
         */
        public fun build(): Uri =
            UriParser.parse(recompose()).fold(
                onOk = { Uri(it) },
                onErr = { throw UriSyntaxException(it) },
            )

        /** Recomposes a parseable URI string from the accumulated components (RFC 3986 §5.3). */
        private fun recompose(): String {
            val sb = StringBuilder()
            if (scheme != null) sb.append(scheme).append(':')
            if (host != null) appendAuthority(sb)
            sb.append(encodedPath)
            if (query != null) sb.append('?').append(query)
            if (fragment != null) sb.append('#').append(fragment)
            return sb.toString()
        }

        /** Appends `//[userinfo@]host[:port]` for a present authority (RFC 3986 §3.2). */
        private fun appendAuthority(sb: StringBuilder) {
            val authorityHost = requireNotNull(host) { "authority requires a host" }
            sb.append(SLASH).append(SLASH)
            if (!userInfo.isNullOrEmpty()) sb.append(userInfo).append('@')
            sb.append(authorityHost)
            if (port != null) sb.append(':').append(port)
        }
    }
}
