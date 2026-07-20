# kuri — User Guide

The complete usage reference for [kuri](../README.md). The [README](../README.md) covers installation and a
quick start; this page goes deep on every part of the API. For the normative behavior specification, see
[`SPEC.md`](SPEC.md).

## Contents

[Two models](#two-models-one-engine) ·
[Parsing and errors](#parsing-and-errors) ·
[Building and resolving](#building-and-resolving) ·
[Utilities](#utilities) ·
[Recipes](#recipes) ·
[URI templates](#uri-templates) ·
[Kotlin DSL and operators](#kotlin-dsl-and-operators) ·
[Annotation binding (kuri-bind)](#annotation-binding-kuri-bind) ·
[kotlinx.serialization (kuri-serde-kotlinx)](#kotlinxserialization-kuri-serde-kotlinx) ·
[Standards](#standards) ·
[Conformance](#conformance) ·
[Platforms](#platforms) ·
[Building from source](#building-from-source)

## Two models, one engine

kuri exposes two profiles over one parsing engine. The [README](../README.md#two-models) has the short
version — reach for `Url` for web URLs and `Uri` for generic identifiers; this is the full comparison.

|          | `Uri`                                              | `Url`                                 |
|----------|----------------------------------------------------|---------------------------------------|
| Standard | RFC 3986 (RFC 3987-aware)                          | WHATWG URL                            |
| Posture  | preserve the input; normalize on request           | canonicalize eagerly                  |
| Scheme   | any, and may be absent (relative reference)        | always present; special schemes known |
| Host     | reg-name / IPv4 / IPv6 / IP-future                 | IDNA, IPv4 shorthand, opaque hosts    |
| Equality | structural, plus `normalizedEquals()` for RFC §6.2 | on the canonical serialization        |

`Url.toUri()` is near-lossless; `Uri.toUrl()` may fail when a generic URI is not a valid web URL — it
returns a `ParseResult<Url>`, with `toUrlOrNull()` / `toUrlOrThrow()` siblings.

A few accessors differ between the profiles by design; getting them wrong is a common trap:

| Reading                    | `Uri` (RFC 3986)                     | `Url` (WHATWG)                                          |
|----------------------------|--------------------------------------|--------------------------------------------------------|
| `port`                     | preserved verbatim                   | `null` when elided **or** equal to the scheme default  |
| opaque origin              | —                                    | `origin` is the literal string `"null"`; test `hasOpaqueOrigin()` |

Across both, `host` is the structured `Host` ADT — a sealed hierarchy of `RegName`, `Ipv4`, `Ipv6`,
`IpFuture`, `Opaque`, and `Empty` — and `hostName` is its serialized text; to render a `Host` you hold,
call `host.asText()`.

**Zone identifiers (RFC 6874).** IPv6 zone identifiers are off by default and opt-in on the `Uri` profile
only — the `Url` (WHATWG) profile always rejects them. Enable them with a `ParseOptions`:

```kotlin
val options = ParseOptions.Builder().allowIpv6ZoneId(true).build()
val uri = Uri.parse("http://[fe80::1%25eth0]/", options).getOrThrow()
```

**Internationalized identifiers (RFC 3987).** For the `Uri` profile, IRI↔URI conversion is available
through the `Iri` facility — `Iri.toUri(iri)` maps an IRI to its ASCII `Uri` and `Iri.toUnicode(uri)`
renders the Unicode form; the `Url` profile applies host IDNA (UTS #46) by default.

```kotlin
import org.dexpace.kuri.Iri

// toUri returns a ParseResult<Uri>; the mapped Uri is fully ASCII.
val uri = Iri.toUri("http://bücher.example/qué").getOrThrow()
uri.toString()          // host becomes Punycode (xn--…), non-ASCII path bytes percent-encoded
Iri.toUnicode(uri)      // "http://bücher.example/qué" — best-effort Unicode display form
```

## Parsing and errors

Parsing never throws: every `parse` returns a `ParseResult<T>` — errors are values — and you choose
how to consume it.

```kotlin
Url.parse(input)                 // ParseResult<Url>  (Ok or Err)
Url.parseOrNull(input)           // Url?
Url.parseOrThrow(input)          // Url, or throws UriSyntaxException
Url.canParse(input)              // Boolean
```

`ParseResult` is a sealed type, so a `when` over it is exhaustive without an `else`, and the `Err`
branch hands you the structured `UriParseError`:

```kotlin
import org.dexpace.kuri.error.ParseResult

when (val result = Url.parse("https://example.com/")) {
    is ParseResult.Ok -> result.value.hostName    // "example.com"
    is ParseResult.Err -> result.error.message     // human-readable reason
}
```

For a pipeline that doesn't want to branch, `map` transforms a success and `fold` collapses both
branches to a single value:

```kotlin
import org.dexpace.kuri.error.fold
import org.dexpace.kuri.error.map

Url.parse(input).map { it.hostName }                    // ParseResult<String>
Url.parse(input).fold({ it.hostName }, { "invalid" })   // String, either way
```

From Java, let the throwing factory raise `UriSyntaxException` and read the structured `error` off it
(or branch on `result instanceof ParseResult.Err`):

```java
import org.dexpace.kuri.Url;
import org.dexpace.kuri.error.UriSyntaxException;

try {
    Url.parseOrThrow("://no-scheme");   // throws on a malformed input
} catch (UriSyntaxException e) {
    e.getError();     // the structured UriParseError
    e.getMessage();   // its human-readable rendering
}
```

`getOrNull()` punts a failure to `null` from either language when you don't need the reason.

Separately from fatal errors, `Url.validationErrors()` lists the non-fatal WHATWG anomalies a lenient
parse silently repaired — a `\` read as `/`, a stripped tab — for linting or telemetry, never for
control flow (a validation error never downgrades a successful parse). Each is a `kind` +
`at` (offset) record over the `ValidationErrorKind` enum.

**Resource limits.** kuri caps the work any single parse can do, so hostile input can't blow up memory or
recursion. Every limit is a field on `ParseOptions` with a documented default, tunable through the
builder:

```kotlin
val options = ParseOptions.Builder()
    .inputLength(8_192)        // max input characters
    .expandedLength(65_536)    // max length after percent-decoding / IDNA expansion
    .pathSegments(1_024)       // max path segments
    .resolutionDepth(64)       // max reference-resolution recursion
    .build()

Url.parse(untrustedInput, options)
```

When a bound is exceeded the parse fails with a `UriParseError.LimitExceeded`, which reports the
offending `limit` (a `ResourceLimit` — `InputLength`, `ExpandedLength`, `PathSegments`,
`ResolutionDepth`, `HostLabelLength`, `HostTotalLength`, `PortMax`), the `observed` value, and the `max`
it crossed — enough to log precisely why a request was rejected.

## Building and resolving

Values are immutable. A `Builder` produces new ones, and `newBuilder()` returns a builder pre-filled
from an existing value, so a **parse → modify → build** round-trip is clean:

```kotlin
val url = Url.parseOrThrow("https://example.com/v1/users?page=1")
    .newBuilder()
    .addPathSegment("42")
    .setQueryParameter("page", "2")
    .build()                                  // https://example.com/v1/users/42?page=2
```

`build()` **throws** (`UriSyntaxException` or `IllegalArgumentException`) when the assembled components
can't form a valid value — a special-scheme `Url` with no host, say. `buildOrNull()` is the
non-throwing sibling for untrusted input:

```kotlin
Url.Builder().scheme("https").host("example.com").buildOrNull()  // https://example.com/  (a Url)
Url.Builder().scheme("https").buildOrNull()                      // null — a special scheme needs a host
```

The same from Java — construct the builder with `new`, chain setters, and call `build()`:

```java
Url url = new Url.Builder()
    .scheme("https")
    .host("example.com")
    .addPathSegment("v1")
    .addPathSegment("users")
    .setQueryParameter("page", "2")
    .build();                                 // https://example.com/v1/users?page=2
```

`resolve` applies a reference to a base (RFC 3986 §5.2 / WHATWG); `resolveOrThrow` and `resolveOrNull`
are the throwing and punning variants. `Uri.relativize` is the inverse (see [Recipes](#recipes)).

```kotlin
val base = Url.parseOrThrow("https://example.com/a/b")
base.resolveOrThrow("../c")                   // https://example.com/c
```

For a single-component edit without a builder, both profiles offer copy-with helpers — `withPort`,
`withFragment`, and `withoutFragment`. `Url` adds WHATWG-named aliases (`withProtocol`, `withHost`,
`withHostname`, `withPort(String)`, `withPathname`, `withSearch`, `withHash`, `withUsername`,
`withPassword`) for callers used to the JS property names. Predicates round out the surface:
`Uri.isAbsolute()` / `Uri.isOpaquePath()`, `Url.isSpecial()`, and — on both — `isDirectory()` /
`hasTrailingSlash()`.

```kotlin
Url.parseOrThrow("https://example.com/a/b/").isDirectory()   // true — path ends in '/'
```

**Redaction.** `redact()` returns a copy with the three components RFC 3986 treats as sensitive or
context-dependent — userinfo, query, and fragment — stripped, leaving scheme, host, port, and path intact.
Safe to log:

```kotlin
Url.parseOrThrow("https://user:secret@example.com/p?token=abc#frag").redact().toString()
// "https://example.com/p"  — credentials, query, and fragment gone; the rest verbatim
```

The generic `Uri` preserves what you parsed and normalizes only when asked:

```kotlin
val uri = Uri.parseOrThrow("HTTP://Example.com/a/../b")
uri.toString()                 // "HTTP://Example.com/a/../b"  — verbatim
uri.normalized().toString()    // "http://example.com/b"       — RFC 3986 §6.2
```

## Utilities

The parsing engine's building blocks are public as small facades, for when you need one component
rather than a whole reference. All are `object`/`static` methods that read the same from Kotlin and
Java.

**Percent-coding.** `Percent.encode` escapes for a chosen component; `Percent.decode` is lenient (a
malformed `%` is left verbatim) and total. `Component.COMPONENT` is the strict `encodeURIComponent`
set; `PATH_SEGMENT` leaves `/` unescaped. (`QUERY`, `FRAGMENT`, and `USER_INFO` round out the set.)

```kotlin
import org.dexpace.kuri.percent.Percent

Percent.encode("a b/c", Percent.Component.COMPONENT)    // "a%20b%2Fc"
Percent.encode("/", Percent.Component.PATH_SEGMENT)     // "/"       — a slash is data in one segment
Percent.decode("a%2Fb")                                 // "a/b"
```

**IDNA (UTS-46).** `Idn.toAscii` is fallible — it returns a `ParseResult` — while `Idn.toUnicode` is
best-effort and total.

```kotlin
import org.dexpace.kuri.idna.Idn

Idn.toAscii("bücher.example").getOrNull()   // "xn--bcher-kva.example"
Idn.toUnicode("xn--bcher-kva.example")      // "bücher.example"
```

**Scheme facts.** Profile-independent, case-insensitive, and total.

```kotlin
import org.dexpace.kuri.scheme.Schemes

Schemes.defaultPort("https")   // 443
Schemes.defaultPort("file")    // null   — special, but portless
Schemes.isSpecial("http2")     // false
Schemes.isValid("mailto")      // true
```

The same three facades from Java are plain statics; `Kuri.VERSION` reports the running release (kuri
has no facade type — start from `Url` or `Uri`):

```java
Percent.encode("a b/c", Percent.Component.COMPONENT);  // "a%20b%2Fc"
Idn.toAscii("bücher.example").getOrNull();             // "xn--bcher-kva.example"
Schemes.defaultPort("https");                          // 443 (Integer)
String version = Kuri.VERSION;                          // e.g. "0.1.0"
```

## Recipes

**Edit query parameters.** The builder edits follow `URLSearchParams`: `setQueryParameter` replaces,
`addQueryParameter` appends (keeping duplicates), `removeAllQueryParameters` drops every match.

```kotlin
val url = Url.parseOrThrow("https://example.com/?a=1&a=2&b=3")
    .newBuilder()
    .setQueryParameter("a", "9")     // a=1, a=2  ->  a=9
    .removeAllQueryParameters("b")
    .build()                          // https://example.com/?a=9
```

Read the decoded pairs off a parsed value. `queryParameters` is duplicate-preserving; `get` returns
the first value, and **`null` for both an absent name and a present name with no `=`** — use `has` or
`getAll` to tell those apart.

```kotlin
val params = Url.parseOrThrow("https://h/?q=kotlin&q=jvm&flag").queryParameters
params["q"]          // "kotlin"            — first value wins
params.getAll("q")   // ["kotlin", "jvm"]
params["flag"]       // null               — present, but has no '='...
params.has("flag")   // true               — ...so check has()
```

`QueryParameters` is also iterable and index-addressable: it implements `Iterable<QueryParameter>` and
exposes `names()`, `size`, `nameAt` / `valueAt`, and `toMap()`, plus `newBuilder()` for a mutable copy.

**Multi-value query params.** `split(name, delimiter)` reads every pair for a name and splits each
value on a delimiter, so a delimited list in one pair *and* repeated pairs both flatten into a single
list. Splitting is literal and lossless — no trimming, empty tokens kept — and typing is just a `map`.

```kotlin
val q = Url.parseOrThrow("https://h/?roles=admin,user&perm=read|write&id=1,2&id=3").queryParameters
q.split("roles", ',')                  // ["admin", "user"]
q.split("perm", '|')                   // ["read", "write"]
q.split("id", ',')                     // ["1", "2", "3"]   — pairs and delimiters both flatten
q.split("id", ',').map(String::toInt)  // [1, 2, 3]         — conversion is just map()
```

The `Uri` profile computes its query on demand, so there it is a method — `uri.queryParameters()` —
rather than a property.

**Form encoding.** `toQueryString()` emits the generic `%20` dialect; `toFormUrlEncoded()` emits the
HTML form dialect (space as `+`). `parse` reads a URL query; `parseForm` reads a form body. `of(Map)`
collapses duplicate names, while `of(vararg QueryParameter)` preserves them.

```kotlin
import org.dexpace.kuri.query.QueryParameter
import org.dexpace.kuri.query.QueryParameters

val q = QueryParameters.of(QueryParameter("full name", "Ada Lovelace"))
q.toQueryString()                                    // "full%20name=Ada%20Lovelace"
q.toFormUrlEncoded()                                 // "full+name=Ada+Lovelace"
QueryParameters.parseForm("a=b+c&a=d").getAll("a")   // ["b c", "d"]   — '+' decodes to space
```

**Edit the path.** Add or replace decoded segments — each is percent-encoded for you. `path` is the
decoded path; `encodedPath` is the raw one.

```kotlin
val uri = Uri.parseOrThrow("http://h/a/b/c")
    .newBuilder()
    .setPathSegment(1, "x y")        // the space is encoded
    .build()
uri.encodedPath      // "/a/x%20y/c"
uri.path             // "/a/x y/c"          — decoded
uri.fileName()       // "c"
```

**Relativize.** `Uri.relativize` inverts `resolve`: it returns a reference that resolves back to the
target against the same base, or `null` when there is no relative form (a differing scheme or
authority, or an opaque path on either side). `Url.relativize` is the same, returning a `String?`.

```kotlin
val base = Uri.parseOrThrow("http://h/a/b/")
val rel = base.relativize(Uri.parseOrThrow("http://h/a/b/c/d"))
    ?: error("no relative form")     // a relative Uri, or null when none resolves back
rel.uriString                        // "c/d"
base.resolveOrThrow(rel.uriString)   // http://h/a/b/c/d  — round-trips to the target
```

## URI templates

`UriTemplate` is the standards-based, string-first companion to `kuri-bind` — a full
[RFC 6570](https://www.rfc-editor.org/rfc/rfc6570) implementation. Parse a template once, then `expand`
it against a set of variables. All four RFC levels are supported, including the operators (`+`, `#`,
`.`, `/`, `;`, `?`, `&`) and the prefix (`{var:3}`) and explode (`{list*}`) modifiers over string,
list, and map values.

```kotlin
import org.dexpace.kuri.template.UriTemplate

val t = UriTemplate.parse("https://api.example.com/users/{id}{?fields*,page}")
t.expand(mapOf("id" to 42, "fields" to listOf("a", "b"), "page" to 2))
// "https://api.example.com/users/42?fields=a&fields=b&page=2"
```

Expansion never throws — an undefined variable (absent, `null`, or an empty list/map) contributes
nothing, per RFC 6570 §3.2.1. `expand` also has a `vararg Pair` overload, and `expandToUri` /
`expandToUrl` expand and parse in one step, returning a `ParseResult`. `variableNames` lists the
template's variables, and the `match` extension runs a template in reverse, extracting variables from a
string:

```kotlin
import org.dexpace.kuri.template.match

t.variableNames                                     // ["id", "fields", "page"]
UriTemplate.parse("/users/{id}").match("/users/42") // {id=42}, or null on no match
```

`UriTemplate.parseOrNull` is the non-throwing parse; a malformed template otherwise raises a
`UriTemplateException` carrying the offending `index`.

## Kotlin DSL and operators

The `org.dexpace.kuri.ktx` package adds optional Kotlin-only sugar over the core API — import it when you
want it, ignore it otherwise. Nothing here is visible to Java.

**Conversions.** String extensions that read like the companion factories:

```kotlin
import org.dexpace.kuri.ktx.toUrl
import org.dexpace.kuri.ktx.toUrlOrThrow

"https://example.com".toUrl()          // Url?          — null on failure
"https://example.com".toUrlOrThrow()   // Url           — throws on failure
"https://example.com".toUrlResult()    // ParseResult<Url>
```

(`toUri` / `toUriOrThrow` / `toUriResult` mirror them for the RFC 3986 profile.)

**Operators.** `/` appends a path segment, `+` adds a query parameter, `[]` reads the first value for a
name, and `in` tests presence:

```kotlin
import org.dexpace.kuri.ktx.div
import org.dexpace.kuri.ktx.plus

val url = Url.parseOrThrow("https://api.example.com/v1")
val next = url / "users" / "42" + ("page" to "2")   // https://api.example.com/v1/users/42?page=2
next["page"]                                          // "2"
"page" in next                                        // true
```

**Builder DSL.** `buildUrl` / `buildUri` open a builder in a lambda, and `edit` opens one pre-filled
from an existing value:

```kotlin
import org.dexpace.kuri.ktx.buildUrl
import org.dexpace.kuri.ktx.edit

val url = buildUrl {
    scheme("https")
    host("example.com")
    pathSegments("v1", "users")   // vararg helper
    params("page" to "2", "sort" to "name")
}
val bumped = url.edit { setQueryParameter("page", "3") }
```

## Annotation binding (kuri-bind)

The optional **`kuri-bind`** module maps an annotated object onto a `Url`/`Uri` builder — declare the
mapping once, then turn any request object into a URL. It is a JVM add-on (it uses Kotlin reflection);
the core `kuri` artifact stays dependency-free.

```kotlin
@Url                                           // bind this class as a URL
@PathTemplate("/search/{category}/{tags...}")  // {name} = one segment, {name...} = catch-all tail
data class SearchRequest(
    @Path("category") val category: String,
    @Path("tags")     val tags: List<String>,
    @Query("q")       val term: String,
    @QueryMap         val extra: Map<String, String>,
)

val base = Url.parseOrThrow("https://api.example.com")
val url = KuriBind.bindInto(
    base.newBuilder(),
    SearchRequest("shoes", listOf("a", "b"), "x y", mapOf("page" to "2")),
).build()
// https://api.example.com/search/shoes/a/b?q=x%20y&page=2
```

**Annotations** (on properties, fields, getters, or constructor parameters): `@Scheme`, `@Host`,
`@Port`, `@Username`/`@Password`/`@UserInfo`, `@Path`, `@Query`, `@QueryMap`, `@Fragment`, plus the
class-level `@Url`/`@Uri` (profile selector) and `@PathTemplate`. Unannotated members are ignored.
Values are the *decoded* form — the builder percent-encodes them.

**Entry points** — the `KuriBind` facade, ergonomic from Kotlin and Java:

```kotlin
val builder: Url.Builder = KuriBind.toUrlBuilder(request)   // populated builder — edit before build()
val url:     Url         = KuriBind.toUrl(request)          // build() convenience
KuriBind.bindInto(clientBase.newBuilder(), request)         // bind onto a client base URL
KuriBind.toUrlOrNull(request)                               // null instead of throwing
// …and toUri / toUriBuilder / *OrNull for the RFC 3986 profile.
```

`bindInto` targets the common SDK shape: a base URL that already carries a scheme and host, onto which a
request object appends its path and query. Single-valued components the object carries (scheme, host,
port, userinfo, fragment) override the base; a component the object leaves out keeps the base's value.
`BindOptions.strict` governs conflicts within the bound object graph — for example a merged `@Url`
sub-object that disagrees with its parent — rather than the object against the base. Nested objects are
supported: mark a complex member `@Url`/`@Uri` to merge all of its components into the parent, or
`@Query`/`@Path` to fold just that component. Binding is bounded (`BindOptions.maxDepth`, cycle-detected)
and fails fast on misconfiguration with a `KuriBindException`; pass `BindOptions(strict = true)` to reject
a conflicting single-valued write within the object graph.

Members bind in Kotlin primary-constructor order (or Java record component order). Other shapes — plain
Java beans and body-declared properties — have no reliable order through reflection and bind in a stable
order sorted by name, so prefer a `data class`, a record, or a `@PathTemplate` when positional path order
matters. Within the object graph, single-valued components are first-writer-wins in that declaration
order: a parent's own leaf overrides the same component from an `@Url`/`@Uri`-merged child only when it
is declared before the merge member, so declare the component first when the parent's value must win. A
leading `/` in a template is decorative for an authority-less URI, where a segment path roots only under
an authority.

**Reflection and Java support.** `kuri-bind` uses Kotlin reflection (`kotlin-reflect`). Kotlin classes are
first-class; Java classes (POJOs, records) are supported through Kotlin reflection's interop views
(getters and fields). A dedicated `java.lang.reflect`-native backend is not shipped — if you need one,
please open an issue.

## kotlinx.serialization (kuri-serde-kotlinx)

The optional **`kuri-serde-kotlinx`** module bridges kuri and
[kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization). It ships across the same
multiplatform targets as `:kuri` (no Android variant) and gives you two things.

**`Url` / `Uri` serializers.** Serialize a value as its string form in any kotlinx format. Reference the
serializer per property, or register it contextually:

```kotlin
import kotlinx.serialization.Serializable
import org.dexpace.kuri.Url
import org.dexpace.kuri.serde.UrlSerializer

@Serializable
data class Link(
    @Serializable(with = UrlSerializer::class) val href: Url,
)

Json.encodeToString(Link(Url.parseOrThrow("https://example.com/")))
// {"href":"https://example.com/"}
```

`UriSerializer` does the same for the `Uri` profile.

**`QueryParametersFormat`.** A serialization *format* that maps a flat `@Serializable` class to and from
`QueryParameters` — the typed **decode** direction that `kuri-bind` (object → URL only) does not provide.

```kotlin
import org.dexpace.kuri.serde.QueryParametersFormat

@Serializable
data class Search(val q: String, val page: Int = 1, val tags: List<String> = emptyList())

QueryParametersFormat.decodeFromQueryString<Search>("q=kotlin&page=2&tags=a&tags=b")
// Search(q = "kotlin", page = 2, tags = ["a", "b"])
QueryParametersFormat.encodeToQueryString(Search("kotlin", 2, listOf("a", "b")))
// "q=kotlin&page=2&tags=a&tags=b"
```

`encodeToQueryParameters` / `decodeFromQueryParameters` are the `QueryParameters`-typed variants. The
scope is a single flat class: each scalar property is one parameter, a list property repeats it, and a
`Map` property repeats a `<name>.key` / `<name>.value` pair per entry. Encoding omits a property still at
its declared default, so the output stays minimal, and decoding fills defaults back in symmetrically.
Nested `@Serializable` objects are rejected — model them at the call site or bind them separately.

## Standards

kuri implements the standards below; per-standard conformance is measured in [Conformance](#conformance).

**Core syntax**

| Standard                          | Governs                                                            | Compliance | Support |
|-----------------------------------|--------------------------------------------------------------------|------------|---------|
| [RFC 3986][rfc3986] (STD 66)      | URI generic syntax; the `Uri` model and parsing authority          | Conformant | Default |
| [RFC 3987][rfc3987]               | IRIs — one-way `Iri` mapping, not a validating parser*             | Supported  | Default |
| [RFC 6570][rfc6570]               | URI Templates — the `UriTemplate` facility                         | Conformant | Default |
| [WHATWG URL Standard][whatwg-url] | the `Url` model — parser, special schemes, canonical serialization | Conformant | Default |

\* kuri maps IRIs to URIs one-way (RFC 3987 §3.1/§3.2), rejecting a §2.2 `ucschar`/`iprivate`
repertoire violation and a §4.1 bidi formatting character; it does not enforce §4.2's per-component
directionality restriction, which the RFC itself states as a SHOULD, not a MUST.

**Hosts, internationalization, and IP addresses**

| Standard            | Governs                                                              | Compliance | Support |
|---------------------|----------------------------------------------------------------------|------------|---------|
| [UTS #46][uts46]    | Unicode IDNA Compatibility Processing (host ToASCII / ToUnicode)     | Ratcheting | Default |
| [RFC 5891][rfc5891] | Internationalized Domain Names in Applications (IDNA2008) — protocol | Ratcheting | Default |
| [RFC 5892][rfc5892] | IDNA2008 — Unicode code points and derived properties                | Ratcheting | Default |
| [RFC 3492][rfc3492] | Punycode — the Bootstring encoding of Unicode                        | Conformant | Default |
| [UAX #15][uax15]    | Unicode Normalization Forms (NFC)                                    | Conformant | Default |
| [RFC 5952][rfc5952] | IPv6 address text representation (canonical form)                    | Conformant | Default |
| [RFC 6874][rfc6874] | IPv6 zone identifiers in URLs                                        | Opt-in     | Opt-in  |

**Query**

| Standard                                       | Governs                                      | Compliance | Support |
|------------------------------------------------|----------------------------------------------|------------|---------|
| [`application/x-www-form-urlencoded`][formenc] | Form-encoded query parsing and serialization | Conformant | Default |

**Notation and requirement levels**

| Standard                                           | Governs                                               | Compliance | Support |
|----------------------------------------------------|-------------------------------------------------------|------------|---------|
| [RFC 5234][rfc5234] (STD 68)                       | ABNF — the grammar notation used by the specification | Notation   | —       |
| [RFC 2119][rfc2119] · [RFC 8174][rfc8174] (BCP 14) | Requirement-level keywords (MUST / SHOULD / MAY)      | Notation   | —       |

**Compliance** — *Conformant*: passes the standard's conformance corpus, or its controlling table, with no known
failures · *Ratcheting*: conformant except for cases pinned in the known-failures baseline, which can only shrink (
see [Conformance](#conformance)) · *Opt-in*: conformant when explicitly enabled · *Supported*: implemented as an input
dialect, not measured by a dedicated corpus · *Notation*: used to author the specification, with no runtime behavior to
conform to.

**Support** — *Default*: active in the default configuration of both profiles · *Opt-in*: available behind an explicit
flag, off by default · *—*: not applicable.

## Conformance

Behavior is checked against the conformance corpora the standards ship with:

| Suite                                                  | Result          |
|--------------------------------------------------------|-----------------|
| WHATWG `urltestdata.json` — parsing                    | 888 / 888       |
| WHATWG `urltestdata.json` — parse → serialize (`href`) | 621 / 621       |
| IDNA `IdnaTestV2` + `toascii`                          | 2756 / 2760     |
| Unicode `NormalizationTest.txt` (NFC)                  | 20 034 / 20 034 |
| RFC 3986 §5.4 reference resolution                     | all rows        |
| WHATWG `urlencoded-parser.any.js` — form parsing       | 35 / 35         |

Any case that does not yet pass is pinned in a checked-in known-failures baseline; the build fails if a passing case
later regresses.

## Platforms

The entire public API lives in common Kotlin and compiles for every target below.

| Tier             | Targets                                                                                                                             |
|------------------|-------------------------------------------------------------------------------------------------------------------------------------|
| JVM              | `jvm`                                                                                                                               |
| Android          | `android` (`minSdk 21`, `compileSdk 35`)                                                                                            |
| JavaScript       | `js` (browser, Node.js)                                                                                                             |
| WebAssembly      | `wasmJs` (browser, Node.js)                                                                                                         |
| Native — Apple   | `macosArm64`, `iosArm64`, `iosX64`, `iosSimulatorArm64`, `watchosArm64`, `watchosSimulatorArm64`, `tvosArm64`, `tvosSimulatorArm64` |
| Native — Linux   | `linuxX64`, `linuxArm64`                                                                                                            |
| Native — Windows | `mingwX64`                                                                                                                          |

`:kuri` publishes to every target above; `:kuri-serde-kotlinx` publishes to the same set minus Android, and
`:kuri-bind` is JVM-only. The `java.net.URI` / `java.net.URL` conversions are JVM-only extensions. Every
target compiles on any host; executing the native test suites requires a matching operating system or
simulator.

## Building from source

Building kuri requires a JDK 21 toolchain; the bundled Gradle wrapper provisions the rest.

```
./gradlew build
```

`build` compiles every target and runs the full quality gate. Each check below fails the build:

- `ktlint` (formatting) and `detekt` (static analysis)
- Kotlin `allWarningsAsErrors`
- explicit-API strict mode
- the binary-compatibility validator (`apiCheck`)
- per-module Kover line/branch floors: `kuri` and `kuri-bind` at 99% line / 85% and 88% branch
  respectively, `kuri-serde-kotlinx` at 90% line / 80% branch

After an intentional public-API change, regenerate and commit the API snapshot in the same change:

```
./gradlew apiDump
```

To try an unreleased build in another project, publish to your local Maven repository
(`./gradlew publishToMavenLocal`, then add `mavenLocal()`), or wire a composite build with
`includeBuild("../kuri")` in the consumer's `settings.gradle.kts`.

[rfc2119]: https://www.rfc-editor.org/rfc/rfc2119

[rfc8174]: https://www.rfc-editor.org/rfc/rfc8174

[rfc3986]: https://www.rfc-editor.org/rfc/rfc3986

[rfc3987]: https://www.rfc-editor.org/rfc/rfc3987

[rfc6570]: https://www.rfc-editor.org/rfc/rfc6570

[rfc6874]: https://www.rfc-editor.org/rfc/rfc6874

[rfc5952]: https://www.rfc-editor.org/rfc/rfc5952

[rfc5891]: https://www.rfc-editor.org/rfc/rfc5891

[rfc5892]: https://www.rfc-editor.org/rfc/rfc5892

[rfc3492]: https://www.rfc-editor.org/rfc/rfc3492

[rfc5234]: https://www.rfc-editor.org/rfc/rfc5234

[uts46]: https://www.unicode.org/reports/tr46/

[uax15]: https://www.unicode.org/reports/tr15/

[whatwg-url]: https://url.spec.whatwg.org/

[formenc]: https://url.spec.whatwg.org/#application/x-www-form-urlencoded
