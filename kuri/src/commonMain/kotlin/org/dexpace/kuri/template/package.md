# Package org.dexpace.kuri.template

An [RFC 6570](https://www.rfc-editor.org/rfc/rfc6570) URI Template implementation — the standards-based,
string-first companion to `kuri-bind`'s annotation binding.

`UriTemplate.parse(...)` compiles a template once; `expand(variables)` produces a URI reference. All
four RFC levels are covered: the simple form plus the `+ # . / ; ? &` operators, and the prefix
(`{var:3}`) and explode (`{list*}`) modifiers over string, list, and associative-array values.
Expansion follows the "errors are values"/"undefined contributes nothing" contract — it never throws;
only [UriTemplate.parse] fails, on a malformed template, via [UriTemplateException].

`UriTemplate.match(input)` is a conservative reverse-matching **extension** (RFC 6570 defines expansion
only): it inverts the common path-routing shape and returns `null` rather than a guess when a template
uses a feature it cannot invert unambiguously.
