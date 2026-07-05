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
import org.dexpace.kuri.parser.Resolver
import org.dexpace.kuri.parser.UriParser
import org.dexpace.kuri.parser.UrlPath
import org.dexpace.kuri.parser.splitUriPath
import org.dexpace.kuri.parser.toUriPathString
import org.dexpace.kuri.percent.PercentCodec
import org.dexpace.kuri.percent.PercentEncodeSet
import org.dexpace.kuri.percent.PercentEncodeSets
import org.dexpace.kuri.query.QueryParameters
import org.dexpace.kuri.scheme.Scheme
import org.dexpace.kuri.serialize.Serializer
import org.dexpace.kuri.serialize.UriNormalizer
import org.dexpace.kuri.serialize.guardRecomposedUriPath
import kotlin.jvm.JvmName
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

/** The path-segment separator shared by the encoded-path projection (RFC 3986 §3.3). */
private const val SLASH: String = "/"

/** The raw code points a single encoded path segment must not carry; each would re-split it. */
private const val SEGMENT_DELIMITERS: String = "/\\?#"

/**
 * The encode set applied to a decoded segment added via [Uri.Builder.addPathSegment]
 * (`encodeURIComponent`, RFC 3986 §3.3). It percent-encodes the reserved delimiters that could
 * break a segment — including `/` and `\` (segment separators), `?` and `#` (which would open a
 * query or fragment), and `%` (the escape introducer) — so an added segment can never merge into a
 * neighbouring component nor produce a bare `%` that the strict `Uri` parser rejects. Matching
 * `encodeURIComponent`, the always-safe sub-delims (`!`, `'`, `(`, `)`, `*`) and the RFC 3986
 * `unreserved` set are left literal.
 */
private val URI_PATH_SEGMENT_ENCODE_SET: PercentEncodeSet = PercentEncodeSets.COMPONENT

/**
 * An immutable, preserve-by-default RFC 3986 generic-URI value (SPEC §3, §11; RFC 3986).
 *
 * A `Uri` wraps the output of the `Uri`-profile §8 engine, so every accessor is a pure projection of
 * the stored components and never re-parses or performs I/O. Unlike [Url], the `Uri` profile is
 * *preserve-by-default*: the scheme and reg-name (domain) hosts keep their original
 * case, the path keeps its dot-segments, and an explicit port is never elided. An IP-literal host is
 * the exception — it is canonicalized on output, so an IPv6 literal is re-serialized to its RFC 5952
 * form (§7). A `Uri` may be a relative reference, so its [scheme] (and authority) can be absent —
 * `null` — which a parsed [Url] never is. Construct one with
 * the [parse] factories or, for programmatic assembly, with [Builder].
 *
 * Equality and hashing are *structural* over the canonical-but-unnormalized [uriString]:
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

    /** The structured host, or `null` when the URI has no authority. */
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
     * port equal to the scheme default is retained.
     */
    @get:JvmName("port")
    public val port: Int?
        get() = components.port

    /**
     * The decoded path, each segment percent-decoded — the `java.net.URI.getPath()`-shaped view.
     *
     * Every segment is percent-decoded and re-joined with `/`, so `http://h/a%2Fb` yields `/a/b`. This
     * projection is therefore lossy where a segment held an encoded `/`: use [encodedPath] for the
     * byte-exact form and [pathSegments] for the loss-free decoded segments. An opaque path (see
     * [isOpaquePath]) is decoded whole. Dot-segments are NOT removed in the `Uri` profile, so
     * `http://h/a/../b` keeps `/a/../b`; apply [normalized] for RFC 3986 §6.2.2.3 removal.
     */
    @get:JvmName("path")
    public val path: String
        get() = decodedPath()

    /** The decoded path segments in order (read-only); an opaque path yields its single decoded value. */
    @get:JvmName("pathSegments")
    public val pathSegments: List<String>
        get() =
            when (val storedPath = components.path) {
                is UrlPath.Opaque -> listOf(PercentCodec.decode(storedPath.path))
                is UrlPath.Segments -> storedPath.segments.map { PercentCodec.decode(it) }
            }

    /**
     * The raw encoded query without its leading `?`, or `null` when no `?` was present.
     *
     * The `null` (absent, no `?`) and `""` (present-but-empty, a bare `?`) cases stay distinct:
     * `http://h/p` yields `null` while `http://h/p?` yields `""`. Use [queryParameters] for the
     * decoded `name=value` pairs.
     */
    @get:JvmName("query")
    public val query: String?
        get() = components.query

    /**
     * The raw encoded fragment without its leading `#`, or `null` when no `#` was present.
     *
     * The `null` (absent, no `#`) and `""` (present-but-empty, a bare `#`) cases stay distinct:
     * `http://h/p` yields `null` while `http://h/p#` yields `""`.
     */
    @get:JvmName("fragment")
    public val fragment: String?
        get() = components.fragment

    /** The `[userinfo@]host[:port]` authority, or `null` when the URI has no authority. */
    @get:JvmName("authority")
    public val authority: String?
        get() = reconstructAuthority()

    /**
     * A decoded, immutable snapshot of this URI's query `name=value` pairs; never a live view.
     *
     * Parses [query] with generic query decoding (`+` kept literal). An absent query (`query == null`)
     * yields an empty snapshot, while a present-but-empty query (`query == ""`) yields the single
     * empty pair the WHATWG/`URLSearchParams` parser produces, so the two stay distinguishable.
     *
     * @return the decoded, ordered, duplicate-preserving snapshot; empty when there is no query.
     */
    public fun queryParameters(): QueryParameters =
        query?.let { QueryParameters.parse(it) } ?: QueryParameters(emptyList())

    /**
     * The last non-empty decoded path segment — the "file name" — or `""` when the path has none.
     *
     * Trailing empty segments (a trailing `/`) are skipped, so both `/a/b` and `/a/b/` return `"b"`;
     * a root-only or empty path returns `""`. Segments are decoded, so `/a/c%20d` returns `"c d"`.
     *
     * @return the last non-empty decoded segment, or `""` when there is none.
     */
    public fun fileName(): String = pathSegments.lastOrNull { it.isNotEmpty() } ?: ""

    /**
     * The file extension of [fileName]: the substring after its last `.`, or `""` when it has none.
     *
     * Returns `""` when [fileName] has no `.`, ends in `.` (a trailing dot, e.g. `"file."`), or has
     * only a leading `.` (a dotfile, e.g. `".bashrc"`); `"archive.tar.gz"` yields `"gz"`.
     *
     * @return the extension after the last interior `.` of [fileName], or `""` when there is none.
     */
    public fun fileExtension(): String {
        val name = fileName()
        val dot = name.lastIndexOf('.')
        return if (dot > 0 && dot < name.length - 1) name.substring(dot + 1) else ""
    }

    /** Cached canonical-but-unnormalized serialization, computed once. */
    private val canonicalUri: String by lazy { Serializer.serialize(components, ParseProfile.URI) }

    /**
     * The canonical-but-UNNORMALIZED RFC 3986 §5.3 serialization; the basis of equality.
     *
     * Equal to [toString]. The `Uri`-profile analogue of [Url.href]: unlike a [Url], whose
     * [href][Url.href] is eager-canonical (fully normalized), this preserves the input's case,
     * dot-segments, and explicit port, so [normalized] must be applied to fold the RFC 3986 §6.2
     * equivalences.
     *
     * @see Url.href for the WHATWG-profile, eager-canonical counterpart.
     */
    @get:JvmName("uriString")
    public val uriString: String
        get() = canonicalUri

    /**
     * The percent-encoded path, preserved verbatim (the raw `pchar` form; portable name shared with
     * `Url.encodedPath()`).
     *
     * Unlike [path] this decodes nothing, so `http://h/a%2Fb` keeps `/a%2Fb`. Dot-segments are NOT
     * removed in the `Uri` profile, so `http://h/a/../b` keeps `/a/../b`; use [normalized] to apply
     * RFC 3986 §6.2.2.3 dot-segment removal.
     */
    @get:JvmName("encodedPath")
    public val encodedPath: String
        get() = components.path.toUriPathString()

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
     * @param options the opt-in parsing configuration applied to the resolved reference; defaults to
     *   the options this base's own components imply, so a zoned base resolves without the caller
     *   re-supplying options.
     * @return [ParseResult.Ok] with the resolved [Uri], or [ParseResult.Err] when resolution fails.
     */
    @JvmOverloads
    public fun resolve(
        reference: String,
        options: ParseOptions = roundTripOptions(components.host),
    ): ParseResult<Uri> =
        when (val resolved = Resolver.resolve(uriString, reference, options)) {
            is ParseResult.Err -> resolved
            is ParseResult.Ok -> parse(resolved.value, options)
        }

    /**
     * Resolves [reference] against this URI, punning a resolution failure to `null` (SPEC §9).
     *
     * The `null`-returning counterpart of [resolve], for a call site that prefers a nullable value to
     * a [ParseResult] branch; resolution fails (yielding `null`) when this URI has no [scheme] or
     * [reference] does not parse against it.
     *
     * @param reference the (possibly relative) reference to resolve.
     * @param options the opt-in parsing configuration applied to the resolved reference; defaults to
     *   the options this base's own components imply.
     * @return the resolved [Uri], or `null` when resolution fails.
     */
    @JvmOverloads
    public fun resolveOrNull(
        reference: String,
        options: ParseOptions = roundTripOptions(components.host),
    ): Uri? = resolve(reference, options).getOrNull()

    /**
     * Resolves [reference] against this URI, throwing when resolution fails (SPEC §9).
     *
     * The throwing counterpart of [resolve]; the thrown [UriSyntaxException.error] is the same
     * structured error [resolve] would report in its [ParseResult.Err].
     *
     * @param reference the (possibly relative) reference to resolve.
     * @param options the opt-in parsing configuration applied to the resolved reference; defaults to
     *   the options this base's own components imply.
     * @return the resolved [Uri].
     * @throws UriSyntaxException when this URI has no [scheme] or [reference] does not resolve.
     */
    @JvmOverloads
    public fun resolveOrThrow(
        reference: String,
        options: ParseOptions = roundTripOptions(components.host),
    ): Uri = resolve(reference, options).getOrThrow()

    /**
     * Returns a relative reference that [resolve]s back to [target] against this URI (RFC 3986 §5.2
     * inverse; SPEC §9).
     *
     * The inverse of [resolve] for the common case: when this URI and [target] share the same [scheme]
     * and [authority] (compared exactly) and neither has an opaque path (see [isOpaquePath]), the
     * result drops the shared path prefix and carries [target]'s trailing path, query, and fragment,
     * so resolving it against this URI reproduces [target]. When the two do not share an origin, or
     * [target] is not at or under this URI's path directory, [target] is returned unchanged (already a
     * valid reference). This is total: it never throws.
     *
     * @param target the URI to express relative to this one.
     * @return a relative-path [Uri] that resolves to [target], or [target] unchanged when it cannot be
     *   made relative.
     */
    public fun relativize(target: Uri): Uri {
        val childPath = target.encodedPath
        val baseDir = relativizeBaseDir(target, childPath) ?: return target
        check(baseDir.length <= childPath.length) { "a relative suffix cannot exceed the target path" }
        return Builder()
            .encodedPath(childPath.substring(baseDir.length))
            .query(target.query)
            .fragment(target.fragment)
            .buildOrNull() ?: target
    }

    /**
     * The base-directory prefix to strip from [childPath] when relativizing [target] against this
     * URI, or `null` when they cannot be made relative (an opaque path, a different scheme/authority,
     * or [target] not at or under this URI's path directory).
     */
    private fun relativizeBaseDir(
        target: Uri,
        childPath: String,
    ): String? {
        val sharesHierarchy =
            !isOpaquePath() && !target.isOpaquePath() && scheme == target.scheme && authority == target.authority
        if (!sharesHierarchy) return null
        val basePath = encodedPath
        val baseDir = if (basePath == childPath || basePath.endsWith(SLASH)) basePath else basePath + SLASH
        return if (childPath == basePath || childPath.startsWith(baseDir)) baseDir else null
    }

    /**
     * Returns a copy with the full RFC 3986 §6.2 normalization applied (SPEC §11.1).
     *
     * Lowercases the scheme and reg-name host, uppercases percent triplets, decodes `unreserved`
     * triplets, removes dot-segments, and elides a default port. The receiver is left untouched.
     *
     * This explicit step is the `Uri`-profile analogue of a [Url]'s eager canonicalization: a parsed
     * [Url] is already normalized (its [href][Url.href] folds these equivalences), whereas the
     * preserve-by-default `Uri` defers them to this call.
     *
     * @return a new, fully normalized `Uri`.
     */
    public fun normalized(): Uri = Uri(UriNormalizer.normalize(components))

    /**
     * Reports whether this URI and [other] are equal after RFC 3986 §6.2 normalization (SPEC §11.3).
     *
     * This is the §6.2-aware counterpart to the structural [equals]: it folds case-only and
     * percent-encoding equivalences, so `HTTP://H/` and `http://h/` compare equal. It is the
     * `Uri`-profile analogue of a [Url]'s structural equality, which already compares eager-canonical
     * [href][Url.href] values and so needs no separate normalization step.
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

    /**
     * Converts this generic URI to a WHATWG [Url], punning a failure to `null` (SPEC §11.5).
     *
     * The `null`-returning counterpart of [toUrl]; yields `null` when [uriString] is not a valid
     * WHATWG URL (e.g. a relative reference, or a host the WHATWG pipeline rejects).
     *
     * @return the equivalent [Url], or `null` when this URI is not a valid WHATWG URL.
     */
    public fun toUrlOrNull(): Url? = toUrl().getOrNull()

    /**
     * Converts this generic URI to a WHATWG [Url], throwing when it is not one (SPEC §11.5).
     *
     * The throwing counterpart of [toUrl]; the thrown [UriSyntaxException.error] is the same
     * structured error [toUrl] would report in its [ParseResult.Err].
     *
     * @return the equivalent [Url].
     * @throws UriSyntaxException when [uriString] is not a valid WHATWG URL.
     */
    public fun toUrlOrThrow(): Url = toUrl().getOrThrow()

    /**
     * Reports whether this URI is absolute — it carries a [scheme] (RFC 3986 §4.3).
     *
     * An absolute-form reference has a scheme; a relative reference (parsed from a scheme-less input)
     * does not, and is resolved against a base via [resolve]. Unlike `java.net.URI.isAbsolute`, this
     * is independent of any fragment: a scheme-bearing value is absolute regardless of a `#`.
     *
     * @return `true` iff [scheme] is non-`null`.
     */
    public fun isAbsolute(): Boolean = scheme != null

    /**
     * Reports whether this URI has an opaque (non-hierarchical) path (RFC 3986 §3.3; SPEC §3.7).
     *
     * True for an absolute URI whose scheme-specific part is a rootless path with no authority — the
     * `java.net.URI.isOpaque()` shape — e.g. `mailto:user@example.com` or `urn:isbn:0451450523`, whose
     * [encodedPath] does not begin with `/`. Such a path is scheme-specific and not subject to
     * dot-segment removal or path merging during [resolve]. A value with an authority (`http://h/p`),
     * an absolute path (`file:/p`), or no scheme (a relative reference) is hierarchical, so `false`.
     *
     * @return `true` iff this URI is absolute with an authority-less, rootless (opaque) path.
     */
    public fun isOpaquePath(): Boolean = components.path is UrlPath.Opaque || hasRootlessSchemePath()

    /**
     * The port a consumer should connect to: the explicit [port], else this scheme's default.
     *
     * Falls back to the scheme's registered default port (e.g. `80` for `http`, `443` for `https`)
     * when no [port] is stated, and to `null` when the port is neither stated nor defaulted — a
     * scheme-less reference, or a scheme with no default such as `mailto` or `file`. Unlike
     * [Url.effectivePort] (which returns the sentinel `-1`), the `Uri` profile reports the "no port"
     * case as `null`.
     *
     * @return the stated port, else the scheme default, else `null` when neither applies.
     */
    public fun effectivePort(): Int? = port ?: scheme?.let { Scheme.defaultPort(it) }

    /**
     * Returns a copy of this URI with its [port] set to [port] (or elided when `null`).
     *
     * A thin [newBuilder] rebuild; every other component is preserved. A value obtained from [parse]
     * always rebuilds, so this does not throw for such a value.
     *
     * @param port a non-negative port, or `null` to elide it.
     * @return a new `Uri` with the requested port.
     * @throws IllegalArgumentException when [port] is negative.
     */
    public fun withPort(port: Int?): Uri = newBuilder().port(port).build()

    /**
     * Returns a copy of this URI with its [fragment] set to [fragment] (or dropped when `null`).
     *
     * A thin [newBuilder] rebuild; every other component is preserved. A value obtained from [parse]
     * always rebuilds, so this does not throw for such a value.
     *
     * @param fragment the encoded fragment (without its leading `#`), or `null` to drop the `#`.
     * @return a new `Uri` with the requested fragment.
     */
    public fun withFragment(fragment: String?): Uri = newBuilder().fragment(fragment).build()

    /**
     * Returns a copy of this URI with its fragment removed; equivalent to `withFragment(null)`.
     *
     * @return a new `Uri` with no `#` fragment.
     */
    public fun withoutFragment(): Uri = withFragment(null)

    /** The canonical [uriString]; a parsed `Uri` round-trips through `toString` then [parse]. */
    override fun toString(): String = uriString

    /** Structural canonical-string equality; pure and I/O-free, with a consistent [hashCode]. */
    override fun equals(other: Any?): Boolean = other is Uri && other.uriString == uriString

    /** The hash of the canonical [uriString], consistent with [equals]. */
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

    /** Percent-decodes the stored path — an opaque path whole, else each segment — backing [path]. */
    private fun decodedPath(): String =
        when (val storedPath = components.path) {
            is UrlPath.Opaque -> PercentCodec.decode(storedPath.path)
            is UrlPath.Segments -> decodeSegmentsPath(storedPath)
        }

    /** Joins the decoded segments of [storedPath] with `/`, restoring the leading `/` when rooted. */
    private fun decodeSegmentsPath(storedPath: UrlPath.Segments): String {
        val decoded = storedPath.segments.joinToString(SLASH) { PercentCodec.decode(it) }
        return when {
            storedPath.segments.isEmpty() -> ""
            storedPath.rooted -> SLASH + decoded
            else -> decoded
        }
    }

    /** True for the RFC 3986 opaque shape: an absolute URI with no authority and a rootless path. */
    private fun hasRootlessSchemePath(): Boolean {
        if (scheme == null || components.host != null) return false
        val encoded = encodedPath
        return encoded.isNotEmpty() && !encoded.startsWith(SLASH)
    }

    /** Parse factories for [Uri] (SPEC §7.5); each returns a value rather than throwing. */
    public companion object {
        /**
         * Parses [input] as an RFC 3986 URI-reference, preserve-by-default.
         *
         * A relative reference (no scheme) is accepted, deferring base merging to [resolve].
         *
         * @param input the URI-reference text to parse, used exactly as supplied.
         * @param options the opt-in parsing configuration ([ParseOptions.DEFAULT] applies the
         *   standards baseline); enables features such as RFC 6874 zone ids.
         * @return [ParseResult.Ok] with the [Uri], or [ParseResult.Err] on the first fatal error.
         */
        @JvmStatic
        @JvmOverloads
        public fun parse(
            input: String,
            options: ParseOptions = ParseOptions.DEFAULT,
        ): ParseResult<Uri> = UriParser.parse(input, options).map { Uri(it) }

        /**
         * Parses [input], punning a failure to `null`.
         *
         * @param input the URI-reference text to parse.
         * @param options the opt-in parsing configuration ([ParseOptions.DEFAULT] applies the
         *   standards baseline).
         * @return the parsed [Uri], or `null` when [input] is not a valid URI-reference.
         */
        @JvmStatic
        @JvmOverloads
        public fun parseOrNull(
            input: String,
            options: ParseOptions = ParseOptions.DEFAULT,
        ): Uri? = parse(input, options).getOrNull()

        /**
         * Parses [input], throwing when it is not a valid URI-reference.
         *
         * The throwing counterpart of [parse]/[parseOrNull], for a call site that prefers an
         * exception to a [ParseResult] branch; the thrown [UriSyntaxException.error] is the same
         * structured error [parse] would report.
         *
         * @param input the URI-reference text to parse.
         * @param options the opt-in parsing configuration ([ParseOptions.DEFAULT] applies the
         *   standards baseline).
         * @return the parsed [Uri].
         * @throws UriSyntaxException when [input] is not a valid URI-reference.
         */
        @JvmStatic
        @JvmOverloads
        public fun parseOrThrow(
            input: String,
            options: ParseOptions = ParseOptions.DEFAULT,
        ): Uri = parse(input, options).getOrThrow()

        /**
         * Reports whether [input] parses as a URI-reference without retaining the value.
         *
         * @param input the URI-reference text to test.
         * @param options the opt-in parsing configuration ([ParseOptions.DEFAULT] applies the
         *   standards baseline).
         * @return `true` iff [parse] would succeed for [input].
         */
        @JvmStatic
        @JvmOverloads
        public fun canParse(
            input: String,
            options: ParseOptions = ParseOptions.DEFAULT,
        ): Boolean = parse(input, options).isOk()
    }

    /**
     * A mutable, Java-constructible (`new Uri.Builder()`) assembler for a [Uri].
     *
     * Setters are fluent and accumulate component state; [build] recomposes that state into a URI
     * string and re-parses it through the `Uri`-profile engine. Recomposition applies the RFC 3986
     * §3.3/§4.2 guards (a `./` before a colon-bearing first segment, a `/.` before a `//`-leading
     * authority-less path), so no component the caller set is silently reinterpreted, and it fails
     * fast with [IllegalArgumentException] on unrepresentable combinations — a host-less [userInfo]
     * or [port], or an authority paired with a non-rooted path. The produced [Uri] is therefore
     * always a valid, canonical value. Use [Uri.newBuilder] for a pre-filled builder.
     */
    @Suppress("TooManyFunctions") // One setter per RFC 3986 component; each is a one-liner.
    public class Builder {
        private var scheme: String? = null
        private var userInfo: String? = null
        private var host: String? = null
        private var port: Int? = null
        private var encodedPath: String = ""

        /** True while [encodedPath] was assembled from empty by segment appends; its rooting is decided at [build]. */
        private var isSegmentBuiltPath: Boolean = false
        private var query: String? = null
        private var fragment: String? = null
        private var options: ParseOptions = ParseOptions.DEFAULT

        /** Creates an empty builder; set at least a [host] or a non-empty [encodedPath] before [build]. */
        public constructor()

        /** Creates a builder pre-filled from [source] so `source.newBuilder().build()` reproduces it. */
        internal constructor(source: Uri) {
            scheme = source.scheme
            userInfo = source.userInfo
            host = source.hostName
            port = source.port
            encodedPath = source.encodedPath
            query = source.query
            fragment = source.fragment
            options = roundTripOptions(source.components.host)
        }

        /**
         * Sets or clears the scheme (without its `:`); the original case is preserved at [build].
         *
         * @param scheme a syntactically valid scheme, or `null` to make this a relative reference.
         * @throws IllegalArgumentException when a non-null [scheme] is not a valid scheme.
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
         * Sets the host from a structured [Host]; its canonical [asText][Host.asText] is used and is
         * re-validated and canonicalized by the host pipeline at [build].
         *
         * A convenience over `host(host.asText())` for a host obtained from another value; because
         * [build] re-parses, the host is re-canonicalized, so an equal [Host] yields an equal result.
         *
         * @param host the structured host to set.
         * @return this builder, for chaining.
         */
        public fun host(host: Host): Builder = host(host.asText())

        /**
         * Enables RFC 6874 IPv6 zone-id acceptance when [build] re-parses the assembled URI (default
         * off); the builder counterpart of [ParseOptions.allowIpv6ZoneId].
         *
         * Set this `true` to assemble a `[` IPv6 `%25` ZoneID `]` host from scratch; with it off,
         * [build] rejects a zone id exactly as [parse] does. A builder from [newBuilder] already
         * carries forward the source value's setting, so a parsed zoned value round-trips without it.
         * Zone ids are a `Uri`-profile feature: a [Url] has no such option and always rejects them.
         *
         * @param allow `true` to accept a `%25`-introduced zone id at [build], `false` (default) to reject it.
         * @return this builder, for chaining.
         */
        public fun allowIpv6ZoneId(allow: Boolean): Builder {
            options = options.newBuilder().allowIpv6ZoneId(allow).build()
            return this
        }

        /**
         * Sets or clears the explicit port; the `Uri` profile applies no `0..65535` cap.
         *
         * @param port a non-negative port, or `null` to elide it.
         * @throws IllegalArgumentException when [port] is negative.
         */
        public fun port(port: Int?): Builder {
            require(port == null || port >= 0) { "port must be non-negative: $port" }
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
         * Replaces the entire path with an already-encoded path verbatim; an alias of [encodedPath].
         *
         * This does NOT percent-encode: [path] is taken as the raw encoded path, so this is the
         * *encoded* setter even though [Uri.path] is the *decoded* projection. To supply decoded data,
         * build the path from [addPathSegment]/[addPathSegments], which encode each segment.
         *
         * @param path the already-encoded path (e.g. `/a/b`); validated at [build].
         * @return this builder, for chaining.
         */
        public fun path(path: String): Builder = encodedPath(path)

        /**
         * Appends one decoded path segment, percent-encoding it (including any `/`, `\`, `%`, `?`, or
         * `#`) so it always stays a single RFC 3986 segment and [Uri.pathSegments] decodes it back to
         * exactly [segment] (SPEC [PATH-6]).
         *
         * Segments join at the path's segment boundary: a path that is empty or already ends in `/`
         * has an open final slot the segment fills directly, and any other path first gains a
         * separating `/` — so `newBuilder()` of `http://h/` plus `"x"` builds `http://h/x`, matching
         * OkHttp's replace-trailing-empty rule (SPEC [PATH-3]). Pushing `""` leaves the final slot
         * open: on a path that already has content it is a real trailing empty segment (`/a/`, so
         * [Uri.pathSegments] is `["a", ""]`); on a still-empty path it leaves the path empty, so a
         * lone `addPathSegment("")` serializes as the root `/` under an authority and contributes
         * nothing without one. An interior empty segment (`/a//b`) is expressible only through
         * [encodedPath]. A path assembled purely from segments stays rootless until [build], which
         * prepends the root `/` iff the value has an authority (RFC 3986 §3.3) — so rootless
         * `urn:`/`mailto:` paths build segment-wise, independent of setter order.
         *
         * @param segment the decoded segment to append.
         * @return this builder, for chaining.
         */
        public fun addPathSegment(segment: String): Builder =
            pushSegment(PercentCodec.encode(segment, URI_PATH_SEGMENT_ENCODE_SET))

        /**
         * Appends one already-encoded path segment verbatim (RFC 3986 §3.3).
         *
         * The caller owns the encoding: percent-escapes are kept exactly as supplied and validated by
         * the strict parser at [build]. Because a segment is `*pchar`, [segment] must not carry a raw
         * `/`, `\`, `?`, or `#` — those are component delimiters, not segment data, and would
         * otherwise silently re-split the built value (SPEC [MODEL-39]); use [addPathSegment] to carry
         * them as data. Separator and rooting rules are those of [addPathSegment]: the segment joins
         * at the path's segment boundary, and a path built purely from segments is rooted at [build]
         * iff the value has an authority.
         *
         * @param segment the encoded segment to append; kept exactly as supplied.
         * @return this builder, for chaining.
         * @throws IllegalArgumentException when [segment] contains a raw `/`, `\`, `?`, or `#`.
         */
        public fun addEncodedPathSegment(segment: String): Builder = pushSegment(segment)

        /**
         * Appends each `/`-separated part of [pathSegments] as a decoded segment (OkHttp
         * `HttpUrl.Builder.addPathSegments`).
         *
         * [pathSegments] is split on `/`, and each part is appended via [addPathSegment] — so every
         * part is percent-encoded (a raw `?`, `#`, `\`, or `%` in a part becomes data, not a
         * delimiter) and the trailing-slash and rooting rules of [addPathSegment] apply. A trailing
         * `/` therefore yields a trailing empty segment (`"a/b/"` -> `["a", "b", ""]`).
         *
         * @param pathSegments the `/`-separated decoded path to append (e.g. `"a/b/c"`).
         * @return this builder, for chaining.
         */
        public fun addPathSegments(pathSegments: String): Builder {
            val parts = pathSegments.split(SLASH)
            check(parts.isNotEmpty()) { "splitting on '/' always yields at least one part" }
            for (segment in parts) {
                addPathSegment(segment)
            }
            return this
        }

        /**
         * Replaces the path segment at [index] with the decoded [segment], percent-encoding it
         * (RFC 3986 §3.3).
         *
         * [segment] is encoded exactly as [addPathSegment] encodes an appended segment, so any `/`,
         * `\`, `?`, `#`, or `%` it holds becomes data rather than a delimiter. The path's absolute
         * versus rootless shape is preserved.
         *
         * @param index the zero-based segment position in the current path.
         * @param segment the decoded replacement segment.
         * @return this builder, for chaining.
         * @throws IllegalArgumentException when [index] is negative or `>=` the current segment count.
         */
        public fun setPathSegment(
            index: Int,
            segment: String,
        ): Builder = replaceSegments(index) { it[index] = PercentCodec.encode(segment, URI_PATH_SEGMENT_ENCODE_SET) }

        /**
         * Removes the path segment at [index], preserving the path's absolute versus rootless shape
         * (RFC 3986 §3.3).
         *
         * @param index the zero-based segment position to remove.
         * @return this builder, for chaining.
         * @throws IllegalArgumentException when [index] is negative or `>=` the current segment count.
         */
        public fun removePathSegment(index: Int): Builder = replaceSegments(index) { it.removeAt(index) }

        /**
         * Sets or clears the raw encoded query (without its leading `?`).
         *
         * @param query the encoded query, or `null` to drop the `?` entirely.
         */
        public fun query(query: String?): Builder {
            this.query = query
            return this
        }

        /**
         * Replace-first/remove-rest sets the query parameter [name] to [value] (SPEC §10.3.2).
         *
         * Parses the current [query] into [QueryParameters], applies `set` (replacing the first pair
         * named [name], dropping later ones, or appending when absent), and writes the re-serialized
         * query back. An empty result yields a present-but-empty `""` query, not `null`.
         *
         * @param name the decoded parameter name.
         * @param value the decoded parameter value, or `null` for a name with no `=`.
         * @return this builder, for chaining.
         */
        public fun setQueryParameter(
            name: String,
            value: String?,
        ): Builder =
            query(
                currentQueryParameters()
                    .newBuilder()
                    .set(name, value)
                    .build()
                    .toQueryString(),
            )

        /**
         * Appends the query parameter [name]=[value] without deduplicating (SPEC §10.3.2).
         *
         * Parses the current [query], appends the pair, and writes the re-serialized query back,
         * preserving any existing pair with the same [name].
         *
         * @param name the decoded parameter name.
         * @param value the decoded parameter value, or `null` for a name with no `=`.
         * @return this builder, for chaining.
         */
        public fun addQueryParameter(
            name: String,
            value: String?,
        ): Builder =
            query(
                currentQueryParameters()
                    .newBuilder()
                    .add(name, value)
                    .build()
                    .toQueryString(),
            )

        /**
         * Removes every query parameter named [name], preserving the order of the rest (SPEC §10.3.2).
         *
         * A no-op when no pair matches. Removing the last pair yields a present-but-empty `""` query,
         * not `null`; clear the `?` entirely with `query(null)`.
         *
         * @param name the decoded parameter name whose pairs are removed.
         * @return this builder, for chaining.
         */
        public fun removeAllQueryParameters(name: String): Builder =
            query(
                currentQueryParameters()
                    .newBuilder()
                    .removeAll(name)
                    .build()
                    .toQueryString(),
            )

        /**
         * Sets or clears the raw encoded fragment (without its leading `#`).
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
         * @throws IllegalArgumentException when the components cannot be represented as an RFC 3986
         *   URI: a [userInfo] or [port] set without a [host], or an authority paired with a
         *   non-rooted [encodedPath].
         * @throws UriSyntaxException when the components do not form a valid URI — a builder misuse is
         *   a programmer error rather than a recoverable parse failure.
         */
        public fun build(): Uri {
            val path = effectivePath()
            validateComposable(path)
            return UriParser.parse(recompose(path), options).fold(
                onOk = { Uri(it) },
                onErr = { throw UriSyntaxException(it) },
            )
        }

        /**
         * Recomposes the accumulated components into a [Uri], returning `null` instead of throwing on
         * any invalid combination.
         *
         * The non-throwing sibling of [build]: it runs the same RFC 3986 recomposition and re-parse
         * but yields `null` for every failure [build] would raise — an unrepresentable component
         * combination (a host-less [userInfo] or [port], or an authority with a rootless path) or a
         * parse error — so a caller assembling untrusted input needs no `try`/`catch`.
         *
         * @return the assembled [Uri], or `null` when the components cannot form a valid URI.
         */
        public fun buildOrNull(): Uri? {
            val path = effectivePath()
            if (!isComposable(path)) return null
            return UriParser.parse(recompose(path), options).getOrNull()?.let { Uri(it) }
        }

        /**
         * Rejects component combinations no RFC 3986 recomposition can represent (RFC 3986 §3.2/§3.3).
         *
         * `userinfo`/`port` are authority sub-components, so they require a host; a caller wanting an
         * empty authority passes `host("")`. A present authority forbids a rootless [path], which
         * would otherwise merge into the authority on re-parse; a segment-built path is already rooted
         * by [effectivePath] when a host is present, so only a verbatim rootless path can trip this.
         */
        private fun validateComposable(path: String) {
            require(authorityHasHost()) {
                "userInfo/port require a host: set host(\"\") for an empty-authority URI, or drop them"
            }
            require(pathFitsAuthority(path)) {
                "a path with an authority must be empty or start with '/': $path"
            }
        }

        /** True iff [build] would pass [validateComposable] for [path] without throwing; for [buildOrNull]. */
        private fun isComposable(path: String): Boolean = authorityHasHost() && pathFitsAuthority(path)

        /** True when a [host] is present, or neither [userInfo] nor [port] (which require one) is set. */
        private fun authorityHasHost(): Boolean = host != null || (userInfo.isNullOrEmpty() && port == null)

        /** True when [path] fits beside the current authority: no host, or an empty or rooted path. */
        private fun pathFitsAuthority(path: String): Boolean = host == null || path.isEmpty() || path.startsWith(SLASH)

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
         * Splits [encodedPath] into segments, applies [edit] to the mutable list at the bounds-checked
         * [index], and stores the re-joined path; the absolute versus rootless shape is preserved so a
         * segment-built rootless path stays rootless (and is still rooted at [build] under a host).
         */
        private fun replaceSegments(
            index: Int,
            edit: (MutableList<String>) -> Unit,
        ): Builder {
            val current = splitUriPath(encodedPath)
            val segments = current.segments.toMutableList()
            require(index in segments.indices) {
                "path segment index out of bounds: $index (size ${segments.size})"
            }
            edit(segments)
            encodedPath = UrlPath.Segments(segments, current.rooted).toUriPathString()
            return this
        }

        /** The current [query] parsed to [QueryParameters], or an empty snapshot when the query is absent. */
        private fun currentQueryParameters(): QueryParameters =
            query?.let { QueryParameters.parse(it) } ?: QueryParameters(emptyList())

        /**
         * The path [recompose] serializes: a segment-built path gains its root `/` exactly when an
         * authority is present, because RFC 3986 §3.3 forbids a rootless path after an authority while
         * the rootless form is the only one an authority-less `urn:`-style value can carry.
         */
        private fun effectivePath(): String {
            if (!isSegmentBuiltPath || host == null) return encodedPath
            check(!encodedPath.startsWith(SLASH)) { "a segment-built path is stored rootless: $encodedPath" }
            return SLASH + encodedPath
        }

        /** Recomposes a parseable URI string from the accumulated components (RFC 3986 §5.3). */
        private fun recompose(path: String): String {
            val sb = StringBuilder()
            if (scheme != null) sb.append(scheme).append(':')
            if (host != null) appendAuthority(sb)
            sb.append(guardRecomposedUriPath(scheme, host != null, path))
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
