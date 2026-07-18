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
[Learn more](#learn-more) ·
[Standards and conformance](#standards-and-conformance) ·
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

## Learn more

kuri's full guide — the two `Uri`/`Url` models, parsing and errors, building and
resolving, the utility facades, query/path recipes, and the optional `kuri-bind`
annotation binder — lives on the [documentation site](https://dexpace.github.io/kuri/).
This README stays focused on getting a first `implementation(...)` line working.

## Standards and conformance

kuri implements RFC 3986, RFC 3987, the WHATWG URL Standard, UTS #46, RFC 5891/5892,
RFC 3492, UAX #15, RFC 5952, RFC 6874, and `application/x-www-form-urlencoded` — see
[Standards and conformance](https://dexpace.github.io/kuri/guides/standards-and-conformance/)
on the docs site for the full compliance table and current conformance-corpus results.

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
- **API reference** — hosted at [dexpace.github.io/kuri/api](https://dexpace.github.io/kuri/api/). To browse
  it locally, build the HTML site with `./gradlew :kuri:dokkaGeneratePublicationHtml` (output under
  `kuri/build/dokka/`).
- **[dexpace.github.io/kuri](https://dexpace.github.io/kuri/)** — the full documentation site: installation,
  guides, recipes, the rendered spec, and the API reference above, all in one place.
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
