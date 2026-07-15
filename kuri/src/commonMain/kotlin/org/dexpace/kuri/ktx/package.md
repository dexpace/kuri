# Package org.dexpace.kuri.ktx

Kotlin-only ergonomics over the core `Url`/`Uri` value types: path (`/`) and query (`+`) operators,
query reads (`url["k"]`, `"k" in url`), the `edit { }` scoped-builder transform, the `buildUrl { }` /
`buildUri { }` DSL, `String.toUrl()` / `toUri()` parse helpers, and `Url(...)` / `Uri(...)`
constructor-style factories.

Every declaration is `@JvmSynthetic` — invisible from Java — so this module adds Kotlin elegance
without widening or duplicating the deliberately plain Java surface of `:kuri`. Values are immutable:
each operator returns a new value and never mutates its receiver. For multi-step edits prefer
`edit { }` (one builder, one `build()`) over a chain of value operators.
