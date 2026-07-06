<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/kuri-dark.svg">
    <img alt="kuri" src="docs/assets/kuri-light.svg" width="600">
  </picture>
</p>

<p align="center">A standards-faithful URI and URL library for Kotlin Multiplatform and Java.</p>

<p align="center">
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/license-MIT-blue.svg"></a>
  <a href="https://central.sonatype.com/artifact/org.dexpace/kuri"><img alt="Maven Central" src="https://img.shields.io/maven-central/v/org.dexpace/kuri?logo=apachemaven&logoColor=white&label=Maven%20Central"></a>
  <a href="https://kotlinlang.org"><img alt="Kotlin" src="https://img.shields.io/badge/kotlin-2.4.0-7F52FF.svg?logo=kotlin&logoColor=white"></a>
  <img alt="Kotlin Multiplatform" src="https://img.shields.io/badge/kotlin-multiplatform-7F52FF.svg?logo=kotlin&logoColor=white">
  <img alt="JDK" src="https://img.shields.io/badge/JDK-8%2B-437291.svg?logo=openjdk&logoColor=white">
  <img alt="Coverage" src="https://img.shields.io/badge/coverage-%E2%89%A580%25-success.svg">
</p>

## Contents

[Installation](#installation) ·
[Quick start](#quick-start) ·
[Two models](#two-models-one-engine) ·
[Parsing and errors](#parsing-and-errors) ·
[Building and resolving](#building-and-resolving) ·
[Utilities](#utilities) ·
[Recipes](#recipes) ·
[Standards](#standards) ·
[Conformance](#conformance) ·
[Platforms](#platforms) ·
[Versioning and stability](#versioning-and-stability) ·
[Building from source](#building-from-source) ·
[Documentation](#documentation) ·
[Contributing](#contributing) ·
[Security](#security) ·
[License](#license)

## Installation

kuri is published to **Maven Central**. The artifact id is identical across Kotlin Multiplatform targets; the Gradle
module metadata selects the right per-platform variant automatically.

| Coordinate | Value           |
|------------|-----------------|
| Group      | `org.dexpace`   |
| Artifact   | `kuri`          |
| Version    | `0.1.0-alpha.1` |

> [!NOTE]
> kuri is in an early **alpha** series: the public API is not yet frozen and may change between releases, so pin to an
> exact version.

**Gradle (Kotlin Multiplatform or Kotlin/JVM)** — add the dependency to your common (or JVM) source set:

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("org.dexpace:kuri:0.1.0-alpha.1")
}
```

Gradle module metadata resolves the correct per-platform variant automatically.

**Maven (Java / JVM)** — reference the JVM artifact explicitly:

```xml
<dependency>
  <groupId>org.dexpace</groupId>
  <artifactId>kuri-jvm</artifactId>
  <version>0.1.0-alpha.1</version>
</dependency>
```

**Requirements**

|                      |                                                                                          |
|----------------------|------------------------------------------------------------------------------------------|
| Java runtime         | Java 8 or newer (the JVM artifact is compiled to `1.8` bytecode for broad compatibility) |
| Kotlin consumers     | Kotlin 2.0 or newer; the public API lives in common Kotlin                               |
| Runtime dependencies | None beyond the Kotlin standard library                                                  |

**Consuming from source** — to try an unreleased build, publish to your local Maven repository (`./gradlew
publishToMavenLocal`, then add `mavenLocal()`), or wire a composite build with `includeBuild("../kuri")` in the
consumer's `settings.gradle.kts`.

## Quick start

```kotlin
import org.dexpace.kuri.Url

val url = Url.parse("https://Example.com:443/a/../b?q=1#frag").getOrThrow()

url.scheme                     // "https"
url.hostName                   // "example.com"  — lower-cased
url.port                       // null           — the default :443 is elided
url.effectivePort              // 443
url.pathSegments               // ["b"]          — /a/../b is resolved
url.queryParameters.get("q")   // "1"
url.toString()                 // "https://example.com/b?q=1#frag"
```

The same read from Java — static factories, `()` accessors, no Kotlin-only syntax:

```java
import org.dexpace.kuri.Url;

Url url = Url.parseOrThrow("https://Example.com:443/a/../b?q=1#frag");

url.scheme();                    // "https"
url.hostName();                  // "example.com"
url.port();                      // null (Integer)  — the default :443 is elided
url.effectivePort();             // 443
url.pathSegments();              // ["b"]
url.queryParameters().get("q");  // "1"
url.toString();                  // "https://example.com/b?q=1#frag"
```

## Two models, one engine

|          | `Uri`                                              | `Url`                                 |
|----------|----------------------------------------------------|---------------------------------------|
| Standard | RFC 3986 (RFC 3987-aware)                          | WHATWG URL                            |
| Posture  | preserve the input; normalize on request           | canonicalize eagerly                  |
| Scheme   | any, and may be absent (relative reference)        | always present; special schemes known |
| Host     | reg-name / IPv4 / IPv6 / IP-future                 | IDNA, IPv4 shorthand, opaque hosts    |
| Equality | structural, plus `normalizedEquals()` for RFC §6.2 | on the canonical serialization        |

`Url.toUri()` is near-lossless; `Uri.toUrl()` may fail when a generic URI is not a valid web URL.

**Which do I use?**

- **`Url`** for WHATWG web URLs — `http` / `https` / `ws` / `wss` / `file`, the input a browser or
  HTTP client hands you. It canonicalizes eagerly and always carries a scheme (and, for a special
  scheme, a host).
- **`Uri`** for RFC 3986 generic identifiers — `urn:`, `mailto:`, custom schemes, and relative
  references. It preserves the input exactly and normalizes only when you ask, via `uri.normalized()`.

A few accessors differ between the profiles by design; getting them wrong is a common trap:

| Reading                    | `Uri` (RFC 3986)                     | `Url` (WHATWG)                                          |
|----------------------------|--------------------------------------|--------------------------------------------------------|
| `port`                     | preserved verbatim                   | `null` when elided **or** equal to the scheme default  |
| port with no scheme default | `effectivePort()` → `null`          | `effectivePort` → `-1`                                 |
| opaque origin              | —                                    | `origin` is the literal string `"null"`; test `hasOpaqueOrigin()` |

Across both, `host` is the structured `Host` ADT and `hostName` is its serialized text; to render a
`Host` you hold, call `host.asText()`.

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
control flow (a validation error never downgrades a successful parse).

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
`withFragment`, and `withoutFragment` — and predicates round out the surface: `Uri.isAbsolute()` /
`Uri.isOpaquePath()` and `Url.isSpecial()`.

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
set; `PATH_SEGMENT` leaves `/` unescaped.

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
String version = Kuri.VERSION;                          // e.g. "0.1.0-alpha.1"
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

## Standards

kuri implements the standards below; per-standard conformance is measured in [Conformance](#conformance).

**Core syntax**

| Standard                          | Governs                                                            | Compliance | Support |
|-----------------------------------|--------------------------------------------------------------------|------------|---------|
| [RFC 3986][rfc3986] (STD 66)      | URI generic syntax; the `Uri` model and parsing authority          | Conformant | Default |
| [RFC 3987][rfc3987]               | Internationalized Resource Identifiers (IRIs)                      | Supported  | Default |
| [WHATWG URL Standard][whatwg-url] | the `Url` model — parser, special schemes, canonical serialization | Conformant | Default |

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
| [`application/x-www-form-urlencoded`][formenc] | Form-encoded query parsing and serialization | Supported  | Default |

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

[rfc2119]: https://www.rfc-editor.org/rfc/rfc2119

[rfc8174]: https://www.rfc-editor.org/rfc/rfc8174

[rfc3986]: https://www.rfc-editor.org/rfc/rfc3986

[rfc3987]: https://www.rfc-editor.org/rfc/rfc3987

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

## Conformance

Behavior is checked against the conformance corpora the standards ship with:

| Suite                                                  | Result          |
|--------------------------------------------------------|-----------------|
| WHATWG `urltestdata.json` — parsing                    | 888 / 888       |
| WHATWG `urltestdata.json` — parse → serialize (`href`) | 621 / 621       |
| IDNA `IdnaTestV2` + `toascii`                          | 2756 / 2760     |
| Unicode `NormalizationTest.txt` (NFC)                  | 20 034 / 20 034 |
| RFC 3986 §5.4 reference resolution                     | all rows        |

Any case that does not yet pass is pinned in a checked-in known-failures baseline; the build fails if a passing case
later regresses.

## Platforms

The entire public API lives in common Kotlin and compiles for every target below.

| Tier             | Targets                                                                                                                             |
|------------------|-------------------------------------------------------------------------------------------------------------------------------------|
| JVM              | `jvm`                                                                                                                               |
| JavaScript       | `js` (browser, Node.js)                                                                                                             |
| WebAssembly      | `wasmJs` (browser, Node.js)                                                                                                         |
| Native — Apple   | `macosArm64`, `iosArm64`, `iosX64`, `iosSimulatorArm64`, `watchosArm64`, `watchosSimulatorArm64`, `tvosArm64`, `tvosSimulatorArm64` |
| Native — Linux   | `linuxX64`, `linuxArm64`                                                                                                            |
| Native — Windows | `mingwX64`                                                                                                                          |

The `java.net.URI` / `java.net.URL` conversions are JVM-only extensions. Every target compiles on any host; executing
the native test suites requires a matching operating system or simulator.

## Versioning and stability

kuri follows [Semantic Versioning 2.0.0](https://semver.org/). At `0.1.0-alpha.1` the public API is not yet frozen and
may change before `1.0.0`, and minor releases in the `0.x` series may carry breaking changes, so pin to an exact
version. Every public signature is tracked in a checked-in binary-compatibility snapshot under `api/`, so an unintended
API change fails the build (see [Building from source](#building-from-source)).

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
- an 80% Kover line-coverage floor

After an intentional public-API change, regenerate and commit the API snapshot in the same change:

```
./gradlew apiDump
```

## Documentation

- [`docs/SPEC.md`](docs/SPEC.md) — the normative behavior specification. It defines the character repertoire, the
  percent-encoding matrix, the host pipeline, the parsing algorithm, reference resolution, the query model,
  normalization and equivalence semantics, and the error model that a conforming implementation must exhibit for each
  profile.
- **API reference** — once published, the full KDoc reference will be hosted at
  [javadoc.io/doc/org.dexpace/kuri](https://javadoc.io/doc/org.dexpace/kuri). To browse it locally, build the
  HTML site with `./gradlew :kuri:dokkaGeneratePublicationHtml` (output under `kuri/build/dokka/`).
- [`CHANGELOG.md`](CHANGELOG.md) — notable changes per release, in Keep a Changelog format.
- [`SECURITY.md`](SECURITY.md) — supported versions and how to report a vulnerability privately.

## Contributing

Issues and pull requests are welcome on the [project repository](https://github.com/dexpace/kuri). `./gradlew build`
must pass — the full quality gate and the conformance baselines included — before a change can merge, and a public-API
change must commit the regenerated `api/` snapshot (see [Building from source](#building-from-source)). New or changed
behavior should be grounded in the relevant standard and reflected in [`docs/SPEC.md`](docs/SPEC.md). Commits and
pull-request titles use the `feat:` / `fix:` / `test:` / `docs:` / `chore:` prefix convention.

## Security

kuri parses and canonicalizes untrusted URLs, so security reports are taken seriously. See
[`SECURITY.md`](SECURITY.md) for the supported versions and how to report a vulnerability privately through GitHub's
Security tab — please don't open a public issue for a security problem.

## License

kuri is released under the [MIT License](LICENSE), Copyright (c) 2026 dexpace.
