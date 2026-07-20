# Package org.dexpace.kuri.error

The parse outcome and the structured error model.

Recoverable parse failures are values, not control flow: every fallible entry point returns a
[ParseResult], a sealed type with exactly two cases — [ParseResult.Ok] carrying the produced value
and [ParseResult.Err] carrying the fatal cause — so a `when` over it is exhaustive without an `else`.
The extension helpers [getOrNull], [getOrThrow], [isOk], [map], and [fold] cover the common
consumption patterns; [getOrThrow] is the bridge to exceptions.

[UriParseError] is the sealed catalog of fatal failures (invalid scheme, missing scheme, invalid
percent-encoding, invalid port, empty or invalid host, forbidden host code point, input too long),
each variant carrying enough context — typically an offset into the original input or an explanatory
sub-value — to locate and explain the problem. A rejected host additionally carries a [HostError]
discriminating *why* the §7 host pipeline failed. [ValidationError] is the record of a single
non-fatal WHATWG anomaly that a lenient parser silently repairs, pairing a closed [ValidationErrorKind]
with the offset it occurred at and whether that kind is WHATWG failure-class; these are observational
and never downgrade an [ParseResult.Ok] to an [ParseResult.Err]. For callers that prefer exceptions,
[UriSyntaxException] is an `IllegalArgumentException` that wraps the same structured [UriParseError].
