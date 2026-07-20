# Changelog

All notable changes to kuri are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html). While kuri is in the `0.x`
series the public API is not yet frozen and may change between minor releases.

## [0.1.1](https://github.com/dexpace/kuri/compare/v0.1.0...v0.1.1) (2026-07-20)


### Added

* add configurable resource limits and a LimitExceeded parse error ([#156](https://github.com/dexpace/kuri/issues/156)) ([40fa407](https://github.com/dexpace/kuri/commit/40fa40720b2b34a00d79d1c2578a744cc750742b))
* add Kotlin DSL sugar, URI templates, and a kotlinx.serialization bridge ([#74](https://github.com/dexpace/kuri/issues/74)) ([a2feca0](https://github.com/dexpace/kuri/commit/a2feca049c12f9f71b3f44ad3ac390d094a816d4))
* add kuri-bind, an annotation-driven URL/URI binding module ([#40](https://github.com/dexpace/kuri/issues/40)) ([8d25bbb](https://github.com/dexpace/kuri/commit/8d25bbb5f37beecc159ddb3f8d16ae0b76d53e84))
* add Uri/Url redact() and isDirectory()/hasTrailingSlash() ([#138](https://github.com/dexpace/kuri/issues/138)) ([326b5a0](https://github.com/dexpace/kuri/commit/326b5a021d58f25e8f7c99dc0f2f13b84dbdb4d5))
* broaden the public API for encoding, IDNA, query, path, and resolution work ([#33](https://github.com/dexpace/kuri/issues/33)) ([8c69e0c](https://github.com/dexpace/kuri/commit/8c69e0cfbd44a09d7e0d9fe57cac3b4d9004cb9c))
* delimited multi-value query params — QueryParameters.split and @Query(delimiter) ([#56](https://github.com/dexpace/kuri/issues/56)) ([cce1fb7](https://github.com/dexpace/kuri/commit/cce1fb74528909d272bbbccd9c76a7c5b2d314d8))
* WHATWG URL component setters and expanded conformance test coverage ([#54](https://github.com/dexpace/kuri/issues/54)) ([45058b1](https://github.com/dexpace/kuri/commit/45058b1497f11761ce617d682b30527735bd314e))


### Fixed

* add decoded userinfo/fragment accessors and fix % round-tripping in kuri-bind ([#79](https://github.com/dexpace/kuri/issues/79)) ([f125f87](https://github.com/dexpace/kuri/commit/f125f873226a6ad06e0d2f4bfc6bdec1699b4af6))
* bound the kuri-bind reflective plan/scan caches ([#141](https://github.com/dexpace/kuri/issues/141)) ([cb64b12](https://github.com/dexpace/kuri/commit/cb64b121130541416f5dfcde79947a00e0cb988e))
* clarify operator grouping and resolve an ambiguous path-string overload ([#80](https://github.com/dexpace/kuri/issues/80)) ([108089f](https://github.com/dexpace/kuri/commit/108089f246cf5e505e4bff321ee8a3d413fe061f))
* correct kuri-bind KDoc/test/message accuracy gaps ([#136](https://github.com/dexpace/kuri/issues/136)) ([19c71e0](https://github.com/dexpace/kuri/commit/19c71e0c9c3854e8d429adad0f20c3eb1b40f1dd))
* emit an empty-list marker so query encoding round-trips empty lists ([#143](https://github.com/dexpace/kuri/issues/143)) ([246a2a1](https://github.com/dexpace/kuri/commit/246a2a1cc7402bee15aaef41dccab7a7cc5eee0d))
* enforce RFC 3987 IRI rules and close IDNA, WPT, and BCV gaps ([#71](https://github.com/dexpace/kuri/issues/71)) ([46ea05f](https://github.com/dexpace/kuri/commit/46ea05fec66ee20967dd971c72f89d28b2fbc466))
* implement Map&lt;K,V&gt; support in the query serde format ([#153](https://github.com/dexpace/kuri/issues/153)) ([d494039](https://github.com/dexpace/kuri/commit/d4940392199e8f84db4802d5322fa493f33eb45e))
* model Uri userinfo username/password as nullable ([#142](https://github.com/dexpace/kuri/issues/142)) ([fa2e93f](https://github.com/dexpace/kuri/commit/fa2e93f07a504954e4d722a782521cc85f98f77d))
* reject invalid boolean query values instead of coercing to false ([#135](https://github.com/dexpace/kuri/issues/135)) ([410dcad](https://github.com/dexpace/kuri/commit/410dcadd30528e2ffcf111b76a5e27aea3cd782a))
* reject leading sign and leading zero in template prefix ([#129](https://github.com/dexpace/kuri/issues/129)) ([4245dee](https://github.com/dexpace/kuri/commit/4245deeb4b0493e4012d6fb6ad19751214fc6259))
* reject nested objects inside list elements in the query format ([#147](https://github.com/dexpace/kuri/issues/147)) ([442dce3](https://github.com/dexpace/kuri/commit/442dce34a8941a7af0a209118ae44dd953d13829))
* reject path template holes that share a segment with literal text ([#132](https://github.com/dexpace/kuri/issues/132)) ([111072b](https://github.com/dexpace/kuri/commit/111072bcaa8f17a38e0aa1c88c68e8f859da0a3f))
* repair malformed percent escapes before Url.toUri()'s Uri hand-off ([#133](https://github.com/dexpace/kuri/issues/133)) ([bb3c1cd](https://github.com/dexpace/kuri/commit/bb3c1cd81d9ffa235849768457f851934f763c59))
* report ForbiddenHostCodePoint at the original-input offset ([#154](https://github.com/dexpace/kuri/issues/154)) ([8b3e1f3](https://github.com/dexpace/kuri/commit/8b3e1f3a7e043484462cdd906f6aa1d8982b1e65))
* return null instead of -1 from Url.effectivePort ([#131](https://github.com/dexpace/kuri/issues/131)) ([43b5f1d](https://github.com/dexpace/kuri/commit/43b5f1ddb718fa279e645c4a25bac67bd4adcd99))
* scope IDNA ASCII web-compat leniency to the whole domain ([#134](https://github.com/dexpace/kuri/issues/134)) ([abb1284](https://github.com/dexpace/kuri/commit/abb128442df3e31a615e5dc0c44a13ecd5a0478d))
* scope smoke-test token to read and fix CodeQL comment ([#140](https://github.com/dexpace/kuri/issues/140)) ([29e7e4f](https://github.com/dexpace/kuri/commit/29e7e4f3d596a1a45319894e4321749a5bd5d57b))
* several host, IPv6, IDNA, and resolver correctness fixes ([#68](https://github.com/dexpace/kuri/issues/68)) ([d00b6b9](https://github.com/dexpace/kuri/commit/d00b6b94e2827a4e797c5c805cb275d67013ee82))
* sort query parameter names by UTF-16 code unit ([#130](https://github.com/dexpace/kuri/issues/130)) ([0604b01](https://github.com/dexpace/kuri/commit/0604b01849d075c42cf8c1d0a0d9862ad8e38d29))
* turn ValidationError into a kind+offset record ([#155](https://github.com/dexpace/kuri/issues/155)) ([e37ac30](https://github.com/dexpace/kuri/commit/e37ac305801449799609a08452ce4e1db474b85c))
* wrap malformed query scalar values in SerializationException ([#137](https://github.com/dexpace/kuri/issues/137)) ([d5471f8](https://github.com/dexpace/kuri/commit/d5471f8776a68452423b0679535986f6407f191a))


### Performance

* memoize derived path/query projections on Uri and Url ([#52](https://github.com/dexpace/kuri/issues/52)) ([babc746](https://github.com/dexpace/kuri/commit/babc746f9ab3c466f863d17052eef50ecf420345))

## [0.1.0-alpha.4](https://github.com/dexpace/kuri/compare/v0.1.0-alpha.3...v0.1.0-alpha.4) (2026-07-10)


### Added

* delimited multi-value query params — QueryParameters.split and @Query(delimiter) ([#56](https://github.com/dexpace/kuri/issues/56)) ([cce1fb7](https://github.com/dexpace/kuri/commit/cce1fb74528909d272bbbccd9c76a7c5b2d314d8))
* WHATWG URL component setters and expanded conformance test coverage ([#54](https://github.com/dexpace/kuri/issues/54)) ([45058b1](https://github.com/dexpace/kuri/commit/45058b1497f11761ce617d682b30527735bd314e))


### Performance

* memoize derived path/query projections on Uri and Url ([#52](https://github.com/dexpace/kuri/issues/52)) ([babc746](https://github.com/dexpace/kuri/commit/babc746f9ab3c466f863d17052eef50ecf420345))

## [0.1.0-alpha.3](https://github.com/dexpace/kuri/compare/v0.1.0-alpha.2...v0.1.0-alpha.3) (2026-07-07)


### Added

* add kuri-bind, an annotation-driven URL/URI binding module ([#40](https://github.com/dexpace/kuri/issues/40)) ([8d25bbb](https://github.com/dexpace/kuri/commit/8d25bbb5f37beecc159ddb3f8d16ae0b76d53e84))

## [0.1.0-alpha.2](https://github.com/dexpace/kuri/compare/v0.1.0-alpha.1...v0.1.0-alpha.2) (2026-07-06)


### Added

* broaden the public API for encoding, IDNA, query, path, and resolution work ([#33](https://github.com/dexpace/kuri/issues/33)) ([8c69e0c](https://github.com/dexpace/kuri/commit/8c69e0cfbd44a09d7e0d9fe57cac3b4d9004cb9c))

## [0.1.0-alpha.1](https://github.com/dexpace/kuri/compare/2e326314c97586e2551e0099e35376a8236cac93...v0.1.0-alpha.1) (2026-07-04)

### Added

- `ParseResult` now carries its common accessors as members, so `isOk()`, `getOrNull()`, and
  `getOrThrow()` work identically from Kotlin and Java (`result.getOrThrow()` in both).
- `Uri.parseOrThrow(input, options)` and `Url.parseOrThrow(input, base)` — the throwing counterpart
  of `parse`, for call sites that prefer an exception to a `ParseResult` branch.
- `Url` gained base-relative overloads: `Url.parseOrNull(input, base)` and
  `Url.canParse(input, base)` resolve a reference against a base URL. The base is optional — omit it
  or pass `null` for an absolute parse — so the whole `parse`/`parseOrThrow`/`parseOrNull`/`canParse`
  family accepts a nullable base uniformly (a null base no longer throws from `parseOrNull`/`canParse`).
- `UriParseError.message` — a stable, human-readable rendering of a parse failure, readable without
  throwing (`error.getMessage()` from Java).
- `Uri.Builder.addPathSegment(segment)` and `Uri.Builder.addEncodedPathSegment(segment)` append a
  single path segment. The encoded variant rejects a segment that contains a raw `/`, `\`, `?`, or
  `#`, since a segment cannot span those delimiters; the decoded variant percent-encodes them. When
  no authority is present the segments compose into a rootless path (`urn:`, `mailto:`), and they
  gain a leading `/` once a host is set — independent of the order in which segments and the host are
  added. `Uri.encodedPath()` exposes the percent-encoded path under a name shared with
  `Url.encodedPath()`.
- `QueryParameters` is now a value type: `equals`/`hashCode`/`toString` compare the full ordered pair
  sequence. It is `Iterable<QueryParameter>` and projects to a first-value-wins map via `toMap()`,
  with an operator `get`, an operator `contains`, and a `size` accessor (`size()` from Java).
- `QueryParameters.parse(String)` and `QueryParameters.of(Map)` static factories build a snapshot
  directly from a raw query string or a map.
- `QueryParameter(name, value)` — the public data class yielded when a `QueryParameters` snapshot is
  iterated.
- `QueryParametersBuilder.addAll(Map)` appends every entry of a map in iteration order.
- `Host.asText()` renders a host back to its canonical authority text, and `Host.Ipv4.octets()`
  exposes the four address bytes as an `IntArray`, high-order octet first.

### Changed

- `QueryParameters.newBuilder()` is now a member method rather than an extension, so it is directly
  callable from Java.

### Removed

- The static result accessors generated as `ParseResultKt.getOrThrow`, `ParseResultKt.getOrNull`, and
  `ParseResultKt.isOk` are gone; call the members on `ParseResult` instead.
- The static `QueryParametersBuilderKt.newBuilder`; use the `QueryParameters.newBuilder()` member.
- `Kuri.version()`; read the `Kuri.VERSION` constant instead.
