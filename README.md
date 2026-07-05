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

From Java:

```java
import org.dexpace.kuri.Url;
import org.dexpace.kuri.error.ParseResult;

// Happy path: unwrap the value, or throw UriSyntaxException on a bad input.
Url url = Url.parse("https://example.com/a?b=1").getOrThrow();
url.scheme();    // "https"
url.hostName();  // "example.com"

// Null-returning form, ergonomic from Java — no exception, no ParseResult branch.
Url maybe = Url.parseOrNull("https://example.com/");   // a Url, or null on failure

// Inspect the failure without throwing: read the human-readable message off the error.
ParseResult<Url> result = Url.parse("://no-scheme");
if (result instanceof ParseResult.Err) {
    String reason = ((ParseResult.Err) result).getError().getMessage();
}
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

Parsing returns a `ParseResult<T>` — errors are values, not exceptions — and you choose how to consume it:

```kotlin
Url.parse(input)                 // ParseResult<Url>  (Ok / Err)
Url.parseOrThrow(input)          // Url, or throws UriSyntaxException
Url.parse(input).getOrNull()     // Url?
Url.parse(input).getOrThrow()    // Url, or throws UriSyntaxException
Url.canParse(input)              // Boolean
Url.parse(input).fold(onOk = { it.host }, onErr = { it })
```

`Err` carries a structured `UriParseError` with the offending offset and reason. Read
`error.message` (Java `error.getMessage()`) for a human-readable rendering of the failure without
throwing.

## Building and resolving

Values are immutable; builders produce new ones, and `newBuilder()` copies an existing value.

```kotlin
val url = Url.Builder()
    .scheme("https")
    .host("example.com")
    .addPathSegment("v1")
    .addPathSegment("users")
    .setQueryParameter("page", "2")
    .build()                                  // https://example.com/v1/users?page=2

val next = url.resolve("orgs").getOrThrow()   // https://example.com/v1/orgs
```

The generic `Uri` preserves what you parsed and normalizes only when asked:

```kotlin
val uri = Uri.parse("HTTP://Example.com/a/../b").getOrThrow()
uri.toString()                 // "HTTP://Example.com/a/../b"  — verbatim
uri.normalized().toString()    // "http://example.com/b"       — RFC 3986 §6.2
```

Query strings have their own immutable, duplicate-preserving model. `QueryParameters` reads decoded
pairs; iterate them, look them up, or project to a map — and build a new query from a map or straight
into a `Url`.

```kotlin
import org.dexpace.kuri.query.QueryParameters

val params = QueryParameters.parse("q=kotlin&page=2&q=jvm")
params["q"]                     // "kotlin"          — first value wins
params.has("page")              // true
params.toMap()                  // {q=kotlin, page=2}  — first value per name
for ((name, value) in params) { /* q→kotlin, page→2, q→jvm — duplicates preserved */ }

// Build a query from a map, or set it straight onto a Url.
QueryParameters.of(linkedMapOf("q" to "kotlin", "page" to "2")).toQueryString()  // "q=kotlin&page=2"
Url.Builder().scheme("https").host("example.com").setQueryParameter("q", "kotlin").build()
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
- **API reference** — generated by [Dokka](https://kotlinlang.org/docs/dokka-introduction.html). Build the HTML site
  with `./gradlew :kuri:dokkaGeneratePublicationHtml`; the output is written under `kuri/build/dokka/`.
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
