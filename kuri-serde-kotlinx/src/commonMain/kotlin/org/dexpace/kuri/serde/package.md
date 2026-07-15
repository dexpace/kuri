# Package org.dexpace.kuri.serde

kotlinx.serialization support for kuri.

[UrlSerializer]/[UriSerializer] represent a `Url`/`Uri` as its canonical string, so a URL field
round-trips through any kotlinx format (JSON, protobuf, CBOR, …) transparently — apply per property,
per file with `@file:UseSerializers`, or contextually.

[QueryParametersFormat] is a small format that maps a flat `@Serializable` class to and from
`QueryParameters` — the typed **decode** direction (query string → object) that `kuri-bind`'s
object → URL binding does not cover. Scalars become parameters; a `List` property repeats its parameter;
absent optionals fall back to their default, and — symmetrically — a property still at its default is
omitted when encoding.

The module depends only on `kotlinx-serialization-core` — it imposes no concrete format on consumers.
