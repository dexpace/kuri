# Package org.dexpace.kuri.idna

UTS-46 / IDNA conversion for a bare hostname.

[Idn] exposes the bundled UTS-46 processor for callers who need to convert a domain outside of a full
parse. [Idn.toAscii] maps a Unicode domain to its ASCII (Punycode / `xn--`) form under the WHATWG
"domain to ASCII" profile, returning a [ParseResult][org.dexpace.kuri.error.ParseResult] because a
disallowed or malformed label is a recoverable failure, not an exception; [Idn.toUnicode] applies the
inverse presentation mapping, which is total and always yields a string. The same processor backs host
parsing in [Url][org.dexpace.kuri.Url], so a hostname converted here matches the one a parse produces.
