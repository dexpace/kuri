# Package org.dexpace.kuri

The entry point to **kuri**, a URI/URL parsing and manipulation library for Kotlin and Java.

Two immutable value types model the two standards. [Uri] is an RFC 3986 generic URI: it is
*preserve-by-default*, keeps the input's original case and dot-segments, may be a relative reference
(its scheme and authority can be absent), and folds the RFC 3986 §6.2 equivalences only when you ask
for them via [Uri.normalized] or [Uri.normalizedEquals]. [Url] is a WHATWG URL Living Standard value:
it is eagerly canonical, always carries a scheme, and (for special schemes) a host. Both expose their
components as pure projections — every accessor reads stored state and never re-parses or performs
I/O, so a parsed value is a safe `Map`/`Set` key.

Parsing never throws: the `parse` factories return a [org.dexpace.kuri.error.ParseResult], with
`parseOrNull`/`canParse` convenience variants. For programmatic assembly each type offers a
Java-constructible `Builder` (and a pre-filled `newBuilder()`); a [Uri] and a [Url] can be bridged to
each other ([Uri.toUrl], [Url.toUri]), and on the JVM to and from `java.net.URI`/`java.net.URL` via
the extension functions in this package (`toJavaUri`, `toJavaUrl`, `toKuriUri`, `toKuriUrl`).

```kotlin
val url = Url.parse("https://example.com/path?q").getOrThrow()
url.host        // RegName("example.com")
url.encodedPath // "/path"
url.query       // "q"
```

Supporting models live in the sibling packages: the sealed host model in
[org.dexpace.kuri.host], the query-string model in [org.dexpace.kuri.query], and the
[ParseResult][org.dexpace.kuri.error.ParseResult] outcome plus the structured error model in
[org.dexpace.kuri.error].
