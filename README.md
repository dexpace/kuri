<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/kuri-dark.svg">
    <img alt="kuri" src="docs/assets/kuri-light.svg" width="420">
  </picture>
</p>

<h1 align="center">kuri</h1>

<p align="center">A standards-faithful URI and URL library for Kotlin and Java.</p>

<p align="center">
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/license-MIT-blue.svg"></a>
  <a href="https://kotlinlang.org"><img alt="Kotlin" src="https://img.shields.io/badge/kotlin-2.4.0-7F52FF.svg?logo=kotlin&logoColor=white"></a>
  <img alt="Kotlin Multiplatform" src="https://img.shields.io/badge/kotlin-multiplatform-7F52FF.svg?logo=kotlin&logoColor=white">
  <img alt="JDK" src="https://img.shields.io/badge/JDK-8%2B-437291.svg?logo=openjdk&logoColor=white">
  <img alt="Coverage" src="https://img.shields.io/badge/coverage-%E2%89%A580%25-success.svg">
</p>

kuri parses, builds, normalizes, and serializes URIs and URLs, and it does so by the book. It models two value types over a single engine: `Uri`, the generic identifier of **RFC 3986**, and `Url`, the web address of the **WHATWG URL Standard**. RFC 3986 is the governing authority; every place the web platform deliberately departs from it is a registered, documented deviation rather than an accident.

It is written in pure Kotlin with no `expect`/`actual` and no platform fallbacks, so the same parser — internationalized host names included — runs identically on the JVM, JavaScript, WebAssembly, and native. Punycode, UTS-46 (Unicode 16.0), and Unicode NFC normalization are implemented in Kotlin rather than delegated to `java.net.IDN`, so behavior never forks across targets. The JVM gets first-class Java interop on top: static factories, fluent accessors, builders, and `java.net.URI` / `java.net.URL` bridges.

Correctness is the contract. The engine is validated against the Web Platform Tests (`urltestdata.json`), the IDNA conformance corpora (`IdnaTestV2`, `toascii`), Unicode's `NormalizationTest.txt`, and the RFC 3986 §5.4 resolution tables, behind ratcheting baselines that fail the build on any regression.

> Early development: version `0.1.0-SNAPSHOT`. The normative behavior is specified in [`docs/SPEC.md`](docs/SPEC.md); the public API may still change.

## Contents

[Quick start](#quick-start) ·
[Two models](#two-models-one-engine) ·
[Parsing and errors](#parsing-and-errors) ·
[Building and resolving](#building-and-resolving) ·
[Conformance](#conformance) ·
[Platforms](#platforms) ·
[Building](#building) ·
[License](#license)

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

## Conformance

| Suite | Result |
|---|---|
| WHATWG `urltestdata.json` — parsing | 880 / 886 |
| WHATWG `urltestdata.json` — parse → serialize (`href`) | 611 / 611 |
| IDNA `IdnaTestV2` + `toascii` | 2732 / 2760 |
| Unicode `NormalizationTest.txt` (NFC) | 19 966 / 19 966 |
| RFC 3986 §5.4 reference resolution | all rows |

The remaining cases are deliberately-scoped follow-ups, tracked as issues and pinned by ratcheting baselines so they can only shrink.

## Platforms

The public API lives in common Kotlin and compiles for every target:

`jvm` · `js` (browser + Node) · `wasmJs` · `macosArm64` · `iosArm64` · `iosSimulatorArm64` · `iosX64` · `watchosArm64` · `watchosSimulatorArm64` · `tvosArm64` · `tvosSimulatorArm64` · `linuxX64` · `linuxArm64` · `mingwX64`

The `java.net.URI` / `java.net.URL` conversions are JVM-only extensions. Every target compiles on any host; native test *execution* needs a matching OS or simulator, and the full matrix runs in CI across Linux, macOS, and Windows.

## Building

```
./gradlew build
```

This compiles every target and runs the quality gate: ktlint, detekt, explicit-API checks, the binary-compatibility validator, and an 80% Kover line-coverage floor.

After an intentional public-API change, regenerate and commit the API snapshot:

```
./gradlew apiDump
```

## License

MIT — see [LICENSE](LICENSE).
