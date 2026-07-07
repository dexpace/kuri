# Changelog

All notable changes to kuri are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html). While kuri is in the `0.x`
series the public API is not yet frozen and may change between minor releases.

## [0.1.0-alpha.3](https://github.com/dexpace/kuri/compare/v0.1.0-alpha.2...v0.1.0-alpha.3) (2026-07-07)


### Added

* add kuri-bind, an annotation-driven URL/URI binding module ([#40](https://github.com/dexpace/kuri/issues/40)) ([8d25bbb](https://github.com/dexpace/kuri/commit/8d25bbb5f37beecc159ddb3f8d16ae0b76d53e84))

## [0.1.0-alpha.2](https://github.com/dexpace/kuri/compare/v0.1.0-alpha.1...v0.1.0-alpha.2) (2026-07-06)


### Added

* broaden the public API for encoding, IDNA, query, path, and resolution work ([#33](https://github.com/dexpace/kuri/issues/33)) ([8c69e0c](https://github.com/dexpace/kuri/commit/8c69e0cfbd44a09d7e0d9fe57cac3b4d9004cb9c))

## [0.1.0-alpha.1] - 2026-07-04

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
