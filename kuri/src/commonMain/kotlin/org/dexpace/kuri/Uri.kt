/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri

import org.dexpace.kuri.error.ParseResult
import org.dexpace.kuri.error.UriSyntaxException
import org.dexpace.kuri.error.map
import org.dexpace.kuri.host.Host
import org.dexpace.kuri.parser.BuilderPath
import org.dexpace.kuri.parser.ComponentPath
import org.dexpace.kuri.parser.ParsedComponents
import org.dexpace.kuri.parser.Resolver
import org.dexpace.kuri.parser.UriParser
import org.dexpace.kuri.parser.decodedSegments
import org.dexpace.kuri.parser.fileExtensionOf
import org.dexpace.kuri.parser.fileNameOf
import org.dexpace.kuri.parser.isDirectoryPath
import org.dexpace.kuri.parser.toUriPathString
import org.dexpace.kuri.percent.PercentCodec
import org.dexpace.kuri.percent.PercentEncodeSet
import org.dexpace.kuri.percent.PercentEncodeSets
import org.dexpace.kuri.query.QueryParameters
import org.dexpace.kuri.query.QueryParametersBuilder
import org.dexpace.kuri.query.QueryState
import org.dexpace.kuri.query.applyParameterEdit
import org.dexpace.kuri.scheme.Scheme
import org.dexpace.kuri.serialize.UriNormalizer
import org.dexpace.kuri.serialize.UriSerializer
import org.dexpace.kuri.serialize.guardRecomposedUriPath
import kotlin.jvm.JvmName
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

/** The path-segment separator shared by the encoded-path projection (RFC 3986 §3.3). */
private const val SLASH: String = "/"

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
        get() = components.host?.asText()

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
        get() = decodedPath

    /** The decoded path segments in order (read-only); an opaque path yields its single decoded value. */
    @get:JvmName("pathSegments")
    public val pathSegments: List<String>
        get() = decodedPathSegments

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

    /** The decoded fragment (percent-decoded [fragment]), or `null` when no `#` was present. */
    @get:JvmName("decodedFragment")
    public val decodedFragment: String?
        get() = decodedFragmentValue

    /** The `[userinfo@]host[:port]` authority, or `null` when the URI has no authority. */
    @get:JvmName("authority")
    public val authority: String?
        get() = reconstructAuthority()

    /**
     * A decoded, immutable snapshot of this URI's query `name=value` pairs; never a live view.
     *
     * Parses [query] with generic query decoding (`+` kept literal). Both an absent query
     * (`query == null`) and a present-but-empty query (`query == ""`) yield an empty snapshot — as
     * WHATWG's `URLSearchParams` yields no pairs for an empty query string. The absent-vs-present
     * distinction is preserved on the raw [query] string, not in this decoded pair view.
     *
     * @return the decoded, ordered, duplicate-preserving snapshot; empty when there is no query.
     */
    public fun queryParameters(): QueryParameters = queryParameterSnapshot

    /**
     * The last non-empty decoded path segment — the "file name" — or `""` when the path has none.
     *
     * Trailing empty segments (a trailing `/`) are skipped, so both `/a/b` and `/a/b/` return `"b"`;
     * a root-only or empty path returns `""`. An opaque path (`mailto:`, `urn:`, etc.; see
     * [isOpaquePath]) has no hierarchical file name, so it too returns `""`.
     *
     * Segments are decoded, so `/a/c%20d` returns `"c d"`. Because the segment is percent-decoded, a
     * source segment holding an encoded `/` (`%2F`) yields a name containing a literal `/` — e.g.
     * `/docs/a%2Fb.txt` returns `"a/b.txt"` — so a caller must not use the result directly as a
     * filesystem name without its own sanitization.
     *
     * @return the last non-empty decoded segment, or `""` when there is none or the path is opaque.
     */
    public fun fileName(): String = if (isOpaquePath()) "" else fileNameOf(pathSegments)

    /**
     * The file extension of [fileName]: the substring after its last `.`, or `""` when it has none.
     *
     * Returns `""` when [fileName] has no `.`, ends in `.` (a trailing dot, e.g. `"file."`), or has
     * only a leading `.` (a dotfile, e.g. `".bashrc"`); `"archive.tar.gz"` yields `"gz"`.
     *
     * @return the extension after the last interior `.` of [fileName], or `""` when there is none.
     */
    public fun fileExtension(): String = fileExtensionOf(fileName())

    /** Cached canonical-but-unnormalized serialization, computed once. */
    private val canonicalUri: String by lazy { UriSerializer.serialize(components) }

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
        get() = encodedPathText

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
     * The inverse of [resolve]: when this URI and [target] share the same [scheme] and [authority]
     * (compared exactly) and neither has an opaque path (see [isOpaquePath]), the result is a relative
     * reference — the target's path suffix below this URI's directory, plus its query and fragment —
     * for which `this.resolve(result)` reproduces [target]. RFC 3986 §5.2.3 merges a rootless reference
     * onto this URI's *directory* (everything up to its last `/`), so that directory, not the full base
     * path, is what the suffix is measured against — `http://h/a/b` relativizes `http://h/a/b/c` to
     * `b/c`, since resolving `c` alone would merge onto `/a/` and miss.
     *
     * The contract is enforced, not merely intended: the candidate is resolved back against this URI
     * and compared to [target], and returned only when it round-trips. The result is `null` when no
     * relative form resolves back to [target] — the two differ in [scheme] or [authority], either side
     * has an opaque path (see [isOpaquePath]), [target] is not under this URI's directory, the only
     * candidate is a case RFC 3986 resolution would re-read (an empty reference re-inherits the base
     * query; a `..`-bearing suffix), the candidate's resolution does not produce a valid URI (dot-segment
     * removal can yield a `//`-leading path that re-reads as an invalid authority), or this URI has no
     * [scheme] (resolution requires an absolute base).
     * This mirrors [Url.relativize], which likewise returns `null` when
     * no relative form exists. This is total: it never throws.
     *
     * @param target the URI to express relative to this one.
     * @return the relative-reference [Uri] that resolves to [target], or `null` when no relative form
     *   reproduces it.
     */
    public fun relativize(target: Uri): Uri? {
        // relativize inverts resolve, which requires an absolute base; a scheme-less base has no relative
        // form and would trip the structured resolver's absolute-base precondition, so reject it up front.
        if (scheme == null) return null
        val candidate = relativeReference(target)
        // Resolve the already-parsed candidate through the structured resolver; a candidate whose
        // resolution does not yield a valid URI (dot-segment removal can produce a //-leading path that
        // re-reads as an invalid authority) means no relative form exists, so fold that failure to null
        // rather than letting the resolver's internal parse error surface — relativize stays total.
        val resolved = candidate?.let { Resolver.resolve(components, it.components).getOrNull() }
        return candidate?.takeIf { resolved != null && Uri(resolved) == target }
    }

    /**
     * Builds a candidate relative reference to [target] (path suffix, query, and fragment), or `null`
     * when the two cannot share a hierarchy (an opaque path on either side, a differing
     * scheme/authority, or a [target] not under this URI's directory). Shared by [relativize] and
     * [Url.relativize]; each caller verifies the candidate resolves back to [target] under its own
     * profile's resolve before returning it.
     */
    internal fun relativeReference(target: Uri): Uri? {
        val sharesHierarchy =
            !isOpaquePath() && !target.isOpaquePath() && scheme == target.scheme && authority == target.authority
        if (!sharesHierarchy) return null
        return relativePathTo(encodedPath, target.encodedPath)?.let { relativePath ->
            Builder()
                .encodedPath(relativePath)
                .query(target.query)
                .fragment(target.fragment)
                .buildOrNull()
        }
    }

    /**
     * The rootless path reference RFC 3986 §5.2.3 resolution merges back onto [basePath]'s directory to
     * yield [targetPath], or `null` when [targetPath] does not lie under that directory.
     *
     * The merge directory is [basePath] up to and including its last `/`; the reference is [targetPath]
     * with that prefix removed, or `"."` when [targetPath] *is* the directory — an empty suffix would
     * instead re-inherit the base query on resolution, so it cannot stand for the directory itself.
     */
    private fun relativePathTo(
        basePath: String,
        targetPath: String,
    ): String? {
        val baseDir = basePath.substring(0, basePath.lastIndexOf(SLASH) + 1)
        return when {
            targetPath == baseDir -> "."
            targetPath.startsWith(baseDir) -> targetPath.substring(baseDir.length)
            else -> null
        }
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
    public fun isOpaquePath(): Boolean = components.path is ComponentPath.Opaque || hasRootlessSchemePath()

    /**
     * The port a consumer should connect to: the explicit [port], else this scheme's default.
     *
     * Falls back to the scheme's registered default port (e.g. `80` for `http`, `443` for `https`)
     * when no [port] is stated, and to `null` when the port is neither stated nor defaulted — a
     * scheme-less reference, or a scheme with no default such as `mailto` or `file`. [Url.effectivePort]
     * reports the "no port" case the same way, as `null`.
     *
     * @return the stated port, else the scheme default, else `null` when neither applies.
     */
    public fun effectivePort(): Int? = port ?: scheme?.let { Scheme.defaultPort(it) }

    /**
     * Returns a copy of this URI with its [port] set to [port] (or elided when `null`).
     *
     * A thin [newBuilder] rebuild; every other component is preserved. A value obtained from [parse]
     * always rebuilds, so this does not throw for such a value. When the URI has no authority (no host
     * — e.g. `mailto:` or another opaque-path URI), a port has nowhere to attach, so the receiver is
     * returned unchanged rather than raising — the same lenient rule [Url.withPort] follows.
     *
     * @param port a non-negative port, or `null` to elide it.
     * @return a new `Uri` with the requested port, or this URI unchanged when it has no authority.
     * @throws IllegalArgumentException when [port] is negative and the URI has an authority.
     */
    public fun withPort(port: Int?): Uri {
        if (authority == null) return this
        return newBuilder().port(port).build()
    }

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

    /**
     * Returns a copy of this URI with its userinfo, query, and fragment removed, leaving the
     * [scheme], [host], [port], and [path] intact (SPEC [CONF-120]).
     *
     * A convenience for logging or telemetry: it strips exactly the three components RFC 3986
     * treats as sensitive or context-dependent (credentials, the query string, and the fragment)
     * while every other component — including a userinfo-less authority — is preserved verbatim.
     * A URI that already carries none of the three is returned equal in value (though [newBuilder]
     * always rebuilds, so the result is not necessarily the same reference).
     *
     * @return a new `Uri` with no userinfo, query, or fragment.
     */
    public fun redact(): Uri =
        newBuilder()
            .userInfo(null)
            .query(null)
            .fragment(null)
            .build()

    /**
     * Reports whether this URI's path denotes a directory — its [encodedPath] ends in `/` (SPEC
     * [PATH-3], [CONF-85]).
     *
     * True for a trailing-slash path such as `/a/` and for the root path `/` (a single empty
     * segment); `false` for a path with content after its last segment (`/a`) and for a wholly
     * empty path, which has no trailing slash to report. [hasTrailingSlash] is an exact alias, for
     * a call site that prefers the WHATWG "trailing slash" phrasing over the filesystem-style
     * "directory" term.
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
        return "$credentials${authorityHost.asText()}$portPart"
    }

    /** Decoded path and segments, computed once each; the value is immutable, mirroring [canonicalUri]. */
    private val decodedPath: String by lazy { computeDecodedPath() }
    private val decodedPathSegments: List<String> by lazy { computeDecodedPathSegments() }

    /** Encoded path and query snapshot, computed once each; both are immutable, mirroring [canonicalUri]. */
    private val encodedPathText: String by lazy { components.path.toUriPathString() }
    private val queryParameterSnapshot: QueryParameters by lazy { QueryParameters.parseOrEmpty(query) }

    /** Decoded fragment backing [decodedFragment], computed once; immutable, mirroring [canonicalUri]. */
    private val decodedFragmentValue: String? by lazy { fragment?.let { PercentCodec.decode(it) } }

    /**
     * Percent-decodes the stored path — an opaque path whole, else each segment — backing [path].
     *
     * Reuses the already-decoded [decodedPathSegments] rather than decoding every segment a second
     * time: an opaque path is that single decoded value, and a segment path rejoins the decoded
     * segments through [toUriPathString] so the empty-vs-root-only and rooted-vs-rootless ordering
     * keeps its single source of truth (ComponentPath.kt). Reading [decodedPathSegments] here is safe —
     * it does not read [decodedPath], so there is no lazy cycle.
     */
    private fun computeDecodedPath(): String =
        when (val storedPath = components.path) {
            is ComponentPath.Opaque -> decodedPathSegments.single()
            is ComponentPath.Segments ->
                ComponentPath.Segments(decodedPathSegments, storedPath.rooted).toUriPathString()
        }

    /** The decoded segments backing [pathSegments]; an opaque path yields its single decoded value. */
    private fun computeDecodedPathSegments(): List<String> =
        decodedSegments(components.path) { PercentCodec.decode(it) }

    /**
     * True for the RFC 3986 opaque shape: an absolute URI with no authority and a rootless path.
     *
     * Reads the structured [ComponentPath.Segments.rooted] flag rather than re-serializing the path and
     * inspecting its first character. A rootless path serializes with a leading `/` only when its first
     * segment is empty, so a non-empty first segment is the same condition as `!startsWith("/")` —
     * without the `O(path)` string build on every [isOpaquePath]/[relativize] call.
     */
    private fun hasRootlessSchemePath(): Boolean {
        if (scheme == null || components.host != null) return false
        val storedPath = components.path
        return storedPath is ComponentPath.Segments &&
            !storedPath.rooted &&
            storedPath.segments.firstOrNull()?.isNotEmpty() == true
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
     * fast with [IllegalArgumentException] on unrepresentable combinations — a host-less [userInfo],
     * [username], [password], or [port], or an authority paired with a non-rooted path. The produced
     * [Uri] is therefore always a valid, canonical value. Use [Uri.newBuilder] for a pre-filled builder.
     */
    @Suppress("TooManyFunctions") // One setter per RFC 3986 component; each is a one-liner.
    public class Builder {
        private var scheme: String? = null
        private var userInfo: String? = null
        private var encodedUsername: String = ""
        private var encodedPassword: String = ""
        private var usesSplitUserInfo: Boolean = false
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
            path = BuilderPath.verbatim(source.encodedPath)
            queryState = QueryState.Raw(source.query)
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
         * Switches this builder back to verbatim mode: a [username]/[password] pair set earlier is
         * discarded from the built authority in favour of [userInfo], matching last-setter-wins. The
         * discarded pair is also cleared from the split-mode state, so a later [username] or
         * [password] call made alone (without re-setting the other) starts from `""` rather than
         * leaking the value this call just discarded.
         *
         * @param userInfo the encoded userinfo (e.g. `user` or `user:password`), or `null` to clear it.
         */
        public fun userInfo(userInfo: String?): Builder {
            this.userInfo = userInfo
            usesSplitUserInfo = false
            encodedUsername = ""
            encodedPassword = ""
            return this
        }

        /**
         * Sets the userinfo username, percent-encoding it under the userinfo set.
         *
         * Switches this builder to split userinfo mode: the username/password pair accumulated
         * through this and [password] takes priority at [build] over a verbatim [userInfo] set
         * earlier, and the two are joined with `:` — an empty password collapses to no colon,
         * matching [Uri.userInfo]'s own reconstruction rule. Call [userInfo] again to switch back
         * to verbatim mode.
         *
         * A literal `%` in [username] is escaped to `%25` first, since the userinfo set does not
         * reserve `%` itself — see [PercentCodec.escapeLiteralPercent] for the rationale.
         * [Url.Builder.username] applies the same escape for the analogous `Url`-profile setter.
         *
         * @param username the decoded username; `""` clears the username.
         * @return this builder, for chaining.
         */
        public fun username(username: String): Builder {
            val escaped = PercentCodec.escapeLiteralPercent(username)
            encodedUsername = PercentCodec.encode(escaped, PercentEncodeSets.USERINFO)
            usesSplitUserInfo = true
            return this
        }

        /**
         * Sets the userinfo password, percent-encoding it under the userinfo set.
         *
         * As [username], switches this builder to split userinfo mode; see its doc for the
         * priority and combination rules the two setters share, and for the literal-`%` escape
         * this setter also applies.
         *
         * @param password the decoded password; `""` clears the password.
         * @return this builder, for chaining.
         */
        public fun password(password: String): Builder {
            val escaped = PercentCodec.escapeLiteralPercent(password)
            encodedPassword = PercentCodec.encode(escaped, PercentEncodeSets.USERINFO)
            usesSplitUserInfo = true
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
         * **Zone ids:** a [Host.Ipv6] that carries a zone id (e.g. `fe80::1%eth0`, obtained by parsing
         * with [ParseOptions.allowIpv6ZoneId]) re-parses only when this builder also has zone ids
         * enabled. A builder from [newBuilder] carries the source value's setting forward, but a *fresh*
         * builder rejects zone ids by default, so pair a zoned host with [allowIpv6ZoneId]`(true)` or
         * [build] raises `UriSyntaxException`. A [Url] never accepts a zone id at all.
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
         * This does NOT percent-encode: [encodedPath] is taken as the raw path (so a `?`, `#`, or `%`
         * in it is a delimiter or an escape, not data), which is why [Uri.path] — the *decoded*
         * projection — is not its inverse. To supply decoded data, build the path from
         * [addPathSegment]/[addPathSegments], which encode each segment.
         *
         * @param encodedPath the already-encoded path (e.g. `/a/b`); validated at [build].
         * @return this builder, for chaining.
         */
        public fun encodedPath(encodedPath: String): Builder {
            path = BuilderPath.verbatim(encodedPath)
            return this
        }

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
        public fun addPathSegment(segment: String): Builder {
            path = path.pushSegment(PercentCodec.encode(segment, URI_PATH_SEGMENT_ENCODE_SET))
            return this
        }

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
        public fun addEncodedPathSegment(segment: String): Builder {
            path = path.pushSegment(segment)
            return this
        }

        /**
         * Appends each `/`-separated part of [pathSegments] as a decoded segment (OkHttp
         * `HttpUrl.Builder.addPathSegments`).
         *
         * Every `/` delimits a segment, so each part is percent-encoded (a raw `?`, `#`, `\`, or `%`
         * in a part becomes data, not a delimiter) and every empty part is preserved as a genuine
         * empty segment: an interior or doubled `/` yields one (`"a//b"` -> `["a", "", "b"]`) and a
         * trailing `/` yields a trailing empty (`"a/b/"` -> `["a", "b", ""]`). Appending onto a
         * directory-style path (one ending in `/`) fills that trailing slot rather than doubling it,
         * so `newBuilder()` of `http://h/x/` plus `"a"` builds `http://h/x/a`. When a path is built
         * from scratch a leading `/` is ignored, since a rootless path cannot begin with an empty
         * segment; the root `/` is still added at [build] iff the value has an authority.
         *
         * @param pathSegments the `/`-separated decoded path to append (e.g. `"a/b/c"`).
         * @return this builder, for chaining.
         */
        public fun addPathSegments(pathSegments: String): Builder {
            path = path.addSegments(pathSegments) { PercentCodec.encode(it, URI_PATH_SEGMENT_ENCODE_SET) }
            return this
        }

        /**
         * Replaces the path segment at [index] with the decoded [segment], percent-encoding it
         * (RFC 3986 §3.3).
         *
         * [segment] is encoded exactly as [addPathSegment] encodes an appended segment, so any `/`,
         * `\`, `?`, `#`, or `%` it holds becomes data rather than a delimiter. The path's absolute
         * versus rootless shape is preserved: an edit that would empty a rootless path's first
         * segment — re-rooting it into an unrepresentable shape — is rejected at [build] ([build]
         * throws, [buildOrNull] returns `null`) rather than silently changing the path.
         *
         * @param index the zero-based segment position in the current path.
         * @param segment the decoded replacement segment.
         * @return this builder, for chaining.
         * @throws IndexOutOfBoundsException when [index] is negative or `>=` the current segment count.
         */
        public fun setPathSegment(
            index: Int,
            segment: String,
        ): Builder {
            path = path.setSegment(index, PercentCodec.encode(segment, URI_PATH_SEGMENT_ENCODE_SET))
            return this
        }

        /**
         * Removes the path segment at [index], preserving the path's absolute versus rootless shape
         * (RFC 3986 §3.3).
         *
         * As with [setPathSegment], an edit that would empty a rootless path's first segment
         * (re-rooting it) is rejected at [build] ([build] throws, [buildOrNull] returns `null`).
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
        ): Builder = editQueryParameters { it.set(name, value) }

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
        ): Builder = editQueryParameters { it.add(name, value) }

        /**
         * Removes every query parameter named [name], preserving the order of the rest (SPEC §10.3.2).
         *
         * A no-op when no pair matches. Removing the last pair yields a present-but-empty `""` query,
         * not `null`; clear the `?` entirely with `query(null)`.
         *
         * @param name the decoded parameter name whose pairs are removed.
         * @return this builder, for chaining.
         */
        public fun removeAllQueryParameters(name: String): Builder = editQueryParameters { it.removeAll(name) }

        /**
         * Applies [edit] to the query parameters and stores the collapsed [QueryState], parsing a
         * verbatim query to a builder once (the `Uri` profile keeps a present-but-empty `?` unless the
         * query was already absent — `emptyBecomesNull = false`).
         */
        private fun editQueryParameters(edit: (QueryParametersBuilder) -> Unit): Builder {
            queryState = queryState.applyParameterEdit(emptyBecomesNull = false, edit)
            return this
        }

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
         *   URI: a [userInfo], [username], [password], or [port] set without a [host], or an authority
         *   paired with a non-rooted [encodedPath].
         * @throws UriSyntaxException when the components do not form a valid URI — a builder misuse is
         *   a programmer error rather than a recoverable parse failure.
         */
        public fun build(): Uri {
            val path = effectivePath()
            composabilityError(path)?.let { throw IllegalArgumentException(it) }
            return buildResult(path).getOrThrow()
        }

        /**
         * Recomposes the accumulated components into a [Uri], returning `null` instead of throwing on
         * any invalid combination.
         *
         * The non-throwing sibling of [build]: it runs the same RFC 3986 recomposition and re-parse
         * but yields `null` for every failure [build] would raise — an unrepresentable component
         * combination (a host-less [userInfo], [username], [password], or [port], or an authority
         * with a rootless path) or a parse error — so a caller assembling untrusted input needs no
         * `try`/`catch`.
         *
         * @return the assembled [Uri], or `null` when the components cannot form a valid URI.
         */
        public fun buildOrNull(): Uri? {
            val path = effectivePath()
            if (composabilityError(path) != null) return null
            return buildResult(path).getOrNull()
        }

        /**
         * Recomposes [path] with the accumulated components and re-parses it through the `Uri` engine.
         *
         * The single recompose→parse projection [build] and [buildOrNull] share. Given a [path] it is
         * total — it never throws — so [buildOrNull] projecting it with [ParseResult.getOrNull] cannot
         * throw once the composability gate has passed, while [build] projects it with
         * [ParseResult.getOrThrow] to surface a parse failure as [UriSyntaxException].
         */
        private fun buildResult(path: String): ParseResult<Uri> =
            UriParser.parse(recompose(path), options).map { Uri(it) }

        /**
         * The RFC 3986 §3.2/§3.3 message for the first component combination no recomposition can
         * represent, or `null` when the accumulated components are composable.
         *
         * The single ordered source of the composability rules [build] and [buildOrNull] share, so a
         * rule cannot drift between the throwing and the non-throwing path. `userinfo` (whether set
         * verbatim via [userInfo] or split via [username]/[password]) and `port` are authority
         * sub-components, so they require a host; a caller wanting an empty authority passes
         * `host("")`. A present authority forbids a rootless [path], which would otherwise merge into
         * the authority on re-parse; a segment-built path is already rooted by [effectivePath] when a
         * host is present, so only a verbatim rootless path can trip that rule.
         */
        private fun composabilityError(path: String): String? =
            when {
                !authorityHasHost() ->
                    "userInfo/username/password/port require a host: set host(\"\") for an empty-authority " +
                        "URI, or drop them"
                !pathFitsAuthority(path) ->
                    "a path with an authority must be empty or start with '/': $path"
                host == null && path.startsWith("//") ->
                    "an authority-less path cannot begin with '//': $path"
                !this.path.wellFormed() ->
                    "a rootless path cannot begin with an empty segment; the edit would re-root it: $path"
                else -> null
            }

        /** True when a [host] is present, or neither the effective userinfo nor [port] (which require one) is set. */
        private fun authorityHasHost(): Boolean = host != null || (effectiveUserInfoIsEmpty() && port == null)

        /** True when [path] fits beside the current authority: no host, or an empty or rooted path. */
        private fun pathFitsAuthority(path: String): Boolean = host == null || path.isEmpty() || path.startsWith(SLASH)

        /**
         * True when the userinfo this builder would emit is empty — whichever representation
         * ([usesSplitUserInfo] or verbatim [userInfo]) is currently active, mirroring the choice
         * [appendAuthority] makes.
         */
        private fun effectiveUserInfoIsEmpty(): Boolean =
            if (usesSplitUserInfo) combinedUserInfo() == null else userInfo.isNullOrEmpty()

        /**
         * Joins the split-mode [encodedUsername]/[encodedPassword] into one `userinfo` string for
         * [appendAuthority], or `null` when both are empty — an empty password collapses to no
         * colon, the same convention [Uri.reconstructUserInfo] applies when reading a parsed value
         * back.
         */
        private fun combinedUserInfo(): String? =
            when {
                encodedUsername.isEmpty() && encodedPassword.isEmpty() -> null
                encodedPassword.isEmpty() -> encodedUsername
                else -> "$encodedUsername:$encodedPassword"
            }

        /** The path [recompose] serializes, resolving the [BuilderPath] rooting against the current authority. */
        private fun effectivePath(): String = path.effectivePath(host != null)

        /** Recomposes a parseable URI string from the accumulated components (RFC 3986 §5.3). */
        private fun recompose(path: String): String {
            val sb = StringBuilder()
            val query = queryState.resolve()
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
            val effectiveUserInfo = if (usesSplitUserInfo) combinedUserInfo() else userInfo
            sb.append(SLASH).append(SLASH)
            if (!effectiveUserInfo.isNullOrEmpty()) sb.append(effectiveUserInfo).append('@')
            sb.append(authorityHost)
            if (port != null) sb.append(':').append(port)
        }
    }
}
