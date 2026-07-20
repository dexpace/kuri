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
  <img alt="Coverage" src="https://img.shields.io/badge/coverage-%E2%89%A590%25-success.svg">
</p>

kuri parses, builds, and normalizes URIs and URLs to the letter of the standards — RFC 3986, the WHATWG URL
Standard, and UTS #46 for internationalized hosts. Parsing returns a result instead of throwing, values are
immutable, and the whole API reads naturally from both Kotlin and Java. It runs everywhere Kotlin does — JVM,
Android, JS, Wasm, and native.

## Installation

```kotlin
// build.gradle.kts — Kotlin Multiplatform or Kotlin/JVM
dependencies {
    implementation("org.dexpace:kuri:0.1.0")
}
```

Published to Maven Central as `org.dexpace:kuri`; Gradle module metadata resolves the correct per-platform
variant automatically. For Maven or plain JVM projects, depend on the `kuri-jvm` artifact:

```xml
<dependency>
  <groupId>org.dexpace</groupId>
  <artifactId>kuri-jvm</artifactId>
  <version>0.1.0</version>
</dependency>
```

Runs on **Java 8+** and **Kotlin 2.0+**, with no runtime dependencies beyond the Kotlin standard library.

> [!NOTE]
> kuri is in the `0.x` series — the public API is not yet frozen and may change between minor releases, so
> pin to an exact version.

## Modules

kuri ships as three artifacts under `org.dexpace`, so you pull in only what you use:

- **`kuri`** — the core engine: the `Url` and `Uri` models, parsing, building, the query API, and the
  standalone `Percent` / `Idn` / `Schemes` utilities. Kotlin Multiplatform (JVM, Android, JS, Wasm, native).
  This is the only module the [quick start](#quick-start) needs.
- **`kuri-bind`** — maps an annotated request object onto a `Url`/`Uri` builder (`@Url`, `@Path`, `@Query`,
  …). JVM-only, since it uses Kotlin reflection; the core stays dependency-free. See
  [Annotation binding](docs/GUIDE.md#annotation-binding-kuri-bind).
- **`kuri-serde-kotlinx`** — a [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization)
  bridge: `Url`/`Uri` serializers plus a query-parameters format. Multiplatform across the same targets as
  `kuri`, minus Android. See
  [kotlinx.serialization](docs/GUIDE.md#kotlinxserialization-kuri-serde-kotlinx).

The optional modules depend on the core, and you add them the same way:

```kotlin
dependencies {
    implementation("org.dexpace:kuri:0.1.0")
    implementation("org.dexpace:kuri-bind:0.1.0")           // optional — annotation binding
    implementation("org.dexpace:kuri-serde-kotlinx:0.1.0")  // optional — kotlinx.serialization
}
```

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

Parsing never throws: `parse` returns a `ParseResult`, with `parseOrNull` / `parseOrThrow` / `canParse` when
you want a `null`, an exception, or a boolean instead.

## Two models

kuri gives you two profiles over one engine. Reach for **`Url`** for WHATWG web URLs — `http`, `https`, `ws`,
`wss`, `file` — the input a browser or HTTP client hands you; it canonicalizes eagerly and always carries a
scheme. Reach for **`Uri`** for RFC 3986 generic identifiers — `urn:`, `mailto:`, custom schemes, and
relative references; it preserves the input exactly and normalizes only when you ask.

```kotlin
Uri.parseOrThrow("HTTP://Example.com/a/../b").toString()   // "HTTP://Example.com/a/../b"  — verbatim
Url.parseOrThrow("HTTP://Example.com/a/../b").toString()   // "http://example.com/b"       — canonicalized
```

The guide covers the full comparison, the accessor differences to watch for, IPv6 zone identifiers, and IRI
conversion: [Two models, one engine](docs/GUIDE.md#two-models-one-engine).

## Documentation

- **[User guide](docs/GUIDE.md)** — the complete usage reference:
  [parsing & errors](docs/GUIDE.md#parsing-and-errors),
  [building & resolving](docs/GUIDE.md#building-and-resolving),
  [utilities](docs/GUIDE.md#utilities),
  [recipes](docs/GUIDE.md#recipes),
  [URI templates](docs/GUIDE.md#uri-templates),
  [Kotlin DSL](docs/GUIDE.md#kotlin-dsl-and-operators),
  [annotation binding](docs/GUIDE.md#annotation-binding-kuri-bind),
  [kotlinx.serialization](docs/GUIDE.md#kotlinxserialization-kuri-serde-kotlinx),
  and [standards & conformance](docs/GUIDE.md#standards).
- [`docs/SPEC.md`](docs/SPEC.md) — the normative behavior specification (character repertoire, encoding matrix, host pipeline, parsing algorithm, resolution, query model, normalization, and error model).
- **API reference** — once published, the KDoc reference will be hosted at [javadoc.io/doc/org.dexpace/kuri](https://javadoc.io/doc/org.dexpace/kuri); build it locally with `./gradlew :kuri:dokkaGeneratePublicationHtml`.
- [`CHANGELOG.md`](CHANGELOG.md) — notable changes per release, in Keep a Changelog format.

## Contributing

Issues and pull requests are welcome. `./gradlew build` — the full quality gate and conformance baselines
included — must pass before a change can merge, and a public-API change must commit the regenerated `api/`
snapshot (see [Building from source](docs/GUIDE.md#building-from-source)). New or changed behavior should be
grounded in the relevant standard and reflected in [`docs/SPEC.md`](docs/SPEC.md). Commits and pull-request
titles use the `feat:` / `fix:` / `docs:` / `chore:` prefix convention.

## Security

kuri parses and canonicalizes untrusted URLs, so security reports are taken seriously. See
[`SECURITY.md`](SECURITY.md) for supported versions and how to report a vulnerability privately through
GitHub's Security tab — please don't open a public issue for a security problem.

## License

kuri is released under the [MIT License](LICENSE), Copyright (c) 2026 dexpace.
