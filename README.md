<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/kuri-dark.svg">
    <img alt="kuri" src="docs/assets/kuri-light.svg" width="600">
  </picture>
</p>

<p align="center">A standards-faithful URI and URL library for Kotlin and Java.</p>

<p align="center">
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/license-MIT-blue.svg"></a>
  <a href="https://kotlinlang.org"><img alt="Kotlin" src="https://img.shields.io/badge/kotlin-2.4.0-7F52FF.svg?logo=kotlin&logoColor=white"></a>
  <img alt="Kotlin Multiplatform" src="https://img.shields.io/badge/kotlin-multiplatform-7F52FF.svg?logo=kotlin&logoColor=white">
  <img alt="JDK" src="https://img.shields.io/badge/JDK-8%2B-437291.svg?logo=openjdk&logoColor=white">
  <img alt="Coverage" src="https://img.shields.io/badge/coverage-%E2%89%A580%25-success.svg">
</p>

kuri parses, builds, normalizes, and serializes URIs and URLs, and it does so by the book. It models two value types over a single engine: `Uri`, the generic identifier of **RFC 3986** (IRI-aware per RFC 3987), and `Url`, the web address of the **WHATWG URL Standard**. RFC 3986 is the governing authority; every place the web platform deliberately departs from it is a registered, documented deviation rather than an accident.

It is written in pure Kotlin with no `expect`/`actual` and no platform fallbacks, so the same parser — internationalized host names included — runs identically on the JVM, JavaScript, WebAssembly, and native. Punycode, UTS-46 (Unicode 16.0), and Unicode NFC normalization are implemented in Kotlin rather than delegated to `java.net.IDN`, so behavior never forks across targets. The JVM gets first-class Java interop on top: static factories, fluent accessors, builders, and `java.net.URI` / `java.net.URL` bridges.

Correctness is the contract. The engine is validated against the Web Platform Tests (`urltestdata.json`), the IDNA conformance corpora (`IdnaTestV2`, `toascii`), Unicode's `NormalizationTest.txt`, and the RFC 3986 §5.4 resolution tables, behind a checked-in known-failures baseline that fails the build on any regression.

> Early development: version `0.1.0-SNAPSHOT`. kuri is pre-release and not yet published to any repository, and the public API may still change. The normative behavior is specified in [`docs/SPEC.md`](docs/SPEC.md).

## Contents

[Requirements](#requirements) ·
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

## Requirements

| | |
|---|---|
| **JVM runtime** | Java 8 or newer. The JVM artifact is compiled to Java 8 (`1.8`) bytecode for broad consumer compatibility. |
| **Kotlin consumers** | Kotlin 2.0 or newer. kuri is a Kotlin Multiplatform library; its public API lives in common Kotlin. |
| **Runtime dependencies** | None beyond the Kotlin standard library. The IDNA, Punycode, and Unicode pipelines are implemented in-library, with no third-party runtime dependencies. |
| **Build from source** | A JDK 21 toolchain. The bundled Gradle wrapper provisions everything else. |

## Installation

> **Not yet published.** The first release has not shipped. kuri is not on Maven Central, Sonatype, or any other repository, so it cannot be resolved as a dependency today. Until then, [build from source](#building-from-source).

The coordinates reserved for the first release are:

| Coordinate | Value |
|---|---|
| Group | `org.dexpace` |
| Artifact | `kuri` |
| Version | `0.1.0-SNAPSHOT` (pre-release) |

The artifact id is identical across Kotlin Multiplatform targets; the Gradle Kotlin Multiplatform plugin selects the right variant for your platform automatically. A copy-paste dependency snippet will be added here once the initial release is available.

## Quick start

```kotlin
import org.dexpace.kuri.Url
import org.dexpace.kuri.error.getOrThrow

val url = Url.parse("https://Example.com:443/a/../b?q=1#frag").getOrThrow()

url.scheme                     // "https"
url.hostName                   // "example.com"  — lower-cased
url.port                       // null           — the default :443 is elided
url.effectivePort              // 443
url.pathSegments               // ["b"]          — /a/../b is resolved
url.queryParameters.get("q")   // "1"
url.toString()                 // "https://example.com/b?q=1#frag"
```

From Java the same surface reads naturally:

```java
import org.dexpace.kuri.Url;

Url url = Url.parse("https://example.com/a?b=1").getOrThrow();
url.scheme();    // "https"
url.hostName();  // "example.com"
```

## Two models, one engine

|  | `Uri` | `Url` |
|---|---|---|
| Standard | RFC 3986 (RFC 3987-aware) | WHATWG URL |
| Posture | preserve the input; normalize on request | canonicalize eagerly |
| Scheme | any, and may be absent (relative reference) | always present; special schemes known |
| Host | reg-name / IPv4 / IPv6 / IP-future | IDNA, IPv4 shorthand, opaque hosts |
| Equality | structural, plus `normalizedEquals()` for RFC §6.2 | on the canonical serialization |

`Url.toUri()` is near-lossless; `Uri.toUrl()` may fail when a generic URI is not a valid web URL.

## Parsing and errors

Parsing returns a `ParseResult<T>` — errors are values, not exceptions — and you choose how to consume it:

```kotlin
Url.parse(input)                 // ParseResult<Url>  (Ok / Err)
Url.parse(input).getOrNull()     // Url?
Url.parse(input).getOrThrow()    // Url, or throws UriSyntaxException
Url.canParse(input)              // Boolean
Url.parse(input).fold(onOk = { it.host }, onErr = { it })
```

`Err` carries a structured `UriParseError` with the offending offset and reason, so a parser, a linter, and a fail-fast caller can each take what they need from the same call.

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

## Standards

kuri is built to the letter of the standards below. [RFC 3986][rfc3986] is the supreme authority: every place the `Url` profile diverges from it to satisfy the [WHATWG URL Standard][whatwg-url] is a registered, documented deviation (see [`docs/SPEC.md`](docs/SPEC.md)). Measured conformance against each standard's published test corpus is tracked, and only ever ratchets forward, in [Conformance](#conformance).

**Core syntax**

| Standard | Governs | Compliance | Support |
|---|---|---|---|
| [RFC 3986][rfc3986] (STD 66) | URI generic syntax; the `Uri` model and parsing authority | Conformant | Default |
| [RFC 3987][rfc3987] | Internationalized Resource Identifiers (IRIs) | Supported | Default |
| [WHATWG URL Standard][whatwg-url] | the `Url` model — parser, special schemes, canonical serialization | Ratcheting | Default |

**Hosts, internationalization, and IP addresses**

| Standard | Governs | Compliance | Support |
|---|---|---|---|
| [UTS #46][uts46] | Unicode IDNA Compatibility Processing (host ToASCII / ToUnicode) | Ratcheting | Default |
| [RFC 5891][rfc5891] | Internationalized Domain Names in Applications (IDNA2008) — protocol | Ratcheting | Default |
| [RFC 5892][rfc5892] | IDNA2008 — Unicode code points and derived properties | Ratcheting | Default |
| [RFC 3492][rfc3492] | Punycode — the Bootstring encoding of Unicode | Conformant | Default |
| [UAX #15][uax15] | Unicode Normalization Forms (NFC) | Conformant | Default |
| [RFC 5952][rfc5952] | IPv6 address text representation (canonical form) | Conformant | Default |
| [RFC 6874][rfc6874] | IPv6 zone identifiers in URLs | Opt-in | Opt-in |

**Query**

| Standard | Governs | Compliance | Support |
|---|---|---|---|
| [`application/x-www-form-urlencoded`][formenc] | Form-encoded query parsing and serialization | Conformant | Default |

**Notation and requirement levels**

| Standard | Governs | Compliance | Support |
|---|---|---|---|
| [RFC 5234][rfc5234] (STD 68) | ABNF — the grammar notation used by the specification | Notation | — |
| [RFC 2119][rfc2119] · [RFC 8174][rfc8174] (BCP 14) | Requirement-level keywords (MUST / SHOULD / MAY) | Notation | — |

**Compliance** — *Conformant*: passes the standard's conformance corpus, or its controlling table, with no known failures · *Ratcheting*: conformant except for a small set of cases pinned in the checked-in known-failures baseline, which can only shrink (see [Conformance](#conformance)) · *Opt-in*: conformant when explicitly enabled · *Supported*: implemented as an input dialect, not measured by a dedicated corpus of its own · *Notation*: used to author the specification, with no runtime behavior to conform to.

**Support** — *Default*: active in the default configuration of both profiles · *Opt-in*: available behind an explicit flag, off by default · *—*: not applicable.

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

Behavior is checked against the conformance corpora the standards ship with, not against hand-written approximations:

| Suite | Result |
|---|---|
| WHATWG `urltestdata.json` — parsing | 880 / 886 |
| WHATWG `urltestdata.json` — parse → serialize (`href`) | 611 / 611 |
| IDNA `IdnaTestV2` + `toascii` | 2732 / 2760 |
| Unicode `NormalizationTest.txt` (NFC) | 19 966 / 19 966 |
| RFC 3986 §5.4 reference resolution | all rows |

Any case that does not yet pass is pinned in a checked-in known-failures baseline. The build fails if a passing case regresses, so conformance can only ratchet forward; closing a known failure is a deliberate, reviewable change.

## Platforms

The entire public API lives in common Kotlin and compiles for every target below.

| Tier | Targets |
|---|---|
| JVM | `jvm` (Java 8+ bytecode) |
| JavaScript | `js` (browser, Node.js) |
| WebAssembly | `wasmJs` (browser, Node.js) |
| Native — Apple | `macosArm64`, `iosArm64`, `iosX64`, `iosSimulatorArm64`, `watchosArm64`, `watchosSimulatorArm64`, `tvosArm64`, `tvosSimulatorArm64` |
| Native — Linux | `linuxX64`, `linuxArm64` |
| Native — Windows | `mingwX64` |

The `java.net.URI` / `java.net.URL` conversions are JVM-only extensions. Every target compiles on any host; executing the native test suites requires a matching operating system or simulator.

## Versioning and stability

kuri follows [Semantic Versioning 2.0.0](https://semver.org/). At `0.1.0-SNAPSHOT` it is a pre-release, unpublished build: the public API is not yet frozen and may change before `1.0.0`, and minor releases in the `0.x` series may carry breaking changes. Pin to an exact version.

Even before `1.0.0`, the public surface is deliberate and machine-checked. Explicit-API strict mode requires every public declaration to state its visibility and return type, so nothing reaches the API by inference. The Kotlin binary-compatibility validator tracks the public ABI in a checked-in snapshot under `api/`; any added, removed, or altered public signature fails the build until the snapshot is regenerated with `./gradlew apiDump` and reviewed in the same change. Public-API changes are therefore always visible in the diff and never accidental.

## Building from source

Building kuri requires a JDK 21 toolchain; the bundled Gradle wrapper provisions the rest. The compiled bytecode targets Java 8, so the library itself runs on JDK 8 and newer.

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

- [`docs/SPEC.md`](docs/SPEC.md) — the normative behavior specification. It defines the character repertoire, the percent-encoding matrix, the host pipeline, the parsing algorithm, reference resolution, the query model, normalization and equivalence semantics, and the error model that a conforming implementation must exhibit for each profile.

## Contributing

Issues and pull requests are welcome on the [project repository](https://github.com/dexpace/kuri). Because correctness is enforced rather than assumed, `./gradlew build` must pass cleanly — including the conformance baselines and the full quality gate — before a change can merge; a change to the public API also needs an accompanying `./gradlew apiDump` with the regenerated snapshot committed. New or changed behavior should be grounded in the relevant standard and reflected in [`docs/SPEC.md`](docs/SPEC.md). Commits and pull-request titles use the `feat:` / `fix:` / `test:` / `docs:` / `chore:` prefix convention.

## Security

kuri is pre-release software with no formal security policy yet. Until one is published, please report suspected vulnerabilities privately to the maintainers rather than opening a public issue, so a fix can be prepared before disclosure.

## License

kuri is released under the [MIT License](LICENSE), Copyright (c) 2026 dexpace. MIT is an OSI-approved permissive license: it permits commercial use, modification, distribution, and private use, subject only to retaining the copyright and license notice.
