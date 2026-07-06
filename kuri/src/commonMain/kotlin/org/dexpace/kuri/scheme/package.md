# Package org.dexpace.kuri.scheme

Scheme metadata: default ports and WHATWG special-scheme classification.

[Schemes] answers scheme-level questions without a full parse. [Schemes.defaultPort] returns the
default port a WHATWG special scheme elides, or `null` for a scheme with none; [Schemes.isSpecial]
reports whether a scheme is one of the WHATWG special schemes (`http`, `https`, `ws`, `wss`, `ftp`,
`file`), whose authority, path, and origin rules differ from a generic scheme's; and [Schemes.isValid]
checks a string against the RFC 3986 `scheme` grammar (an ASCII letter followed by letters, digits,
`+`, `-`, or `.`). The same classification drives port elision and special-scheme handling in
[Url][org.dexpace.kuri.Url].
