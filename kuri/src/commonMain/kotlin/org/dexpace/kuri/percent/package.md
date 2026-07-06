# Package org.dexpace.kuri.percent

Percent-encoding and -decoding for a single URI component.

[Percent] percent-encodes and -decodes an arbitrary component against the RFC 3986 / WHATWG encode
sets, for callers who want the transform without the JVM-only, form-shaped `URLEncoder`/`URLDecoder`.
[Percent.encode] escapes a string for a chosen [Percent.Component] — the destination
([PATH_SEGMENT][Percent.Component.PATH_SEGMENT], [QUERY][Percent.Component.QUERY],
[FRAGMENT][Percent.Component.FRAGMENT], [USER_INFO][Percent.Component.USER_INFO], or the strictest
`encodeURIComponent`-equivalent [COMPONENT][Percent.Component.COMPONENT]) whose encode set decides
which octets pass through — and [Percent.decode] reverses every `%XX` triplet as UTF-8, leaving a lone
or malformed `%` verbatim. The component-scoped sets are the same ones the parser and serializer
apply, so a value encoded here slots into the matching position of a [Uri][org.dexpace.kuri.Uri] or
[Url][org.dexpace.kuri.Url] unchanged.
