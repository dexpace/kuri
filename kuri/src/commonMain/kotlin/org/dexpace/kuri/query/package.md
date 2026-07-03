# Package org.dexpace.kuri.query

The query-string model: decoded `name=value` access and form encoding.

[QueryParameters] is an immutable, ordered, duplicate-preserving, case-sensitive snapshot of a
URL's decoded query pairs, obtained from [Url.queryParameters][org.dexpace.kuri.Url.queryParameters]
or built directly with [QueryParameters.parse] (from a raw query string) and [QueryParameters.of]
(from a `Map`). It has value semantics: two snapshots with the same pairs in the same order are
equal and share a hash. It distinguishes a pair with no `=` (a `null` value) from a pair whose value
is the empty string, and both survive a round-trip through [QueryParameters.toQueryString]. Lookups
come in first-match ([QueryParameters.get]), all-matches ([QueryParameters.getAll]), and positional
([QueryParameters.nameAt] / [QueryParameters.valueAt]) forms; the snapshot is [Iterable] over its
pairs and projects to a first-value-wins map via [QueryParameters.toMap].

The snapshot is never a live view; mutation goes through [QueryParametersBuilder]
(seeded from a snapshot via [QueryParameters.newBuilder]), whose [add][QueryParametersBuilder.add],
[addAll][QueryParametersBuilder.addAll], [set][QueryParametersBuilder.set],
[removeAll][QueryParametersBuilder.removeAll], and [sort][QueryParametersBuilder.sort] operations
follow the WHATWG `URLSearchParams` semantics and produce a fresh snapshot via
[build][QueryParametersBuilder.build]. The `application/x-www-form-urlencoded` dialect — the only
place where `+` means space — is handled separately so the generic query model stays free of
form-encoding quirks.
