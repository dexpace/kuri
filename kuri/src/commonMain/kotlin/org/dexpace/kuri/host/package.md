# Package org.dexpace.kuri.host

The structured host model shared by [Uri][org.dexpace.kuri.Uri] and [Url][org.dexpace.kuri.Url].

[Host] is a sealed type, so the host *kind* is part of the type and drives serialization
exhaustively. Its variants are [Host.RegName] (a registered name or canonical domain),
[Host.Ipv4] (a 32-bit address), [Host.Ipv6] (eight 16-bit groups with an optional RFC 6874 zone id),
[Host.IpFuture] (an RFC 3986 `IPvFuture` literal, reachable only under the `Uri` profile),
[Host.Opaque] (a non-special-scheme opaque host or a preserved reg-name), and [Host.Empty]
(an authority whose host is the empty string, e.g. `file:///x`). A `null` host means there is no
authority at all and must not be conflated with [Host.Empty].

Stored values are always already-canonical and never carry the surrounding `[`/`]` brackets —
bracketing is a serialization concern. [Host.serialize] renders a host back to its canonical
authority text, reapplying the RFC 3986 §3.2.2 / WHATWG §11.2 bracketing rules for IPv6 and
IP-future literals.
