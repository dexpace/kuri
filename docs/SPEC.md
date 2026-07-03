# kuri — URI/URL Parsing & Manipulation Specification

**Version 0.1.0-draft · 2026-06-28 · Layers 1–2**

`kuri` is a Kotlin/JVM library for parsing, building, manipulating, and serializing Uniform Resource Identifiers (URIs) and Uniform Resource Locators (URLs). It is built on the observation that the two dominant identifier specifications — the IETF RFC 3986/3987 generic-URI syntax and the WHATWG URL Living Standard — describe overlapping but materially incompatible models, and that no single parsing posture can satisfy both audiences. `kuri` therefore exposes two public value types, `Uri` and `Url`, backed by one shared parsing engine configured by a `ParseProfile`. This document is the normative specification for that engine and its public surface: it defines the character repertoire, percent-encoding matrix, host pipeline, parsing algorithm, reference-resolution rules, query model, normalization and equivalence semantics, and error model that a conforming implementation MUST exhibit, for each profile, together with the conformance classes and test corpora against which conformance is measured.

---

## Status of This Document

This is the **kuri Specification, version 0.1.0-draft, dated 2026-06-28**. It is a working draft and is subject to change.

This document specifies **layers 1 and 2** of the `kuri` architecture:

- **Layer 1** — the internal RFC/WHATWG parse-and-serialize engine (the profile-parameterized state machine, the host module, and the percent-encoding module).
- **Layer 2** — the public API surface (`Uri`, `Url`, their builders, the `Host` model, `QueryParameters`, and the parse/error result types).

**Layer 3** — framework and ecosystem integrations (HTTP-client adapters, serialization-framework codecs, dependency-injection bindings, and similar) — is **out of scope** for this document and is specified elsewhere.

Because this is a `0.1.0-draft`, requirement numbers and identifiers are stable within this revision but MAY be renumbered in a future revision; downstream references SHOULD cite both the requirement identifier and the specification version.

---

## Table of Contents

- [1. Introduction & Conformance Model](#1-introduction--conformance-model)
  - [1.1 Purpose & Scope](#11-purpose--scope)
  - [1.2 The Two-Profile Architecture](#12-the-two-profile-architecture)
  - [1.3 Conformance](#13-conformance)
    - [1.3.1 Precedence of authorities](#131-precedence-of-authorities)
  - [1.4 Normative References](#14-normative-references)
  - [1.5 Informative References](#15-informative-references)
- [2. Terminology & Notation](#2-terminology--notation)
  - [2.1 Code points and characters](#21-code-points-and-characters)
  - [2.2 Schemes, special schemes, and ports](#22-schemes-special-schemes-and-ports)
  - [2.3 URI/URL strings and components](#23-uriurl-strings-and-components)
  - [2.4 Percent-encoding terms](#24-percent-encoding-terms)
  - [2.5 References, base, and resolution](#25-references-base-and-resolution)
  - [2.6 Output, equivalence, and profile](#26-output-equivalence-and-profile)
  - [2.7 Validation errors and failure](#27-validation-errors-and-failure)
  - [2.8 Internationalization terms](#28-internationalization-terms)
  - [2.9 Notation](#29-notation)
- [3. Data Model](#3-data-model)
  - [3.1 The abstract URI record](#31-the-abstract-uri-record)
  - [3.2 Null versus empty: the central invariant](#32-null-versus-empty-the-central-invariant)
  - [3.3 Scheme](#33-scheme)
  - [3.4 Userinfo: user and password modelled independently](#34-userinfo-user-and-password-modelled-independently)
  - [3.5 Host](#35-host)
  - [3.6 Port](#36-port)
  - [3.7 Path](#37-path)
  - [3.8 Query (raw + parameter view)](#38-query-raw--parameter-view)
  - [3.9 Fragment](#39-fragment)
  - [3.10 Authority and other derived projections](#310-authority-and-other-derived-projections)
  - [3.11 Immutability and construction](#311-immutability-and-construction)
  - [3.12 Equality and hashing (model-level contract)](#312-equality-and-hashing-model-level-contract)
  - [3.13 The `Uri` ↔ `Url` relationship](#313-the-uri--url-relationship)
  - [3.14 Interop constraints on the model](#314-interop-constraints-on-the-model)
- [4. Character Repertoire & Grammar](#4-character-repertoire--grammar)
  - [4.1 RFC 3986 Collected ABNF](#41-rfc-3986-collected-abnf)
  - [4.2 WHATWG Code-Point Classes](#42-whatwg-code-point-classes)
  - [4.3 Reconciliation: RFC 3986 Productions vs WHATWG Classes](#43-reconciliation-rfc-3986-productions-vs-whatwg-classes)
- [5. Percent-Encoding](#5-percent-encoding)
  - [5.1 Percent-encode sets](#51-percent-encode-sets)
  - [5.2 The percent-encode algorithm](#52-the-percent-encode-algorithm)
  - [5.3 The percent-decode algorithm](#53-the-percent-decode-algorithm)
  - [5.4 Triplet case normalization](#54-triplet-case-normalization)
  - [5.5 Already-encoded input and the idempotency contract](#55-already-encoded-input-and-the-idempotency-contract)
- [6. Schemes](#6-schemes)
  - [6.1 The special-scheme registry](#61-the-special-scheme-registry)
  - [6.2 Scheme syntax and validation](#62-scheme-syntax-and-validation)
  - [6.3 Scheme normalization](#63-scheme-normalization)
  - [6.4 Scheme, `effectivePort`, and default-port elision](#64-scheme-effectiveport-and-default-port-elision)
  - [6.5 Non-special scheme semantics](#65-non-special-scheme-semantics)
- [7. Host Parsing](#7-host-parsing)
  - [7.1 Host end-delimiter detection and dispatch](#71-host-end-delimiter-detection-and-dispatch)
  - [7.2 IPv6 literals](#72-ipv6-literals)
  - [7.3 IPv4 addresses](#73-ipv4-addresses)
  - [7.4 Registered names and domains via IDNA (UTS-46)](#74-registered-names-and-domains-via-idna-uts-46)
  - [7.5 Opaque host / reg-name preserve pipeline](#75-opaque-host--reg-name-preserve-pipeline)
  - [7.6 Forbidden code-point tables (normative)](#76-forbidden-code-point-tables-normative)
  - [7.7 Empty-host rules](#77-empty-host-rules)
  - [7.8 IP-future (`Uri` profile only)](#78-ip-future-uri-profile-only)
  - [7.9 Mapping of outcomes to `Host` variants](#79-mapping-of-outcomes-to-host-variants)
- [8. The Parsing Algorithm](#8-the-parsing-algorithm)
  - [8.1 Pre-processing](#81-pre-processing)
  - [8.2 Pointer and EOF model](#82-pointer-and-eof-model)
  - [8.3 States](#83-states)
  - [8.4 Userinfo splitting](#84-userinfo-splitting)
  - [8.5 Profile-gated quirk branches](#85-profile-gated-quirk-branches)
- [9. Paths & Reference Resolution](#9-paths--reference-resolution)
  - [9.1 Path model](#91-path-model)
  - [9.2 Dot-segment removal](#92-dot-segment-removal)
  - [9.3 Backslash conversion (special schemes, `Url` profile)](#93-backslash-conversion-special-schemes-url-profile)
  - [9.4 `file` Windows drive letters](#94-file-windows-drive-letters)
  - [9.5 Opaque paths (cannot-be-a-base URLs)](#95-opaque-paths-cannot-be-a-base-urls)
  - [9.6 Path/authority structural constraints](#96-pathauthority-structural-constraints)
  - [9.7 Reference resolution](#97-reference-resolution)
- [10. Query & Form Encoding](#10-query--form-encoding)
  - [10.1 The raw query component](#101-the-raw-query-component)
  - [10.2 The `QueryParameters` model](#102-the-queryparameters-model)
  - [10.3 Read API and Builder](#103-read-api-and-builder)
  - [10.4 `application/x-www-form-urlencoded`](#104-applicationx-www-form-urlencoded)
  - [10.5 Resource bound on pair parsing](#105-resource-bound-on-pair-parsing)
- [11. Normalization, Serialization & Equivalence](#11-normalization-serialization--equivalence)
  - [11.1 Normalization](#111-normalization)
  - [11.2 Serialization](#112-serialization)
  - [11.3 Equality & hashCode contract](#113-equality--hashcode-contract)
  - [11.4 Round-trip & idempotency](#114-round-trip--idempotency)
  - [11.5 `toUri()` / `toUrl()` bridges](#115-touri--tourl-bridges)
- [12. Error Handling, Validation & Resource Limits](#12-error-handling-validation--resource-limits)
  - [12.1 The `ParseResult<out T>` ADT](#121-the-parseresultout-t-adt)
  - [12.2 `UriParseError` — the fatal-error catalog](#122-uriparseerror--the-fatal-error-catalog)
  - [12.3 `ValidationError` — non-fatal anomalies](#123-validationerror--non-fatal-anomalies)
  - [12.4 Strict vs lenient semantics per profile](#124-strict-vs-lenient-semantics-per-profile)
  - [12.5 Reject-vs-normalize decision table](#125-reject-vs-normalize-decision-table)
  - [12.6 Resource limits / DoS bounds](#126-resource-limits--dos-bounds)
  - [12.7 Foreign-exception translation boundary](#127-foreign-exception-translation-boundary)
- [13. Conformance Requirements & Test Corpora](#13-conformance-requirements--test-corpora)
  - [13.1 Conformance classes](#131-conformance-classes)
  - [13.2 Required external test corpora](#132-required-external-test-corpora)
  - [13.3 Edge-case master checklist (normative)](#133-edge-case-master-checklist-normative)
  - [13.4 Known failures and opt-in policy](#134-known-failures-and-opt-in-policy)
  - [13.5 Specification versioning and stability](#135-specification-versioning-and-stability)

- [Appendix A — Requirements Index](#appendix-a--requirements-index)
- [Appendix B — Deviations from RFC 3986](#appendix-b--deviations-from-rfc-3986)

---

## 1. Introduction & Conformance Model

### 1.1 Purpose & Scope

`kuri` provides four capabilities over URIs and URLs: **parsing** textual input into a structured model, **building** a model programmatically, **manipulating** an existing model to derive a new one, and **serializing** a model back to text. The library is designed for both Kotlin and Java consumers and treats Java interoperability as a first-class concern.

The scope of this specification is the behaviour of layers 1 and 2:

- **[INTRO-1]** A conforming implementation MUST implement the parsing algorithm of §8, the host-parsing pipeline of §7, and the percent-encoding rules of §5 as a single engine parameterized by `ParseProfile`, such that `Uri` and `Url` are produced by the same code paths configured differently, not by two independent parsers.
- **[INTRO-2]** A conforming implementation MUST expose, at layer 2, the public types and identifiers named verbatim in this specification (`Uri`, `Url`, `ParseProfile`, `Host` and its variants, `QueryParameters`, `ParseResult`, `UriParseError`, `ValidationError`, `effectivePort`) with the semantics this specification assigns them.
- **[INTRO-3]** This specification governs textual identifier syntax, structure, and serialization only. Network operations (name resolution, connection establishment, retrieval, redirection following) are out of scope, and a conforming implementation MUST NOT perform any network access — including DNS resolution — as part of parsing, building, manipulating, serializing, comparing, or hashing a `Uri` or `Url`.

The treatment of internationalized identifiers (IRIs, per RFC 3987) is **in scope**, but its mechanism differs by profile. In the `Url` profile, non-ASCII input is resolved **inline during parsing**: the host through IDNA/UTS-46 and every other component through percent-encoding of its UTF-8 octets, as specified in §5 and §7. In the `Uri` profile, strict parsing follows RFC 3986 to the letter and therefore **rejects** a non-ASCII host outright; the RFC 3987 §3.1 IRI-to-URI mapping (host IDNA plus UTF-8 percent-encoding of the other components) and the §3.2 display transform are instead offered as an explicit, opt-in conversion facility (`Iri.toUri` / `Iri.toUnicode`, §11.5), never as a relaxation of the parser. Keeping the mapping outside strict `Uri` parsing preserves the invariant that the `Uri` profile has no RFC 3986 deviations ([INTRO-14]; Appendix B).

### 1.2 The Two-Profile Architecture

`kuri` is structured around a single decision: one parsing engine, two parse profiles, two public value types.

- **`Uri`** models the RFC 3986 generic-URI syntax (IRI-aware per RFC 3987). It is scheme-agnostic, has no notion of "special" schemes or default ports, performs no backslash rewriting, and **preserves input by default**, with normalization available only on explicit opt-in.
- **`Url`** models the WHATWG URL Living Standard. It is aware of the special schemes (`http`, `https`, `ws`, `wss`, `ftp`) and `file`, applies **eager canonicalization** on every parse and build, runs the full WHATWG host pipeline (IPv4 shorthand/hex/octal, IDNA/UTS-46, forbidden host code points), and elides default ports.

Both types are produced by the **same** state machine, host module, and percent-encoding module, selected by `ParseProfile.URI` or `ParseProfile.URL`. The two are surfaced as distinct types — rather than one type carrying a runtime mode flag — so that the type system communicates to a consumer which contract a value holds.

- **[INTRO-4]** A `ParseProfile` value MUST be one of exactly two members: `URI` and `URL`. The profile selected for a parse fully determines which profile-gated behaviours (defined throughout §§4–11) apply, and a single parse MUST NOT mix behaviours from both profiles.
- **[INTRO-5]** The conversion `Url.toUri()` MUST be total (it MUST succeed for every well-formed `Url`), because every WHATWG URL has an RFC 3986 representation. The conversion `Uri.toUrl()` MUST be fallible and MUST report an error (rather than throwing for a recoverable condition) when the `Uri` is not a valid special-scheme URL under the `Url` profile.

The contrasting postures of the two profiles are summarized below; the cited sections are normative for the detail.

| Concern | `Uri` profile (`ParseProfile.URI`) | `Url` profile (`ParseProfile.URL`) | Governed by |
|---|---|---|---|
| Underlying standard | RFC 3986 / RFC 3987 | WHATWG URL Living Standard | §1.4 |
| Special schemes / default ports | None; scheme-agnostic | `http`/`https`/`ws`/`wss`/`ftp`/`file` recognized; default ports elided | §6 |
| Default posture | Preserve input; normalization opt-in | Eager canonicalization on every parse | §11 |
| Backslash (`\`) | Not in the grammar; percent-encoded where disallowed | Treated as `/` for special schemes | §4, §8 |
| Authority introduction | Exactly `//` introduces an authority | Runs of `/` and `\` after `scheme:` collapse for special schemes | §8 |
| Host pipeline | reg-name / IP-literal / IPv4address; no IDNA, no IPv4 shorthand | Full WHATWG pipeline: IPv4 shorthand/hex/octal, IDNA/UTS-46, forbidden code points, opaque host | §7 |
| Empty host | Permitted | Rejected for special schemes except `file` | §7 |
| Tab/newline & C0 handling | Embedded controls are invalid | Tab/LF/CR stripped anywhere; leading/trailing C0+space trimmed | §4, §8 |
| Internationalized host | Percent-encoded UTF-8 in reg-name; IDNA not applied | Mapped via bundled UTS-46 (ToASCII/ToUnicode) | §5, §7 |

**Primary use case.** Although `kuri` supports two profiles, the `Url` profile (`ParseProfile.URL`), which implements the **[WHATWG-URL]** Standard, is `kuri`'s **primary and most-common use case**: the overwhelming majority of identifiers `kuri` is expected to parse, build, manipulate, and serialize in practice are web URLs (`http`, `https`, `ws`, `wss`, `ftp`, `file`). The `Url` profile is therefore the profile to optimize and test first.

- **[INTRO-16]** The `Url` profile SHOULD receive implementation and conformance-testing priority: where engineering effort, conformance-corpus coverage (notably the Web Platform Tests URL and IDNA fixtures, §13), or optimization attention must be allocated between the two profiles, the `Url` profile SHOULD be prioritized, and an implementation SHOULD treat a `Url`-profile conformance gap as at least as severe as an equivalent `Uri`-profile gap. This priority concerns **usage and testing emphasis only** and MUST NOT be read as altering the **precedence of authorities** fixed in §1.3.1: **[RFC3986]** remains the supreme normative authority ([INTRO-12]–[INTRO-15]), and the **[WHATWG-URL]** behaviours the `Url` profile adopts are authoritative only to the extent each is explicitly registered as a sanctioned deviation in **Appendix B — Deviations from RFC 3986**. That the `Url` profile is the primary use case does not promote **[WHATWG-URL]** above **[RFC3986]** in any conflict, and does not sanction any departure from **[RFC3986]** that is not registered in Appendix B.

### 1.3 Conformance

The key words **MUST**, **MUST NOT**, **REQUIRED**, **SHALL**, **SHALL NOT**, **SHOULD**, **SHOULD NOT**, **RECOMMENDED**, **MAY**, and **OPTIONAL** in this document are to be interpreted as described in BCP 14 (RFC 2119 and RFC 8174) when, and only when, they appear in all capitals, as shown. Prose that does not state a requirement does not use these key words.

Every testable normative requirement in this specification is numbered with an identifier of the form **[ABBR-N]**, where `ABBR` is the abbreviation assigned to the section and `N` is a sequential number within that section. The conformance section (§13) references these identifiers, and Appendix A indexes them.

- **[INTRO-6]** An implementation conforms to this specification **if and only if** it satisfies every applicable **MUST** and **MUST NOT** requirement.
- **[INTRO-7]** An implementation that deviates from any **SHOULD** or **SHOULD NOT** requirement MUST document each such deviation in its published conformance statement, including the requirement identifier and the rationale.
- **[INTRO-8]** A claim of conformance MUST identify the conformance class claimed (per the definitions below) and MUST identify this specification by version.

This specification defines three conformance classes:

| Class | Definition |
|---|---|
| **URI-conformant** | Satisfies every applicable **MUST**/**MUST NOT** requirement governing the `Uri` profile (`ParseProfile.URI`) and all profile-agnostic requirements. |
| **URL-conformant** | Satisfies every applicable **MUST**/**MUST NOT** requirement governing the `Url` profile (`ParseProfile.URL`) and all profile-agnostic requirements. |
| **Fully-conformant** | Satisfies the requirements of both the URI-conformant and URL-conformant classes, and additionally every requirement governing cross-profile behaviour (the `Uri`↔`Url` conversions of [INTRO-5] and the shared-engine requirement of [INTRO-1]). |

- **[INTRO-9]** An implementation claiming the **fully-conformant** class MUST satisfy [INTRO-1] (single shared engine) in addition to the union of the URI-conformant and URL-conformant requirements; satisfying the two profile classes by means of two independent parsers does not qualify as fully-conformant.

A "profile-agnostic" requirement is one whose statement does not restrict itself to a single profile; such requirements apply to both classes. The conformance classes are restated operationally in §13.1.

#### 1.3.1 Precedence of authorities

The standards cited by this specification do not carry equal weight. The following rules fix their order of precedence and govern the resolution of any conflict among them.

- **[INTRO-12]** **[RFC3986]** is the supreme normative authority for this specification. Every requirement, algorithm, grammar production, and definition in this document MUST be read and implemented in conformance with **[RFC3986]**, except where this specification lawfully departs from it under [INTRO-14].
- **[INTRO-13]** Where this specification, the **[WHATWG-URL]** Standard, or any other cited source (including **[RFC3987]**, **[UTS46]**, and every reference of §1.4 and §1.5) conflicts with **[RFC3986]**, **[RFC3986]** governs, and the conflicting provision MUST be read and implemented in conformance with **[RFC3986]**.
- **[INTRO-14]** The sole exception to [INTRO-12] and [INTRO-13] is a behaviour of the `Url` profile (`ParseProfile.URL`) that is explicitly registered in **Appendix B — Deviations from RFC 3986**. Each such registered deviation is a deliberate adoption of **[WHATWG-URL]** behaviour for web-URL compatibility, applies only under `ParseProfile.URL`, and is authoritative only to the extent and in the form recorded in Appendix B. The `Uri` profile (`ParseProfile.URI`) introduces no deviations from **[RFC3986]** and MUST conform to **[RFC3986]** without exception.
- **[INTRO-15]** Any conflict between this specification and **[RFC3986]** that is not registered in **Appendix B** is a defect in this specification, not a sanctioned deviation, and MUST be resolved in favour of **[RFC3986]**: a conforming implementation MUST follow **[RFC3986]**, and the conflicting specification text MUST be corrected to conform to it.

The complete, exhaustive set of sanctioned `Url`-profile departures from RFC 3986 is catalogued in [Appendix B — Deviations from RFC 3986](#appendix-b--deviations-from-rfc-3986).

### 1.4 Normative References

The following documents are referenced normatively; their relevant requirements are incorporated by reference into this specification. When a reference defines behaviour that this specification also states, the text of this specification is controlling for `kuri`, **except that [RFC3986] is the supreme normative authority** (see §1.3 "Precedence of authorities", [INTRO-12]-[INTRO-15]): where this specification conflicts with [RFC3986], [RFC3986] governs and the specification MUST be read and corrected in conformance with it, save only for `Url`-profile behaviours explicitly registered in Appendix B — Deviations from RFC 3986. Subject to that exception, the reference is otherwise cited to establish provenance. Where a reference is a Living Standard or a versioned data file, the version pinned by the conformance section (§13) applies.

| Tag | Reference |
|---|---|
| **[RFC2119]** | Bradner, S., "Key words for use in RFCs to Indicate Requirement Levels", BCP 14, RFC 2119. |
| **[RFC8174]** | Leiba, B., "Ambiguity of Uppercase vs Lowercase in RFC 2119 Key Words", BCP 14, RFC 8174. |
| **[RFC3986]** | Berners-Lee, T., Fielding, R., and L. Masinter, "Uniform Resource Identifier (URI): Generic Syntax", STD 66, RFC 3986. |
| **[RFC3987]** | Duerst, M. and M. Suignard, "Internationalized Resource Identifiers (IRIs)", RFC 3987. |
| **[RFC6874]** | Carpenter, B., Cheshire, S., and R. Hinden, "Representing IPv6 Zone Identifiers in Address Literals and Uniform Resource Identifiers", RFC 6874. |
| **[RFC5952]** | Kawamura, S. and M. Kawashima, "A Recommendation for IPv6 Address Text Representation", RFC 5952. |
| **[RFC5891]** | Klensin, J., "Internationalized Domain Names in Applications (IDNA): Protocol", RFC 5891. |
| **[RFC3492]** | Costello, A., "Punycode: A Bootstring encoding of Unicode for Internationalized Domain Names in Applications (IDNA)", RFC 3492. |
| **[UTS46]** | Unicode Consortium, "Unicode Technical Standard #46: Unicode IDNA Compatibility Processing". |
| **[RFC5234]** | Crocker, D., Ed. and P. Overell, "Augmented BNF for Syntax Specifications: ABNF", STD 68, RFC 5234. |
| **[WHATWG-URL]** | WHATWG, "URL Living Standard". |
| **[WHATWG-FORM-URLENCODED]** | WHATWG, "HTML Standard" / "URL Living Standard", the `application/x-www-form-urlencoded` serializer and parser definition. |

- **[INTRO-10]** Internationalized host processing MUST be implemented per **[UTS46]** (ToASCII and ToUnicode) together with **[RFC5891]** and **[RFC3492]** (Punycode), using a bundled mapping table with NFC normalization as specified in §7; an implementation MUST NOT delegate this processing to `java.net.IDN` or any platform IDNA facility, because doing so would not satisfy the normative IDNA behaviour defined in §7.
- **[INTRO-11]** ABNF grammar appearing in this specification MUST be read per **[RFC5234]**.

Note: the ABNF of §4 and the reference-resolution algorithm of §9 derive from RFC 3986; the state machine of §8, the host pipeline of §7, and the percent-encode-set matrix of §5 derive from the WHATWG URL Standard; cf. WHATWG URL §4.4 and RFC 3986 §3.

### 1.5 Informative References

The following are referenced for background and were analyzed during the design of `kuri`; they are not normative, and no conformance requirement depends on them. They are listed to record provenance and to aid implementers comparing behaviour.

| Tag | Reference |
|---|---|
| **[ada]** | ada — a WHATWG-conformant URL parser written in C++ (the `url` and `url_aggregator` engines), source of the host, percent-encoding, and IDNA pipeline modelling. |
| **[okhttp-httpurl]** | OkHttp's `HttpUrl` — an HTTP-pragmatic URL type for `http`/`https`, source of the decoded-vs-encoded paired-accessor model and the canonical-string equality contract. |
| **[ktor-http]** | Ktor's `ktor-http` URL model — a fixed-scheme structured URL representation. |
| **[chrynan-uri]** | chrynan `uri` — a pure RFC 3986 component splitter for Kotlin Multiplatform. |
| **[furl]** | furl — a Python RFC-3986-oriented URL library emphasizing mutation ergonomics and preserve-by-default behaviour. |
| **[WPT]** | The Web Platform Tests URL corpus (`urltestdata.json`, `IdnaTestV2.json`, and related fixtures), used as a conformance corpus for the `Url` profile and the IDNA pipeline (see §13). |

Note: `kuri` adopts no single one of these as a model; it occupies the gap none of them fills — a strict generic-URI library and a WHATWG-URL library sharing one engine.

## 2. Terminology & Notation

This section defines the vocabulary and notational conventions used normatively throughout this specification. Definitions are grouped by concern and ordered alphabetically within each group. Terms defined here are used with their defined meaning wherever they appear in subsequent sections; where a later section refines a term, it does so explicitly.

A small number of statements in this section impose genuine requirements (chiefly on how conforming text and tooling interpret the terms and notation). Those statements are numbered **[TERM-N]**. Unnumbered prose is definitional or explanatory and imposes no independent requirement.

### 2.1 Code points and characters

This specification operates on Unicode code points. The terms below establish the low-level repertoire on which the grammar (§4), percent-encoding (§5), and host parsing (§7) are built.

| Term | Definition |
|---|---|
| **code point** | A Unicode code point: an integer in the inclusive range U+0000 to U+10FFFF. Surrogate code points are included in this range but are not scalar values. |
| **scalar value** | A code point that is not a surrogate, i.e. in U+0000–U+D7FF or U+E000–U+10FFFF. UTF-8 octets (§5) encode scalar values. |
| **surrogate** | A code point in the inclusive range U+D800 to U+DFFF. A surrogate is not a valid Unicode scalar value and has no UTF-8 encoding. |
| **ASCII code point** | A code point in the inclusive range U+0000 to U+007F. |
| **ASCII alpha** | An ASCII code point in the ranges U+0041 (`A`) to U+005A (`Z`) or U+0061 (`a`) to U+007A (`z`). |
| **ASCII digit** | An ASCII code point in the inclusive range U+0030 (`0`) to U+0039 (`9`). |
| **ASCII alphanumeric** | An ASCII alpha or an ASCII digit. |
| **ASCII hex digit** | An ASCII digit, or an ASCII code point in the ranges U+0041 (`A`) to U+0046 (`F`) or U+0061 (`a`) to U+0066 (`f`). |
| **C0 control** | A code point in the inclusive range U+0000 to U+001F. |
| **C0 control or space** | A C0 control or U+0020 SPACE. |
| **ASCII tab or newline** | U+0009 TAB, U+000A LF, or U+000D CR. |
| **octet** | An 8-bit byte: an integer in the inclusive range 0 to 255. "Octet" always denotes a byte; "code point" always denotes a Unicode code point. The two are never used interchangeably. |

**[TERM-1]** Where this specification names a code-point class (for example "ASCII alpha" or "C0 control"), an implementation MUST classify a code point by the exact numeric ranges given in the table above, independent of host locale, Unicode case-folding, or platform character-classification routines.

Note: the ranges mirror the WHATWG "Infrastructure" definitions and RFC 5234 core rules; cf. WHATWG URL §1 / RFC 3986 §1.2.

### 2.2 Schemes, special schemes, and ports

| Term | Definition |
|---|---|
| **scheme** | The leading component that names the URI/URL's namespace, matching the grammar `ALPHA *( ALPHA / DIGIT / "+" / "-" / "." )` (§4). A scheme is compared case-insensitively and is serialized in lowercase. |
| **special scheme** | One of the schemes `ftp`, `file`, `http`, `https`, `ws`, `wss`. Special schemes activate WHATWG-specific behaviour (host pipeline, backslash handling, default-port elision) in the `Url` profile. The complete set and the per-scheme default ports are fixed normatively in §6. |
| **default port** | The port associated with a special scheme that is elided from serialization when the URL's port equals it: `ftp` 21, `http` 80, `https` 443, `ws` 80, `wss` 443. `file` has no default port. Defined normatively in §6. |
| **`effectivePort`** | The port a consumer should use to reach the resource: the explicitly specified port if present, otherwise the scheme's default port, otherwise absent. Distinct from the stored port (which is `null` when unspecified). Defined in §3 and §6. |

Note: derived from ada `scheme.h` / `parser.cpp`; cf. WHATWG URL §3.1.

### 2.3 URI/URL strings and components

| Term | Definition |
|---|---|
| **URI string / URL string** | The textual serialization of a `Uri` or `Url` value: a sequence of code points that, when parsed under the relevant profile, yields the corresponding value. "URI string" denotes the `Uri`-profile serialization; "URL string" the `Url`-profile serialization. Where a statement applies to both, "input string" is used. |
| **component** | One of the named substructures of a parsed value: scheme, authority (and its sub-parts userinfo, host, port), path, query, fragment. Each component has an independent presence state (absent vs. present-but-empty), modelled with nullable types in §3. |
| **authority** | The component following `//` that carries userinfo, host, and port: `[ userinfo "@" ] host [ ":" port ]`. The authority as a whole may be absent (no `//`) or present-but-empty. |
| **userinfo** | The optional credential portion of the authority preceding `@`, decomposed into `user` and `password` (split on the first `:`; the userinfo/host boundary is the last `@`). An absent password and an empty password are distinct (§3). |
| **host** | The addressable-name portion of the authority, modelled by the sealed `Host` type (`RegName`, `Ipv4`, `Ipv6`, `IpFuture`, `Empty`, `Opaque`; §7). Stored host values never include IPv6 brackets; bracketing is applied on serialization. |
| **port** | The optional numeric port of the authority, a non-negative integer. The stored port is `null` when unspecified; it is never a `-1` or `0` sentinel for "absent". Range and per-profile acceptance are fixed in §6 and §8. |
| **path** | The component that identifies the resource within the scheme, modelled either as a sequence of zero or more path segments or as an opaque path (below). Defined in §9. |
| **opaque path** | A path that is a single opaque string rather than a sequence of slash-delimited segments; it arises for "cannot-be-a-base" URLs (e.g. `mailto:user@host`, `data:...`). An opaque path is not subject to segment-wise dot-segment removal. Defined in §9. |
| **path segment** | One element of a non-opaque path, delimited by `/`. A segment may be empty (consecutive slashes, or a trailing slash, produce empty segments, which are significant). |
| **query** | The component introduced by `?` and terminated by `#` (or end of input). The raw query is an opaque string; structured access is provided separately by `QueryParameters` (§10). An absent query (`null`) is distinct from a present-but-empty query (`""`). |
| **fragment** | The component introduced by `#` and extending to end of input. An absent fragment (`null`) is distinct from a present-but-empty fragment (`""`). |

Note: component decomposition follows RFC 3986 §3; the opaque-path / segment distinction follows WHATWG URL §4.1.

### 2.4 Percent-encoding terms

| Term | Definition |
|---|---|
| **percent-encoded byte** | A three-code-point sequence `"%"` followed by two ASCII hex digits, denoting a single octet. On serialization the two hex digits are uppercase; on decoding they are matched case-insensitively. Defined in §5. |
| **percent-encode set** | A set of ASCII code points that, for a given component, MUST be percent-encoded when serialized, in addition to all code points outside the printable-ASCII range that the component does not permit. Each component's set is fixed as a table in §5. |
| **percent-decoding** | The transformation that replaces each valid percent-encoded byte in a string with the octet it denotes, leaving malformed `%` sequences literal, and interprets the resulting octets as UTF-8 (§5). |

### 2.5 References, base, and resolution

| Term | Definition |
|---|---|
| **absolute URL** | A URI/URL string that includes a scheme and is not a relative reference. |
| **relative reference** | An input string parsed without its own scheme, whose missing components are taken from a base URL during reference resolution (§9). |
| **base URL** | The already-parsed, absolute URI/URL against which a relative reference is resolved. Supplied explicitly to the parse/resolve entry points. |
| **reference resolution** | The algorithm (§9, per RFC 3986 §5) that combines a base URL and a relative reference into a target URL. |

### 2.6 Output, equivalence, and profile

| Term | Definition |
|---|---|
| **serialize / serialization** | The act, and the result, of converting a `Uri`/`Url` value (or one of its components) back into a string. "The serialization" of a value is its canonical string form as defined in §11. |
| **serializer** | The algorithm in §11 that produces a serialization from a parsed value. |
| **canonicalization** | The application of normalization rules (§11) to produce a single canonical form for a value or component. In the `Url` profile canonicalization is eager and always applied; in the `Uri` profile it is opt-in. |
| **profile** | The configuration that parameterizes the shared engine, selected by the value type being produced. `ParseProfile.URL` selects WHATWG semantics (special schemes, eager canonicalization, full host pipeline, backslash rewriting); `ParseProfile.URI` selects RFC 3986/3987 semantics (scheme-agnostic, preserve-by-default, opt-in normalization, no special-scheme magic). Where behaviour differs, this specification states it as "In the `Url` profile … In the `Uri` profile …". |

**[TERM-2]** Where this specification distinguishes profiles, an implementation MUST apply the behaviour prescribed for the profile under which the value is being parsed or serialized, and MUST NOT apply `Url`-profile behaviour (special-scheme detection, default-port elision, backslash-to-slash rewriting, IDNA host processing, IPv4 shorthand) when operating under `ParseProfile.URI`.

### 2.7 Validation errors and failure

This specification adopts the WHATWG distinction between a fatal failure and a non-fatal validation error, and extends it across both profiles.

| Term | Definition |
|---|---|
| **failure (fatal)** | A condition under which parsing cannot produce a value. A fatal failure terminates parsing and is reported as a `ParseResult.Err` carrying a `UriParseError` (§12). No value is produced. |
| **validation error (non-fatal)** | A condition that indicates the input deviates from a recommended form but does not, by itself, prevent a value from being produced. A non-fatal validation error is recorded as a `ValidationError` (§12) and does not halt parsing. |

**[TERM-3]** An implementation MUST treat a fatal failure and a non-fatal validation error as distinct outcomes. A fatal failure MUST yield `ParseResult.Err` and MUST NOT yield a value; a non-fatal validation error MUST NOT, on its own, prevent the production of a `ParseResult.Ok` value.

**[TERM-4]** In the `Url` profile, when parsing succeeds despite one or more non-fatal validation errors, the implementation MUST make the recorded `ValidationError` list available alongside the produced value (so that strict callers may inspect it and lenient callers may ignore it), and the presence of such errors alone MUST NOT downgrade the result to `ParseResult.Err`.

**[TERM-5]** In strict mode (§12), a condition classified in this specification as a non-fatal validation error MAY be escalated to a fatal failure; outside strict mode such a condition MUST remain non-fatal. The set of escalatable conditions is fixed by §12, not by this section.

Note: the fatal/non-fatal split derives from WHATWG URL's "validation error" concept (§1.3); the `Uri` profile reuses the machinery while defaulting to RFC-strict rejection for conditions WHATWG would merely flag.

### 2.8 Internationalization terms

| Term | Definition |
|---|---|
| **IDNA** | Internationalizing Domain Names in Applications: the process that converts a Unicode domain name to/from an ASCII-compatible encoding for host processing. In this specification IDNA denotes the UTS-46-based ToASCII/ToUnicode operations defined normatively in §7, implemented via a bundled UTS-46 mapping table (not `java.net.IDN`). |
| **UTS-46 mapping** | The Unicode Technical Standard #46 mapping that, code point by code point, maps, ignores, disallows, or leaves unchanged each input code point before normalization and Punycode conversion. The mapping table is bundled and its behaviour is defined normatively in §7. |
| **Punycode** | The bootstring encoding (RFC 3492) used to represent a Unicode label as an ASCII label prefixed with `xn--`. Applied to and from labels during IDNA ToASCII/ToUnicode (§7). |
| **NFC** | Unicode Normalization Form C (canonical decomposition followed by canonical composition). Applied to host labels during IDNA processing as required by UTS-46 / RFC 5891 (§7). |

Note: IDNA behaviour is fully normative in §7; the obligations here only fix the vocabulary. cf. UTS-46, RFC 5890/5891, RFC 3492.

### 2.9 Notation

#### 2.9.1 Grammar (ABNF)

Grammar productions in this specification are written in ABNF as defined by **RFC 5234** (with the incremental-alternative and case-sensitivity conventions of that RFC). The following RFC 5234 core terminals are used throughout and are reproduced here for self-containment:

| Rule | Definition | Meaning |
|---|---|---|
| `ALPHA` | `%x41-5A / %x61-7A` | ASCII letters `A`–`Z`, `a`–`z`. |
| `DIGIT` | `%x30-39` | ASCII digits `0`–`9`. |
| `HEXDIG` | `DIGIT / "A" / "B" / "C" / "D" / "E" / "F"` | Hexadecimal digit. Per [TERM-6], matched case-insensitively. |
| `SP` | `%x20` | Space (U+0020). |
| `HTAB` | `%x09` | Horizontal tab (U+0009). |
| `CR` | `%x0D` | Carriage return (U+000D). |
| `LF` | `%x0A` | Line feed (U+000A). |

**[TERM-6]** In this specification's grammar, `HEXDIG` MUST be matched case-insensitively (both `A`–`F` and `a`–`f` are accepted) wherever it appears, including within percent-encoded bytes; this overrides the case-sensitive literal default of RFC 5234 for `HEXDIG` only.

Notation within productions:
- Terminal code points are written either as quoted ASCII literals (`"%"`, `"/"`) or as `%xHH` hexadecimal values (`%x41`), with ranges written `%xHH-HH`.
- A percent-encoded byte is written in grammar as `pct-encoded = "%" HEXDIG HEXDIG`, and informally as `%xx`, where each `x` denotes one `HEXDIG`. The informal `%xx` always means exactly one percent sign followed by two hex digits and never a literal `xx`.
- `*rule` means zero or more, `1*rule` one or more, `n*mrule` between n and m repetitions, `[ rule ]` optional, `/` separates alternatives, and parentheses group, all per RFC 5234.

#### 2.9.2 Algorithm pseudocode

Algorithm sections (notably §7, §8, §9) are written as numbered, sequentially executed steps. The conventions are:

- **Steps.** Each algorithm is an ordered list of steps; sub-steps are nested and numbered hierarchically. Unless a step transfers control ("go to", "return", "continue", "break"), execution proceeds to the next step in order.
- **Variables.** "Let x be V" introduces a new local variable x bound to value V. "Set x to V" reassigns an existing variable. "Append", "remove", "prepend" mutate ordered collections. Variables are local to the algorithm unless stated otherwise.
- **Return.** "Return V" terminates the algorithm yielding V. "Return failure" terminates yielding a fatal failure (§2.7). "Validation error" (as a step) records a non-fatal `ValidationError` and continues unless the same step also returns failure.
- **Input pointer / EOF model.** Several algorithms scan an input string with a single position pointer, by convention named `pointer`, starting at 0 and never decreasing except where a step explicitly says "decrease pointer by 1". The code point at `pointer` is "c". The sentinel **EOF** denotes the position one past the last code point; an algorithm that loops "while pointer ≤ length of input" observes c = EOF on the final iteration. "Decrease pointer by 1" is the canonical way to re-process the current state without consuming a code point (it emulates the WHATWG "decrease pointer and continue" idiom).

**[TERM-7]** An implementation of an algorithm specified in this document MUST produce, for every input, the same result (the produced value, or fatal failure, together with the set of recorded non-fatal validation errors) as the numbered steps prescribe. An implementation MAY use any internal control structure (state machine, recursive descent, etc.) provided the observable result is identical; the numbered steps are normative as to result, not as to implementation structure.

Note: the pointer/EOF and "let/set/return" conventions mirror the WHATWG "basic URL parser"; cf. WHATWG URL §4.4 and the "Infrastructure" algorithm conventions.

## 3. Data Model

This section defines the abstract shape of a parsed URI/URL value: the set of components, the type of each component, and the normative meaning of every distinguishable state each component can hold (in particular, the difference between an absent component and a present-but-empty component). It defines the public value types `Uri` and `Url`, the sealed `Host` type, the port model, the path model, and the query surface. It defines how values are constructed and how they relate.

This section specifies **data shape only**. The algorithms that *produce* these values (parsing, host processing, percent-encoding, reference resolution, normalization, serialization) are normative in §5–§11; where this section constrains a value, it constrains every algorithm that yields one. An implementer reading only this section learns what a valid in-memory value looks like, not how to compute it.

### 3.1 The abstract URI record

[MODEL-1] A parsed value MUST be modelled as an immutable record of exactly the following seven logical components, in this order: `scheme`, `userinfo` (itself decomposed into `user` and `password`), `host`, `port`, `path`, `query`, `fragment`. No additional load-bearing component may be added to the model; derived views (authority string, origin, encoded forms, query-parameter view) are computed projections of these components and MUST NOT be stored as independent authoritative state.

[MODEL-2] The model is the same record shape for both profiles. The `Uri` type and the `Url` type MUST expose the same seven components with the same types. They differ only in (a) which states are *reachable* (e.g. a `Url` always has a scheme; a `Url` host is never an `IpFuture`), and (b) the canonicalization applied when a value is produced (PRESERVE for `Uri`, EAGER for `Url`), not in the structural model.

[MODEL-3] Every component value held in the record MUST be in the *stored canonical form* defined for its component below (e.g. host with brackets stripped, port as `Int?`, path as an ordered segment list plus its canonical encoded form). Producers normalize on the way in; consumers never re-derive authoritative state from a serialized string.

The component types are summarized here and specified in detail in §3.3–§3.10:

| Component | Stored type | Absent (null) means | Cross-ref |
|---|---|---|---|
| `scheme` | `String?` | relative reference (no scheme); `Uri` only | §3.3, §6 |
| `user` | `String?` | no userinfo present | §3.4 |
| `password` | `String?` | no `:` password field present | §3.4 |
| `host` | `Host?` | no authority component | §3.5, §7 |
| `port` | `Int?` | port not explicitly given | §3.6 |
| `path` | `Path` (segment list + encoded form) | never null; empty path is a value | §3.7, §9 |
| `query` | `String?` | no `?` delimiter present | §3.8, §10 |
| `fragment` | `String?` | no `#` delimiter present | §3.9 |

### 3.2 Null versus empty: the central invariant

Throughout the model, a **nullable component distinguishes absence from emptiness**, and this distinction is observable, round-trippable, and normative. A `null` means the delimiter that introduces the component was not present in the input; an empty string (`""`) means the delimiter was present and the component it introduces is empty.

[MODEL-4] An implementation MUST NOT use an empty string as a sentinel for "absent", and MUST NOT use a non-null numeric or string sentinel (e.g. `-1`, `0`, `"null"`, a `"?"` marker) to encode absence of any component. Absence is represented exclusively by Kotlin `null` (for nullable components) or by the designated `Host.Empty` / empty `Path` value (for the two non-`String?` components that have an "empty" state).

[MODEL-5] The following states MUST be mutually distinguishable in the model and MUST survive a parse → serialize round trip in the `Uri` (PRESERVE) profile:

| Distinction | State A | State B | Serialized A | Serialized B |
|---|---|---|---|---|
| query absent vs present-empty | `query = null` | `query = ""` | `…` | `…?` |
| fragment absent vs present-empty | `fragment = null` | `fragment = ""` | `…` | `…#` |
| authority absent vs empty host | `host = null` | `host = Host.Empty` | `s:path` | `s://` (or `s:///path`) |
| no userinfo vs empty userinfo | `user = null, password = null` | `user = "", password = null` | `//host` | `//@host` |
| no password vs empty password | `password = null` | `password = ""` | `//user@host` | `//user:@host` |
| no port vs … (see §3.6) | `port = null` | `port = N` | `//host` | `//host:N` |

Note: derived from the WHATWG distinction between a missing component and an empty component, and from RFC 3986 §3 where the presence of `//`, `?`, and `#` is itself syntactically significant.

[MODEL-6] In the `Url` (EAGER) profile, canonicalization MAY collapse certain present-empty states during production (for example, a default-port `port` is elided, and an empty `path` for a special scheme is serialized as `/`). Such collapsing is governed by §11 (serialization) and §6 (schemes), not by this section. The *model* still represents the post-canonicalization state faithfully (e.g. after canonicalization `port` is `null`, not `0`).

### 3.3 Scheme

[MODEL-7] `scheme` MUST be modelled as `String?`. A non-null value holds the scheme in its stored canonical form: ASCII letters, digits, `+`, `-`, `.`, lowercased (lowercasing of the scheme is required in both profiles; it is the one normalization the `Uri` profile applies unconditionally to the scheme). The leading `:` delimiter is not part of the stored value.

[MODEL-8] In the `Uri` profile, `scheme` MAY be `null`; a `null` scheme denotes a **relative reference** (RFC 3986 §4.2). In the `Url` profile, `scheme` MUST NOT be `null`: a successfully produced `Url` always has a non-null, non-empty scheme. A relative input is resolved against a base URL during parsing (§8/§9) and the resulting `Url` carries the resolved scheme.

[MODEL-9] The `Uri` public type MUST expose `scheme` as `String?`. The `Url` public type MUST expose `scheme` as non-null `String` (the always-present invariant is reflected in the public type, giving Java and Kotlin callers a non-null guarantee).

### 3.4 Userinfo: user and password modelled independently

[MODEL-10] Userinfo MUST be modelled as two independent components, `user: String?` and `password: String?`, and MUST NOT be modelled as a single opaque `userInfo` string as authoritative state. (`userInfo` MAY be offered as a derived read-only convenience getter — see [MODEL-12].)

[MODEL-11] The four reachable userinfo states MUST be distinguishable:

| `user` | `password` | Meaning | Authority serialization |
|---|---|---|---|
| `null` | `null` | no userinfo subcomponent | `//host` |
| `""` | `null` | empty user, no password field | `//@host` |
| `"u"` | `null` | user only, no `:` | `//u@host` |
| `"u"` | `""` | user and empty password (the `:` is present) | `//u:@host` |
| `"u"` | `"p"` | user and password | `//u:p@host` |

[MODEL-13] A non-null `password` with a `null` `user` is not a representable state from parsing (the `:` separator implies a user field precedes it, possibly empty). Builders (§3.11) MUST reject `password != null && user == null` at `build()` time, or normalize `user` to `""`; the chosen behaviour MUST be deterministic and documented. The stored `user` and `password` values are held in their decoded form for the decoded getters and re-encoded per the userinfo percent-encode set (§5) on serialization.

[MODEL-12] A combined `userInfo: String?` projection MAY be exposed. When exposed it MUST be derived from `user`/`password` (null when `user == null`; otherwise `user`, plus `":" + password` when `password != null`) and MUST NOT be separately mutable authoritative state.

[MODEL-47] In the `Url` profile, a value **cannot have a username, password, or port** when its `host` is `null` or `Host.Empty`, or its `scheme` is `file` (the WHATWG *cannot-have-a-username/password/port* condition). For such a value, the `Url` `Builder` MUST NOT produce a non-null `user`, non-null `password`, or non-null `port`: an attempt to set any of these via a setter on a cannot-have value MUST be a no-op (or be rejected deterministically per §12), consistent with the WHATWG URL setters. This makes `file://user@host/` and `file://host:80/` non-representable as a `Url`, matching the WHATWG file/file-host parser states, which parse no credentials or port. This constraint applies to the `Url` profile only; the `Uri` profile follows RFC 3986 and imposes no such restriction.

[MODEL-48] A value **includes credentials** when its `user` or its `password` is present and non-empty (the WHATWG *includes credentials* condition: username or password not the empty string). The userinfo serialization of §11 MUST emit the `userinfo@` prefix for a `Url`-profile value only when it includes credentials; an empty user with an empty or absent password does not, in the `Url` profile, produce a `//@host` form (cf. [MODEL-6], which permits the `Url` profile to collapse the present-empty userinfo states that the `Uri` PRESERVE profile distinguishes in [MODEL-11]).

### 3.5 Host

Host is modelled as a sealed type so that the host *kind* is part of the type, exhaustively matchable, and drives serialization (notably bracketing). Detailed host parsing, validation, IDNA/UTS-46 processing, IPv4 interpretation, and canonical serialization are normative in §7; this section fixes only the stored shape.

[MODEL-14] The `host` component MUST be modelled as `Host?`, where `Host` is a sealed type with exactly the following variants and no others:

```kotlin
public sealed interface Host {
    public data class RegName(val ascii: String) : Host
    public data class Ipv4(val value: Int) : Host
    public data class Ipv6(val pieces: List<Int>, val zoneId: String?) : Host
    public data class IpFuture(val raw: String) : Host
    public data object Empty : Host
    public data class Opaque(val encoded: String) : Host
}
```

[MODEL-15] `host == null` denotes **no authority component** (there was no `//`). It MUST be distinct from `Host.Empty`, which denotes an authority component whose host is the empty string (e.g. `file:///path`, where authority is present and host is empty). An implementation MUST NOT conflate these two states.

[MODEL-16] `RegName.ascii` MUST store the host as an already-canonical ASCII registered name: lowercased where applicable, IDNA/UTS-46 ToASCII-processed for the `Url` profile (§7), percent-encoding applied per the host rules of the active profile. It MUST NOT store raw Unicode awaiting later processing; canonicalization happens at production time. (A Unicode/display form is a derived view computed via ToUnicode, not stored authoritative state.)

[MODEL-17] `Ipv4.value` MUST store the address as a single 32-bit quantity (held in a Kotlin `Int`, interpreted as unsigned). It MUST NOT store the original textual form (dotted, hex, octal, shorthand). The canonical dotted-decimal serialization is computed from `value` (§7).

[MODEL-18] `Ipv6.pieces` MUST store the eight 16-bit groups of the address as fixed-length ordered data (e.g. a `List<Int>` of length 8, or an equivalent fixed structure), each element in `0..0xFFFF`. The stored IPv6 value MUST NOT include the surrounding `[` and `]` brackets; brackets are a serialization concern applied on output (§7/§11). Any embedded-IPv4 tail (`::ffff:1.2.3.4`) MUST be folded into the eight groups at production time, not stored as text.

[MODEL-19] `Ipv6.zoneId` MUST be modelled as `String?`, default `null` (no zone id). Zone identifiers (RFC 6874) are an opt-in capability; in the default configuration of both profiles a zone id is rejected during parsing (§7/§12). When stored, the zone id holds the text between `%25` and the closing bracket, without the `%25` prefix.

[MODEL-20] `IpFuture` MUST store the `vN.…` literal content (without brackets) and is reachable only in the `Uri` profile. A `Url` value MUST NOT hold an `IpFuture` host.

[MODEL-21] `Opaque.encoded` holds a non-special host that is neither an IP literal nor a domain to be IDNA-processed (WHATWG opaque host for non-special schemes; an RFC reg-name preserved verbatim under PRESERVE). It MUST store the host with only the forbidden-host code-point and C0 percent-encoding applied (§7), and MUST NOT apply domain lowercasing or IDNA. The `Uri` profile uses `Opaque`/`RegName` for registered names per its PRESERVE policy; the `Url` profile uses `Opaque` only for non-special schemes.

[MODEL-22] Public APIs MUST surface the host kind via exhaustive `when` over the sealed `Host` type (no `else` branch, no separate stringly-typed `hostType` enum is required as authoritative state). A convenience kind accessor MAY be derived from the variant.

[MODEL-49] In the `Url` profile, the reachable `Host` variant is constrained by the scheme class, per the WHATWG scheme/host combination table: a **special scheme other than `file`** MUST have a `RegName` (domain), `Ipv4`, or `Ipv6` host (never `Empty`, never `Opaque`, never `null`); a **`file`** value MUST have a `RegName`, `Ipv4`, `Ipv6`, or `Empty` host (never `Opaque`, never `null`); a **non-special scheme** MUST have an `Opaque`, `Ipv6`, `Empty`, or `null` host and MUST NOT have a `RegName` (domain) or `Ipv4` host. In particular, a dotted-decimal authority such as `1.2.3.4` under a non-special `Url` scheme is stored as `Host.Opaque("1.2.3.4")`, not `Host.Ipv4` ([SCH-22]). These constraints bind the `Url` profile only; the `Uri` profile (RFC 3986) imposes no scheme-keyed host-kind restriction.

### 3.6 Port

[MODEL-23] `port` MUST be modelled as `Int?`. A `null` value denotes that no port was explicitly specified. A non-null value MUST be the literal port given in the input (after the `Url`-profile default-port elision in [MODEL-25]). In the `Url` profile a non-null value MUST be in the range `0..65535` (a value outside that range is a parse failure, per the WHATWG URL Standard). In the `Uri` profile, which follows RFC 3986 §3.2.3 (`port = *DIGIT`), a port consisting solely of decimal digits is syntactically valid and is parsed into the `port: Int?` model value; the `0..65535` cap MUST NOT be imposed as an RFC-`Uri` acceptance rule. Because the port is modelled as `Int`, a digit run whose value exceeds `Int.MAX_VALUE` is rejected as `InvalidPort`, and only the decimal value is stored (leading zeros are not preserved). The model MUST NOT use `-1`, `0`, or any other in-range value as a sentinel for "unspecified"; absence is `null` exclusively.

Note: `-1` (the `java.net.URI`/`java.net.URL` convention) and `0` (a `DEFAULT_PORT` convention seen in some libraries) are both rejected as sentinels. A literal port `0` in the input is a real, distinct value from `null`.

[MODEL-24] A companion derived accessor `effectivePort: Int` MUST be provided. `effectivePort` returns `port` when `port != null`; otherwise it returns the default port registered for `scheme` (§6). When `port == null` and the scheme has no known default port, `effectivePort` is unspecified-by-default and its behaviour MUST be defined by §6 (it either returns a sentinel-free signal — e.g. throws/`-1`-free contract — or is only available where a default exists). `effectivePort` is a computed projection and MUST NOT be stored as authoritative state.

[MODEL-25] In the `Url` profile, when the input port equals the default port for the scheme, canonicalization elides it: the produced `Url` MUST have `port == null` (not the numeric default). `effectivePort` then recovers the default. In the `Uri` profile, no default-port elision is applied: a stated port (including one equal to a well-known default) is preserved as a non-null value, and an absent port is `null`.

### 3.7 Path

Path segment grammar, dot-segment removal, and reference-resolution interactions are normative in §9; this section fixes only the stored representation.

[MODEL-26] `path` MUST always be present (never `null`). The empty path is represented as an empty value, not as `null`. The path component MUST store both (a) an **ordered list of decoded path segments** and (b) the **canonical encoded path string**, kept mutually consistent. The encoded string is authoritative for serialization; the segment list is the ergonomic decoded view. An implementation MAY compute one lazily from the other, provided they never disagree.

[MODEL-27] The path model MUST distinguish:

| State | Segment list | Encoded form |
|---|---|---|
| empty path | `[]` | `""` |
| root-only path | `[""]` (one empty segment after the root) | `"/"` |
| absolute path | leading segment boundary at `/` | `"/a/b"` |
| rootless/relative path (`Uri` only) | no leading `/` | `"a/b"` |

The leading-slash (absolute vs rootless) distinction MUST be preserved in the `Uri` profile. A trailing empty segment (trailing `/`) MUST be preserved as a distinct state from its absence (`"/a"` vs `"/a/"`).

[MODEL-28] An **opaque path** (a scheme-specific path with no authority, e.g. `mailto:user@example.com`, `urn:isbn:…`) MUST be representable. It is stored as a single opaque encoded string with no segment structure; the segment-list view of an opaque path MUST reflect that it is not slash-decomposed (§9 defines the exact representation). In the `Url` profile, opaque paths arise only for non-special schemes.

[MODEL-29] In the `Url` profile, a special-scheme value with an empty input path is canonicalized to a root path (`"/"`); the stored model reflects the post-canonical path. In the `Uri` profile, an empty path remains empty (no synthetic `/` is inserted).

### 3.8 Query (raw + parameter view)

[MODEL-30] `query` MUST be modelled as a raw `String?` holding the encoded query content **without** the leading `?` delimiter. `null` = no `?` was present; `""` = a `?` was present with empty content (these MUST be distinct per [MODEL-5]). The raw query is the authoritative query state; it preserves arbitrary content including additional `?` characters and any structure the parameter view cannot represent.

[MODEL-31] A structured `QueryParameters` view MUST be obtainable from a value (e.g. via a `queryParameters()` accessor). `QueryParameters` is an **immutable, ordered, duplicate-preserving, case-sensitive** snapshot of name/value pairs derived from the raw `query`; it is NOT a live view and mutating a derived `QueryParameters` does not mutate the source value (a new value is built via §3.11). The full `QueryParameters` surface, its value-sentinel semantics (`?k` → value `null`; `?k=` → value `""`), its parse bounds, and form-encoding are normative in §10. This section requires only that the raw `query` remain the authoritative state and that the parameter view be a derived snapshot.

[MODEL-32] The `Uri` public type primarily exposes the raw `query`; the `QueryParameters` view is available on both types. An implementation MUST NOT make `QueryParameters` the sole representation (it cannot represent non-pair query content) and MUST NOT silently drop duplicate names when constructing the view.

### 3.9 Fragment

[MODEL-33] `fragment` MUST be modelled as `String?` holding the encoded fragment content **without** the leading `#` delimiter. `null` = no `#` was present; `""` = a `#` was present with empty content (these MUST be distinct per [MODEL-5]). The fragment is stored percent-encoded per the fragment encode set (§5); its decoded form is a derived view.

### 3.10 Authority and other derived projections

[MODEL-34] `authority`, `userInfo`, `origin`, the encoded component getters (`encodedPath`, etc.), and the decoded component getters are **derived projections** of the seven stored components and MUST NOT be stored as independent authoritative state. The `authority` projection is `null` exactly when `host == null` (no authority), and otherwise is the serialization of userinfo + host + port. `origin` is defined for the `Url` profile (§11.6); it MUST NOT be relied upon to round-trip and is not part of the stored record.

### 3.11 Immutability and construction

[MODEL-35] `Uri` and `Url` MUST be immutable value types. All stored components MUST be `val`; collection-valued components (path segments, query pairs) MUST be exposed as read-only collections (`List`/`Set` wrapped unmodifiable). Once constructed, a value never changes.

[MODEL-36] Each type MUST be constructed via a **private primary constructor** plus a nested public `Builder` class (a plain nested class, instantiable from Java as `new Url.Builder()` / `new Uri.Builder()` — not a Kotlin DSL lambda). Direct public construction other than through the `Builder` or the parse factories MUST NOT be offered.

[MODEL-37] Each type MUST provide a `newBuilder(): Builder` instance method that returns a `Builder` **pre-filled** with the current value's components, so that `value.newBuilder().…build()` produces a modified copy. Every mutation operation MUST be expressed as producing a **new** value; no in-place mutator on `Uri`/`Url` may exist.

[MODEL-38] `Builder.build()` MUST validate the assembled components and MUST produce either a valid value or a typed failure. For the `Url` profile, `build()` MUST reject a value that lacks a scheme, or that lacks a host where the (special) scheme requires one (§6), failing deterministically (`ParseResult.Err` / documented exception per §12). For the `Uri` profile, `build()` MUST enforce the structural invariants of [MODEL-13] (userinfo) and the null-vs-empty consistency of [MODEL-5]. A `Builder` MUST NOT expose a partially-mutated value: intermediate state is observable only within the `Builder`, and only `build()` yields a `Uri`/`Url`.

[MODEL-39] Builders SHOULD provide paired decoded/encoded setters for components that have both forms (e.g. `host`/`encodedHost`-equivalent, `addPathSegment`/`addEncodedPathSegment`, query setters), so callers may supply already-encoded or to-be-encoded input explicitly. The decoded setter percent-encodes per the relevant set (§5); the encoded setter validates the supplied encoding.

### 3.12 Equality and hashing (model-level contract)

[MODEL-40] `Uri` and `Url` MUST implement value equality and `hashCode` consistently with one another and stable across the lifetime of a value (suitable as `Map`/`Set` keys), and MUST NOT perform any I/O (no DNS resolution) during equality or hashing. The exact comparison basis is normative in §11: for `Url`, equality compares the canonical WHATWG serialization; for `Uri` (PRESERVE), structural equality compares the canonical-but-unnormalized serialization, with a separate `normalized()`/normalization-aware comparison applying RFC 3986 §6. This section requires only that equality be a pure function of the stored components.

### 3.13 The `Uri` ↔ `Url` relationship

[MODEL-41] `Url.toUri(): Uri` MUST be provided and is **near-lossless**: every `Url` maps to a `Uri` because the WHATWG model is structurally expressible in the RFC 3986/3987 generic model. The conversion carries the already-canonical components across (scheme, userinfo, host, port, path, query, fragment) without re-parsing. It is "near"-lossless only in that WHATWG-eager canonicalization already applied to the `Url` (e.g. default-port elision, host lowercasing) is reflected in the resulting `Uri` and is not reverted; `toUri()` itself MUST NOT lose or alter any component.

[MODEL-42] `Uri.toUrl(): ParseResult<Url>` MUST be provided and **may fail**, returning `ParseResult.Err` (§12) when the `Uri` cannot be represented as a WHATWG `Url`. Failure cases include, at minimum: a `null` scheme (relative reference; a `Url` requires a scheme — [MODEL-8]), an `IpFuture` host ([MODEL-20]), a host or other component that violates a WHATWG special-scheme constraint (e.g. a special scheme with an empty host, or a forbidden host code point under the stricter forbidden-domain set, §7), or a structure with no valid WHATWG canonicalization. `toUrl()` MUST NOT throw for these expected cases; it returns a typed `Err`. The conversion applies `Url`-profile EAGER canonicalization (host pipeline, default-port elision, path rooting) to the components on success.

[MODEL-43] The pair of conversions is not required to be a round-trip identity: `uri.toUrl()` followed by `.toUri()` MAY differ from the original `uri` because `Url` canonicalization is lossy with respect to PRESERVE forms. Implementations MUST document that `Url` conversions canonicalize and MUST NOT claim byte-exact round-tripping through `Url`.

### 3.14 Interop constraints on the model

[MODEL-44] The public model MUST NOT use `java.util.Optional` (or `OptionalInt`/`OptionalLong`) for any component or accessor. Absence MUST be expressed with Kotlin nullable types (`String?`, `Int?`, `Host?`) — which surface to Java as ordinary nullable references annotated for nullability.

[MODEL-45] The public model MUST NOT wrap component `String` values in a `@JvmInline value class` (e.g. a `UriString`/`Scheme`/`Host`-string wrapper) on the public surface, because value-class boxing mangles JVM signatures and degrades Java interop. Public `String`-typed components MUST be exposed as plain `String`/`String?`. `@JvmInline value class` MAY be used for purely-internal, non-public typed quantities (e.g. internal offset or code-point-set indices) where no Java-visible signature is affected.

[MODEL-46] Nullable public accessors that distinguish absence (per §3.2) MUST be annotated such that Java consumers observe the nullability (`scheme` non-null on `Url`, nullable on `Uri`; `host`, `port`, `query`, `fragment`, `user`, `password` nullable on both). The non-null invariants of [MODEL-8]/[MODEL-9] and the always-present `path` of [MODEL-26] MUST be reflected as non-null in the public type so callers get accurate null-safety without defensive checks.

## 4. Character Repertoire & Grammar

This section fixes the formal vocabulary on which the rest of this specification builds: the RFC 3986 ABNF productions (§4.1), the WHATWG URL Standard code-point classes expressed as normative sets (§4.2), and the reconciliation rules that determine which of the two governs in each profile (§4.3). Later sections refer to these productions and classes by name; this section is the single authoritative definition of each.

Throughout this section, "code point" means a Unicode scalar value, "octet" means an 8-bit byte, and all hexadecimal literals of the form `U+XXXX` denote Unicode code points. Character literals in tables are given as `U+XXXX (g)` where `g` is the glyph, except for non-graphic code points, which are named.

### 4.1 RFC 3986 Collected ABNF

The grammar in this subsection is the RFC 3986 generic-URI grammar, reproduced from RFC 3986 Appendix A together with the core rules it depends on. It uses the ABNF notation of RFC 5234. The terminal core rules `ALPHA`, `DIGIT`, and `HEXDIG` are defined at the end of this subsection.

**[GRAM-1]** An implementation of the `Uri` profile MUST treat the productions in this subsection as the normative syntax for parsing and for validation. Where the `Uri` profile validates a component (see §12), a component value SHALL be accepted only if it matches the production assigned to that component in this subsection.

**[GRAM-2]** The `Url` profile MUST NOT use these productions as acceptance gates. The `Url` profile parses by the algorithm of §8 and the host pipeline of §7; the productions here are referenced by the `Url` profile only where §4.3 or a later section names a specific production as shared.

#### 4.1.1 Top-level and hierarchical productions

```abnf
URI           = scheme ":" hier-part [ "?" query ] [ "#" fragment ]

hier-part     = "//" authority path-abempty
              / path-absolute
              / path-rootless
              / path-empty

URI-reference = URI / relative-ref

absolute-URI  = scheme ":" hier-part [ "?" query ]

relative-ref  = relative-part [ "?" query ] [ "#" fragment ]

relative-part = "//" authority path-abempty
              / path-absolute
              / path-noscheme
              / path-empty
```

#### 4.1.2 Scheme, authority, userinfo, host, port

```abnf
scheme        = ALPHA *( ALPHA / DIGIT / "+" / "-" / "." )

authority     = [ userinfo "@" ] host [ ":" port ]

userinfo      = *( unreserved / pct-encoded / sub-delims / ":" )

host          = IP-literal / IPv4address / reg-name

port          = *DIGIT
```

#### 4.1.3 Host literals

```abnf
IP-literal    = "[" ( IPv6address / IPvFuture ) "]"

IPvFuture     = "v" 1*HEXDIG "." 1*( unreserved / sub-delims / ":" )

IPv6address   =                            6( h16 ":" ) ls32
              /                       "::" 5( h16 ":" ) ls32
              / [               h16 ] "::" 4( h16 ":" ) ls32
              / [ *1( h16 ":" ) h16 ] "::" 3( h16 ":" ) ls32
              / [ *2( h16 ":" ) h16 ] "::" 2( h16 ":" ) ls32
              / [ *3( h16 ":" ) h16 ] "::"    h16 ":"   ls32
              / [ *4( h16 ":" ) h16 ] "::"              ls32
              / [ *5( h16 ":" ) h16 ] "::"              h16
              / [ *6( h16 ":" ) h16 ] "::"

h16           = 1*4HEXDIG
ls32          = ( h16 ":" h16 ) / IPv4address

IPv4address   = dec-octet "." dec-octet "." dec-octet "." dec-octet

dec-octet     = DIGIT                 ; 0-9
              / %x31-39 DIGIT         ; 10-99
              / "1" 2DIGIT            ; 100-199
              / "2" %x30-34 DIGIT     ; 200-249
              / "25" %x30-35          ; 250-255

reg-name      = *( unreserved / pct-encoded / sub-delims )
```

**[GRAM-3]** In the `Uri` profile, the `IPv4address` production above is exact: each `dec-octet` MUST denote a value in the range 0 through 255 written in decimal with no superfluous leading zeros beyond what the production permits, and the address MUST consist of exactly four octets. A string that resembles a dotted-decimal address but does not match `IPv4address` (for example `1.2.3.256`, `01.2.3.4`, or `1.2.3`) is, in the `Uri` profile, a `reg-name` and MUST be modelled as `Host.RegName`, not `Host.Ipv4`. The WHATWG shorthand/hex/octal forms of §7 are out of scope for this production and apply only to the `Url` profile.

#### 4.1.4 Paths

```abnf
path          = path-abempty    ; begins with "/" or is empty
              / path-absolute   ; begins with "/" but not "//"
              / path-noscheme   ; begins with a non-colon segment
              / path-rootless   ; begins with a segment
              / path-empty      ; zero characters

path-abempty  = *( "/" segment )
path-absolute = "/" [ segment-nz *( "/" segment ) ]
path-noscheme = segment-nz-nc *( "/" segment )
path-rootless = segment-nz *( "/" segment )
path-empty    = 0<pchar>

segment       = *pchar
segment-nz    = 1*pchar
segment-nz-nc = 1*( unreserved / pct-encoded / sub-delims / "@" )
              ; non-zero-length segment without any colon ":"
```

**[GRAM-4]** The `segment-nz-nc` production MUST be applied to the first segment of a `path-noscheme` (a relative reference whose first segment must not be mistaken for a scheme). A colon appearing in that first segment is a syntax error in the `Uri` profile and MUST be rejected or, where the component is being constructed rather than parsed, remedied — either by percent-encoding the colon or by prefixing the path with the RFC 3986 §4.2 `./` dot-segment guard (as `Uri.Builder` does, permitted by [PATH-22]); the colon MUST NOT be emitted unescaped in a leading `segment-nz-nc`.

#### 4.1.5 Query and fragment

```abnf
query         = *( pchar / "/" / "?" )
fragment      = *( pchar / "/" / "?" )
```

#### 4.1.6 Percent-encoding and character classes

```abnf
pct-encoded   = "%" HEXDIG HEXDIG

pchar         = unreserved / pct-encoded / sub-delims / ":" / "@"

reserved      = gen-delims / sub-delims

gen-delims    = ":" / "/" / "?" / "#" / "[" / "]" / "@"

sub-delims    = "!" / "$" / "&" / "'" / "(" / ")"
              / "*" / "+" / "," / ";" / "="

unreserved    = ALPHA / DIGIT / "-" / "." / "_" / "~"
```

The members of each class are enumerated below for unambiguous reference; the enumeration is normative and is equivalent to the ABNF above.

Table 4.1.6-a — `unreserved`

| Group | Code points |
|---|---|
| `ALPHA` | `U+0041`–`U+005A` (A–Z), `U+0061`–`U+007A` (a–z) |
| `DIGIT` | `U+0030`–`U+0039` (0–9) |
| Marks | `U+002D` (-), `U+002E` (.), `U+005F` (_), `U+007E` (~) |

Table 4.1.6-b — `gen-delims`

| Glyph | Code point |
|---|---|
| `:` | `U+003A` |
| `/` | `U+002F` |
| `?` | `U+003F` |
| `#` | `U+0023` |
| `[` | `U+005B` |
| `]` | `U+005D` |
| `@` | `U+0040` |

Table 4.1.6-c — `sub-delims`

| Glyph | Code point | Glyph | Code point |
|---|---|---|---|
| `!` | `U+0021` | `*` | `U+002A` |
| `$` | `U+0024` | `+` | `U+002B` |
| `&` | `U+0026` | `,` | `U+002C` |
| `'` | `U+0027` | `;` | `U+003B` |
| `(` | `U+0028` | `=` | `U+003D` |
| `)` | `U+0029` |  |  |

#### 4.1.7 Terminal core rules

```abnf
ALPHA  = %x41-5A / %x61-7A   ; A-Z / a-z
DIGIT  = %x30-39             ; 0-9
HEXDIG = DIGIT / "A" / "B" / "C" / "D" / "E" / "F"
```

**[GRAM-5]** `ALPHA` and `DIGIT` are exactly the ASCII ranges above; no Unicode "letter" or "digit" category, and no fullwidth or other compatibility variant, is a member of `ALPHA` or `DIGIT` for the purpose of any production in this specification.

**[GRAM-6]** The core rule `HEXDIG` as written admits only uppercase `A`–`F`. For parsing `pct-encoded`, `h16`, and `IPvFuture` in the `Uri` profile, an implementation MUST accept both uppercase (`A`–`F`, `U+0041`–`U+0046`) and lowercase (`a`–`f`, `U+0061`–`U+0066`) hexadecimal letters; the two are equivalent on input. This relaxation matches RFC 3986 §6.2.2.1, which declares percent-encoding hexadecimal case-insensitive on input.

**[GRAM-7]** On serialization of a `pct-encoded` triplet, an implementation MUST emit the two hexadecimal digits using uppercase `A`–`F`. Lowercase hexadecimal digits MUST NOT appear in a serialized percent-encoding triplet. (Hexadecimal case inside `IPv6address` host serialization is governed separately by §7's RFC 5952 rules, which also mandate lowercase for that distinct context.)

### 4.2 WHATWG Code-Point Classes

This subsection defines, as normative sets, the WHATWG URL Standard code-point classes used by the `Url` profile (and, where §4.3 so states, by shared machinery). Each class is a predicate over a single code point unless stated otherwise. Membership tables are exhaustive for the ASCII range; for ranges above `U+007F`, the bound is given numerically.

**[GRAM-8]** An implementation MUST define each class below as a constant-time membership test whose result for any code point equals the membership stated in the corresponding table. For the ASCII range (`U+0000`–`U+007F`) the test MUST be exact; classes whose definition extends beyond ASCII MUST honour the stated numeric bounds and the surrogate/noncharacter exclusions where specified.

#### 4.2.1 C0 control, C0 control or space

Table 4.2.1-a — `C0 control` and `C0 control or space`

| Class | Members |
|---|---|
| **C0 control** | `U+0000`–`U+001F`, inclusive |
| **C0 control or space** | `U+0000`–`U+001F` inclusive, plus `U+0020` SPACE |

**[GRAM-9]** A code point is a **C0 control or space** if and only if it is less than or equal to `U+0020`. This class governs the stripping of leading and trailing control-or-space code points from input in the `Url` profile (see §8) and MUST NOT be applied as a stripping rule in the `Uri` profile.

#### 4.2.2 ASCII tab or newline

Table 4.2.2-a — `ASCII tab or newline`

| Glyph / name | Code point |
|---|---|
| CHARACTER TABULATION (tab) | `U+0009` |
| LINE FEED (LF) | `U+000A` |
| CARRIAGE RETURN (CR) | `U+000D` |

**[GRAM-10]** A code point is an **ASCII tab or newline** if and only if it is `U+0009`, `U+000A`, or `U+000D`. In the `Url` profile, every occurrence of an ASCII tab or newline MUST be removed from the input before component parsing, and each such removal is a non-fatal validation error (see §8 and §12). In the `Uri` profile, an embedded ASCII tab or newline MUST NOT be silently removed; it is a syntax error subject to §12.

#### 4.2.3 Forbidden host code point

Table 4.2.3-a — `forbidden host code point`

| Code point | Glyph / name | Code point | Glyph / name |
|---|---|---|---|
| `U+0000` | NULL | `U+003E` | `>` |
| `U+0009` | tab | `U+003F` | `?` |
| `U+000A` | LF | `U+0040` | `@` |
| `U+000D` | CR | `U+005B` | `[` |
| `U+0020` | SPACE | `U+005C` | `\` (backslash) |
| `U+0023` | `#` | `U+005D` | `]` |
| `U+002F` | `/` | `U+005E` | `^` |
| `U+003A` | `:` | `U+007C` | `\|` (vertical line) |
| `U+003C` | `<` |  |  |

**[GRAM-11]** A code point is a **forbidden host code point** if and only if it is one of the seventeen code points in Table 4.2.3-a. This class governs the validation of an opaque host (`Host.Opaque`) and the early rejection of malformed authorities in the `Url` profile (see §7). A host string that contains any forbidden host code point, other than as part of a percent-encoding where percent-encoding is permitted for that host kind, MUST be rejected with a fatal `UriParseError`.

#### 4.2.4 Forbidden domain code point

Table 4.2.4-a — `forbidden domain code point` (additions over the forbidden host set)

| Source | Members |
|---|---|
| All forbidden host code points | the seventeen code points of Table 4.2.3-a |
| Percent sign | `U+0025` (`%`) |
| C0 controls | `U+0000`–`U+001F`, inclusive |
| DELETE | `U+007F` |

**[GRAM-12]** A code point is a **forbidden domain code point** if and only if it is a forbidden host code point, or `U+0025` (`%`), or a C0 control (`U+0000`–`U+001F`), or `U+007F` DELETE. This is a strict superset of the forbidden host code point class. It governs validation of a domain (`Host.RegName` / `Host.Ipv4` input) after the host has passed through the IDNA ToASCII pipeline of §7.

**[GRAM-13]** In the `Url` profile, after IDNA ToASCII has produced an ASCII domain string, an implementation MUST re-scan that ASCII string for forbidden domain code points and MUST reject the host with a fatal `UriParseError` if any is present. The re-scan is mandatory and MUST occur after, not before, IDNA processing.

> Note: a conforming byte-table implementation that operates over UTF-8 octets MAY treat every octet greater than or equal to `0x7F` as forbidden in the domain scan, because a domain that has completed IDNA ToASCII is pure ASCII and any high octet at that point is necessarily a defect. This is an equivalent realization of [GRAM-12] for the post-IDNA ASCII string; it MUST NOT be applied to input that has not yet been transcoded by IDNA. Note: derived from ada `src/unicode.cpp` (`is_forbidden_host_code_point_table`, `is_forbidden_domain_code_point_table`); cf. WHATWG URL §3.5.

#### 4.2.5 URL code points

**[GRAM-14]** The **URL code points** are: the ASCII alphanumerics (`ALPHA` and `DIGIT` as defined in §4.1.7), the code points in Table 4.2.5-a, and every code point in the range `U+00A0` to `U+10FFFD` inclusive that is neither a surrogate (`U+D800`–`U+DFFF`) nor a noncharacter.

Table 4.2.5-a — ASCII URL code points beyond alphanumerics

| Glyph | Code point | Glyph | Code point |
|---|---|---|---|
| `!` | `U+0021` | `;` | `U+003B` |
| `$` | `U+0024` | `=` | `U+003D` |
| `&` | `U+0026` | `?` | `U+003F` |
| `'` | `U+0027` | `@` | `U+0040` |
| `(` | `U+0028` | `_` | `U+005F` |
| `)` | `U+0029` | `~` | `U+007E` |
| `*` | `U+002A` | `-` | `U+002D` |
| `+` | `U+002B` | `.` | `U+002E` |
| `,` | `U+002C` | `/` | `U+002F` |
| `:` | `U+003A` |  |  |

**[GRAM-15]** A **noncharacter**, for the purpose of [GRAM-14], is any of `U+FDD0`–`U+FDEF` inclusive, or any code point whose low 16 bits are `0xFFFE` or `0xFFFF` (that is, `U+FFFE`, `U+FFFF`, `U+1FFFE`, `U+1FFFF`, …, `U+10FFFE`, `U+10FFFF`).

**[GRAM-16]** The URL code points class is advisory, not a gate: in the `Url` profile, a code point in a parsed component that is not a URL code point and is not a `U+0025` (`%`) introducing a valid percent-encoding is a non-fatal validation error (see §12) and MUST NOT, by itself, cause parsing to fail. The presence of such a code point does not change how the component is otherwise serialized; serialization is governed by the percent-encode sets of §4.2.6 and §5.

#### 4.2.6 Percent-encode set memberships

The `Url` profile decides, per component, which code points are emitted literally and which are percent-encoded by testing membership in a named percent-encode set. The complete enumerated contents of each set, and the algorithm that applies them, are normative in §5; this subsection only names the four component-level sets and binds each to the component it governs, so that §4 readers can locate them.

Table 4.2.6-a — component-level percent-encode sets (contents defined in §5)

| Component | Governing percent-encode set | Defined in |
|---|---|---|
| fragment | **fragment percent-encode set** | §5 |
| query | **query percent-encode set** (and the **special-query percent-encode set** for special schemes) | §5 |
| path | **path percent-encode set** | §5 |
| userinfo | **userinfo percent-encode set** | §5 |

**[GRAM-17]** An implementation MUST NOT define the contents of these four sets locally to §4; the authoritative membership of each set, including the nesting relationship in which each set is a superset of the C0 control percent-encode set, is the matrix in §5. Any reference to "the fragment / query / path / userinfo percent-encode set" elsewhere in this specification denotes the set as defined in §5.

> Note: the query component additionally selects between the query percent-encode set and the special-query percent-encode set on whether the scheme is special (see §6). The host component is not governed by a percent-encode set at all in the `Url` profile; it is governed by the forbidden-code-point classes of §4.2.3–§4.2.4 and the IDNA pipeline of §7. Note: derived from ada `character_sets.h` / WHATWG URL §1.3.

### 4.3 Reconciliation: RFC 3986 Productions vs WHATWG Classes

The two vocabularies of §4.1 and §4.2 overlap but are not identical. This subsection states, normatively, how the RFC 3986 productions map to the WHATWG classes and which governs in each profile. The general rule is that the `Uri` profile is governed by §4.1 and the `Url` profile by §4.2 and §5; the per-topic rules below refine that division where the two vocabularies touch the same code points.

**[GRAM-18]** Profile governance is absolute for acceptance. In the `Uri` profile, whether a component is well-formed MUST be decided by the ABNF productions of §4.1. In the `Url` profile, whether parsing succeeds MUST be decided by the algorithm of §8 together with the host classes of §4.2.3–§4.2.4; the ABNF productions of §4.1 MUST NOT be used to reject input in the `Url` profile.

#### 4.3.1 Unreserved / URL code points

The RFC `unreserved` set (Table 4.1.6-a) is exactly the code points that are never percent-encoded by either profile and never require encoding for safety. The WHATWG URL code points set (§4.2.5) is broader: it additionally admits most `sub-delims`, several `gen-delims`, and the entire non-ASCII range `U+00A0`–`U+10FFFD` minus surrogates and noncharacters.

**[GRAM-19]** Membership in `unreserved` MUST be treated identically by both profiles: a code point in `unreserved` MUST NOT be percent-encoded on output, and an existing percent-encoding of an `unreserved` code point MAY be decoded only where the active normalization policy of §11 permits it. Membership in the URL code points set MUST NOT be used by the `Uri` profile for any decision; it is a `Url`-profile-only advisory class per [GRAM-16].

#### 4.3.2 Reserved / sub-delims vs forbidden sets

The RFC `gen-delims` and `sub-delims` classes are syntactic delimiters: they are permitted in some components and forbidden (or must be percent-encoded) in others, but RFC 3986 never treats any of them as a hard "forbidden everywhere in host" code point. The WHATWG forbidden host code point class (§4.2.3) singles out a subset of `gen-delims` (`:`, `/`, `?`, `#`, `[`, `]`, `@`) plus `<`, `>`, `^`, `|`, `\`, SPACE, and the control code points as forbidden inside a host.

**[GRAM-20]** In the `Uri` profile, a `reg-name` host is governed solely by the `reg-name` production (`*( unreserved / pct-encoded / sub-delims )`); the WHATWG forbidden host and forbidden domain classes MUST NOT be applied to a `Uri`-profile host. In the `Url` profile, a host is governed by §4.2.3–§4.2.4 and §7; the `reg-name` production MUST NOT be applied as the acceptance test for a `Url`-profile host.

**[GRAM-21]** The backslash `U+005C` (`\`) is a forbidden host code point in the `Url` profile (Table 4.2.3-a) and, for special schemes, is treated as equivalent to `/` outside the host by the algorithm of §8. In the `Uri` profile, `U+005C` is an ordinary code point that is not part of any RFC 3986 production except where it appears percent-encoded; it MUST NOT be rewritten to `/` and MUST NOT be treated as a path or authority delimiter.

#### 4.3.3 Hexadecimal, ALPHA, and DIGIT

**[GRAM-22]** The definitions of `ALPHA` and `DIGIT` in §4.1.7 ([GRAM-5]) are shared verbatim by both profiles wherever those classes are referenced (scheme start, scheme continuation, IPv4 decimal parsing, percent-encoding hexadecimal). Neither profile extends `ALPHA` or `DIGIT` to any non-ASCII or compatibility code point. In particular, the IDNA label-separator mappings and fullwidth-digit handling of §7 operate before host parsing and MUST NOT be conflated with `DIGIT`.

**[GRAM-23]** Percent-encoding hexadecimal is case-insensitive on input in both profiles ([GRAM-6]) and is emitted uppercase on output in both profiles ([GRAM-7]). This rule is identical across profiles and is independent of the per-profile normalization policy of §11; a profile's choice not to re-normalize existing triplets (the `Url` profile preserves existing triplets rather than re-canonicalizing their byte values) does not change the requirement that any triplet the implementation itself emits use uppercase hexadecimal digits.

#### 4.3.4 Whitespace and controls

**[GRAM-24]** ASCII tab and newline (§4.2.2) and leading/trailing C0-control-or-space (§4.2.1) are removed or stripped only in the `Url` profile, each removal being a non-fatal validation error. In the `Uri` profile these same code points are not stripped: an embedded C0 control or DEL is rejected, but an embedded space is neither stripped nor rejected — the non-validating splitter preserves it verbatim; the `Uri` profile does not re-validate component content against the full grammar (only a raw C0/DEL and a malformed `%xx` are fatal). An implementation MUST NOT share a single "strip whitespace" pass across both profiles; the stripping behaviour is profile-specific by [GRAM-9] and [GRAM-10].

> Note: this reconciliation reflects the locked architecture in which one shared engine is parameterized by `ParseProfile` (`URI` vs `URL`); the productions of §4.1 and the classes of §4.2 are both compiled into the engine, and the active `ParseProfile` selects which set of rules gates acceptance versus merely records a validation error. Note: cf. WHATWG URL §4.4 (basic URL parser) and RFC 3986 §3 / Appendix A.

## 5. Percent-Encoding

This section is the normative authority for percent-encoding and percent-decoding in `kuri`. It defines every named **percent-encode set**, the **encode** algorithm, the **decode** algorithm, **triplet case normalization**, and the **already-encoded / idempotency** contract. A domain or reg-name host does not use a percent-encode set; its encoding is governed by the host pipeline (cross-ref §7). A non-special-scheme opaque host, however, is percent-encoded with the C0-control percent-encode set ([PCT-5]), per the WHATWG opaque-host parser.

A **percent-encoded triplet** is the three-octet sequence `%` followed by two hexadecimal digits (`0`–`9`, `A`–`F`, `a`–`f`). To **percent-encode** an octet is to emit `%` followed by the two uppercase hexadecimal digits of its value. To **percent-decode** a triplet is to reproduce the single octet it denotes.

> Note: encode sets derived from ada `character_sets-inl.h` (the eight `*_PERCENT_ENCODE` bitmaps), okhttp `-Url.kt` encode-set constants, and ktor `Codecs.kt`; cf. WHATWG URL §1.3 (percent-encode sets) and RFC 3986 §2.1.

### 5.1 Percent-encode sets

#### 5.1.1 The universal encode rule

Every percent-encode set named in this section is defined as a list of ASCII code points in the printable range U+0020–U+007E. A set's full behaviour layers three always-on conditions on top of that list.

**[PCT-1]** For every percent-encode set defined in §5.1 *except* the C0-control set, a code point `c` MUST be percent-encoded when **any** of the following holds:

| # | Condition | Code points |
|---|---|---|
| a | `c` is a C0 control | U+0000 – U+001F |
| b | `c` is DEL | U+007F |
| c | `c` appears in the set's explicit code-point list (§5.1.3) | U+0020 – U+007E |
| d | `c` is non-ASCII and the active profile encodes non-ASCII for this component | > U+007F |

**[PCT-2]** Condition (d) is profile-dependent. In the `Url` profile every non-ASCII code point in a component governed by a percent-encode set MUST be percent-encoded (after UTF-8 encoding, §5.2). In the `Uri` profile the parser is preserve-by-default: a non-ASCII code point in the path, query, or fragment is retained **as-is** — neither percent-encoded nor rejected — so the IRI character repertoire is carried through unchanged. The RFC 3987 §3.1 percent-encoding of UTF-8 octets is applied on demand by the `Iri` conversion facility (§11.5), not during a strict `Uri` parse. A code point that is preserved unencoded MUST NOT be partially encoded.

**[PCT-3]** A code point not selected by [PCT-1] MUST be copied to the output unchanged (identity). ASCII letters (`A`–`Z`, `a`–`z`), ASCII digits (`0`–`9`), and the code points `*` `-` `.` `_` are never members of any explicit list in §5.1.3 and therefore pass through identity in every set except the `application/x-www-form-urlencoded` set (which still passes `*` `-` `.` `_` and the alphanumerics — see §5.1.4).

**[PCT-4]** The handling of U+0025 (`%`) is **not** governed by a set's explicit list. It is governed solely by the already-encoded rules of §5.5. An implementation MUST NOT add `%` to any encode-set list; doing so would prevent emitting triplets.

#### 5.1.2 Master matrix

The following matrix is normative for the printable ASCII punctuation range. `●` means the code point is forced to a `%XX` triplet by that set; `⊕` means the special substitution of §5.1.4 (space → `+`); a blank cell means identity (the code point passes through unchanged). Alphanumerics (`0`–`9`, `A`–`Z`, `a`–`z`) are blank in every column and are omitted. The C0-control column is blank for the entire printable range by definition and is omitted from the body; see §5.1.3.

| Code point | Fragment | Query | Special-query | Path | Userinfo | Form | Query-component |
|---|:--:|:--:|:--:|:--:|:--:|:--:|:--:|
| U+0020 space | ● | ● | ● | ● | ● | ⊕ | ● |
| `!` | | | | | | ● | ● |
| `"` | ● | ● | ● | ● | ● | ● | ● |
| `#` | | ● | ● | ● | ● | ● | ● |
| `$` | | | | | | ● | ● |
| `%` | see §5.5 | see §5.5 | see §5.5 | see §5.5 | see §5.5 | ● | see §5.5 |
| `&` | | | | | | ● | ● |
| `'` | | | ● | | | ● | ● |
| `(` | | | | | | ● | ● |
| `)` | | | | | | ● | ● |
| `*` | | | | | | | |
| `+` | | | | | | ● | |
| `,` | | | | | | ● | ● |
| `-` | | | | | | | |
| `.` | | | | | | | |
| `/` | | | | | ● | ● | ● |
| `:` | | | | | ● | ● | ● |
| `;` | | | | | ● | ● | ● |
| `<` | ● | ● | ● | ● | ● | ● | ● |
| `=` | | | | | ● | ● | ● |
| `>` | ● | ● | ● | ● | ● | ● | ● |
| `?` | | | | ● | ● | ● | ● |
| `@` | | | | | ● | ● | ● |
| `[` | | | | | ● | ● | ● |
| `\` | | | | | ● | ● | ● |
| `]` | | | | | ● | ● | ● |
| `^` | | | | ● | ● | ● | ● |
| `_` | | | | | | | |
| `` ` `` | ● | | | ● | ● | ● | ● |
| `{` | | | | ● | ● | ● | ● |
| `\|` | | | | | ● | ● | ● |
| `}` | | | | ● | ● | ● | ● |
| `~` | | | | | | ● | ● |

#### 5.1.3 Per-set definitions

Each named set is defined below by its explicit code-point list (the §5.1.1 condition (c) members). The sets nest: each row's list is a superset of the one cited as its base.

**[PCT-5]** The **C0-control percent-encode set** has an empty explicit list. A code point is encoded under this set only via conditions (a), (b), and (d) of [PCT-1]. This is the most permissive set and is used for the opaque-path component of special-less inputs (cross-ref §9).

**[PCT-6]** The **fragment percent-encode set** explicit list MUST be exactly:
`U+0020 (space)`, `"`, `<`, `>`, `` ` ``.

**[PCT-7]** The **query percent-encode set** explicit list MUST be exactly:
`U+0020 (space)`, `"`, `#`, `<`, `>`.

**[PCT-8]** The **special-query percent-encode set** explicit list MUST be exactly the query set (PCT-7) plus `'` (U+0027). In the `Url` profile this set is applied to the query of a special-scheme URL (`http`, `https`, `ws`, `wss`, `ftp`, `file`); the query set (PCT-7) is applied otherwise (cross-ref §6).

**[PCT-9]** The **path percent-encode set** explicit list MUST be exactly the query set (PCT-7) plus `?`, `` ` ``, `{`, `}`, `^`.

> Note: ada's `PATH_PERCENT_ENCODE` bitmap includes `^` (U+005E) in the path set, matching deployed browser/Node behaviour; it does **not** include `|`. kuri follows the ada bitmap. An implementer cross-checking against an older WHATWG snapshot that places `^` only at the userinfo layer MUST nonetheless encode `^` in the path component for the `Url` profile.

**[PCT-10]** The **userinfo percent-encode set** explicit list MUST be exactly the path set (PCT-9) plus `/`, `:`, `;`, `=`, `@`, `[`, `\`, `]`, `|`. The full userinfo list is therefore:
`U+0020 (space)`, `"`, `#`, `/`, `:`, `;`, `<`, `=`, `>`, `?`, `@`, `[`, `\`, `]`, `^`, `` ` ``, `{`, `|`, `}`.

**[PCT-11]** The userinfo set MUST be applied independently to the decoded username and the decoded password before they are joined. Because `:` (U+003A) is a member of the set, a literal colon inside either part is encoded to `%3A` and does not introduce a spurious user/password boundary.

**[PCT-12]** The **query-component percent-encode set** (a single query name or a single query value, okhttp `QUERY_COMPONENT_ENCODE_SET`) explicit list MUST be exactly:
`U+0020 (space)`, `!`, `"`, `#`, `$`, `&`, `'`, `(`, `)`, `,`, `/`, `:`, `;`, `<`, `=`, `>`, `?`, `@`, `[`, `]`, `\`, `^`, `` ` ``, `{`, `|`, `}`, `~`.
This set is stricter than the whole-query sets (PCT-7/PCT-8): it forces `&`, `=`, and `#` to be encoded so a name or value cannot inject a pair separator, an assignment, or a fragment delimiter into the assembled query string. It does **not** include `+` (a literal `+` in a query-component value survives as `+`; the form set, PCT-13, is the only set that rewrites `+`).

**[PCT-40]** The **component percent-encode set** (the WHATWG `encodeURIComponent` equivalent) MUST be exactly the userinfo set ([PCT-10]) plus `$` (U+0024), `&` (U+0026), `+` (U+002B), and `,` (U+002C); within the component set, `%` (U+0025) is also a forced member, so the set is one of only two §5.1 sets that round-trips `%`. UTF-8 percent-encoding with the component set MUST give results identical to JavaScript's `encodeURIComponent()`. This set is provided for embedding arbitrary data into a URL path, query, fragment, or opaque host; it is distinct from the okhttp-derived query-component set ([PCT-12]).

#### 5.1.4 application/x-www-form-urlencoded

**[PCT-13]** The **`application/x-www-form-urlencoded` percent-encode set** is defined by exclusion: a code point is passed through identity if and only if it is an ASCII letter, an ASCII digit, or one of `*`, `-`, `.`, `_`. Every other code point in U+0020–U+007E, every C0 control, DEL, and every non-ASCII code point (after UTF-8 encoding) MUST be percent-encoded, **except** as overridden by [PCT-14].

**[PCT-14]** Within the form set, and **only** within the form set:
- U+0020 (space) MUST be serialized as `+` (U+002B), not as `%20`.
- A literal `+` (U+002B) supplied as input data MUST be percent-encoded to `%2B`, so that the `+`↔space convention is unambiguous on decode.

**[PCT-15]** The form set is a serializer concern of the form layer (cross-ref §10) and MUST NOT be applied to the generic query, path, fragment, or userinfo components. No component other than the form layer maps space to `+`.

#### 5.1.5 Host handling pointer

**[PCT-16]** A *domain* host (the IDNA pipeline) and an RFC `reg-name` host MUST NOT be processed through any percent-encode set defined in §5.1; their encoding is governed by the host pipeline. In the `Url` profile a domain host is processed by forbidden-domain code-point checks plus IDNA (UTS-46 ToASCII). A non-special-scheme **opaque host**, however, IS percent-encoded — after forbidden-host code-point validation — using the **C0-control percent-encode set** ([PCT-5]), exactly as the WHATWG opaque-host parser specifies (cross-ref §7, [MODEL-21], [PATH-18]). The host pipeline produces a `RegName`, `Ipv4`, `Ipv6`, `Empty`, or `Opaque` host (cross-ref §7). In the `Uri` profile a registered-name host is percent-encoded using the RFC 3986 `reg-name` rule (the unencoded set is `unreserved` ∪ `sub-delims`; everything else is encoded), defined normatively in §7; this is a registered-name rule, not one of the §5.1 sets.

### 5.2 The percent-encode algorithm

**[PCT-17]** `percentEncode(input, set, alreadyEncoded)` takes a Unicode string `input`, a percent-encode set `set`, and the boolean `alreadyEncoded` flag of §5.5, and returns a string in which exactly the code points selected by [PCT-1] (as modified by §5.5 for `%`) have been replaced by triplets. The algorithm MUST process `input` by Unicode code point (handling surrogate pairs as single code points), not by UTF-16 code unit.

**[PCT-18]** A code point selected for encoding MUST first be encoded to octets using **UTF-8** (in the `Url` profile and by default), and each resulting octet MUST then be emitted as a triplet. Multi-octet code points therefore produce multiple consecutive triplets (e.g. U+00E9 `é` → `%C3%A9`). An implementation MAY support an alternative output charset for the form layer only; absent an explicit charset, UTF-8 MUST be used.

**[PCT-19]** Hexadecimal digits in emitted triplets MUST be **uppercase** (`%XX`, digits `0`–`9` and `A`–`F`). An implementation MUST NOT emit lowercase digits when producing output.

**[PCT-20]** The encoder MUST provide a zero-allocation fast path: it scans `input` for the first code point that requires encoding under [PCT-1]/§5.5; if no such code point exists, it MUST return the input slice itself (or an equal view) without allocating a new buffer. Only when an encodable code point is found does the encoder allocate and copy.

**[PCT-21]** The encoder MUST be total over all input strings: every code point either passes through identity or is encoded; the encoder MUST NOT throw on any input (malformed UTF-16, lone surrogates, control characters). A lone surrogate that cannot be UTF-8 encoded MUST be emitted as the UTF-8 encoding of U+FFFD (`%EF%BF%BD`).

### 5.3 The percent-decode algorithm

Percent-decoding is **lenient**: it never rejects input.

**[PCT-22]** `percentDecode(input)` scans `input` left to right and produces an octet stream as follows. For each position:
- If the code point is `%` **and** it is followed by two hexadecimal digits, the two digits are parsed and the resulting octet is appended to the octet stream; the scan advances past all three characters.
- Otherwise the code point is appended to the octet stream as its UTF-8 octets and the scan advances by one code point.

**[PCT-23]** A `%` that is **not** followed by two hexadecimal digits — including a trailing `%` or `%x` at the end of input, and a `%` followed by a non-hex character — MUST be left **literal**: the `%` octet (U+0025) is appended to the output and the scan advances by one. The decoder MUST NOT throw on an incomplete or malformed escape.

> Note: this diverges deliberately from ktor `Codecs.kt`, which throws `URLDecodeException` on incomplete/invalid escapes. kuri's decode is lenient by contract.

**[PCT-24]** Hexadecimal-digit parsing in decode MUST be **case-insensitive**: `%7a`, `%7A`, `%6d`, and `%6D` decode to the same octet. The pair `%7a` and `%7A` both decode to the octet 0x7A (`z`).

**[PCT-25]** Consecutive triplets MUST be gathered into a contiguous run of octets before that run is interpreted as text. The octet run is then decoded as **UTF-8** when a `String` result is requested. A maximal run of triplets that together form a valid multi-octet UTF-8 sequence decodes to the corresponding code point (e.g. `%C3%A9` → `é`).

**[PCT-26]** When producing a `String`, an octet (or octet subsequence) that is **not** valid UTF-8 MUST be replaced by U+FFFD (REPLACEMENT CHARACTER) using the WHATWG-compatible UTF-8 decoder replacement behaviour. The number of U+FFFD substitutions MUST follow the standard maximal-subpart rule for ill-formed sequences.

**[PCT-27]** Lossy U+FFFD substitution applies **only** to the `String` view. An implementation MUST also expose, or be able to reproduce, the original octet sequence (the raw decoded bytes) so that an input → decode-octets → re-encode round trip preserves the original octets exactly, even when those octets are not valid UTF-8. The lossy `String` and the lossless octet view MUST be kept distinct in the API surface (cross-ref §3).

**[PCT-28]** Plain `percentDecode` MUST NOT treat `+` as space. The `+`↔space transformation is a property of the form dialect only and MUST be requested explicitly (cross-ref §10); a generic component decode leaves `+` literal.

### 5.4 Triplet case normalization

**[PCT-29]** Percent-encoded triplets are **case-insensitive** in meaning (PCT-24) but their normalized serialized form uses **uppercase** hexadecimal digits. Triplet case normalization is a *serialization-time* transform: it rewrites the two hex digits of each existing triplet to uppercase and leaves the surrounding octets untouched. It MUST NOT decode the triplet, and MUST NOT alter a `%` that does not introduce a valid triplet.

**[PCT-30]** The parser MUST NOT rewrite triplet case while parsing. Triplet case is preserved in the stored representation exactly as it appeared in the input; any case change happens only when a component is serialized.

**[PCT-31]** In the `Uri` profile (PRESERVE by default), triplet case MUST survive verbatim through parse → serialize when normalization has not been requested. The input `%6d%6D` MUST serialize back as `%6d%6D`, byte-for-byte. Triplet-case normalization in the `Uri` profile is an explicit, opt-in `normalize()` operation (cross-ref §11); when invoked it uppercases every triplet (`%6d%6D` → `%6D%6D`).

**[PCT-32]** In the `Url` profile, triplet case of triplets **already present** in the input is preserved verbatim, matching the WHATWG basic URL parser: the `%` and its two hex digits are members of no path/query/fragment/opaque-host percent-encode set, so they pass through unchanged (`%6d%6D` serializes as `%6d%6D`). Only triplets the implementation itself **emits** while percent-encoding use uppercase hex digits ([PCT-19]). The `Url` profile therefore performs no re-casing of pre-existing triplets; this preserves the round-trip fixed point of [NORM-25].

**[PCT-33]** Triplet case normalization MUST NOT decode-then-re-encode existing triplets. In particular it MUST NOT convert a triplet whose octet is an unreserved character (e.g. `%41` → `A`); unreserved-octet decoding is the separate, opt-in normalization of §11 (`Uri`) and is not performed by the `Url` profile on already-present path/query triplets. Triplet case normalization touches only the two hex digits.

### 5.5 Already-encoded input and the idempotency contract

A builder setter (and any re-encoding pass) accepts component text together with an `alreadyEncoded` flag that declares whether the supplied text is raw (decoded) data or text that already contains percent-encoding.

**[PCT-34]** When `alreadyEncoded = false` (the input is raw data), every literal `%` in the input MUST itself be encoded to `%25`, and all other code points are processed by the active percent-encode set per §5.2. Raw data therefore cannot accidentally be interpreted as containing triplets: the input string `100%` becomes `100%25`.

**[PCT-35]** When `alreadyEncoded = true` (the input is already percent-encoded), a `%` that **introduces a valid triplet** (`%` followed by two hexadecimal digits) MUST be preserved as `%` and the two following hex digits copied through, so the existing triplet is not disturbed. A `%` that does **not** introduce a valid triplet MUST be encoded to `%25` (preventing a stray `%` from corrupting the output while keeping the operation idempotent). Code points other than `%` are still processed by the active percent-encode set.

**[PCT-36]** Re-encoding already-encoded text MUST NOT double-encode a valid triplet. `percentEncode("%41", set, alreadyEncoded = true)` MUST yield `%41`, never `%2541`. The hex digits of a preserved triplet MUST NOT themselves be treated as data to encode.

**[PCT-37]** The encode operation MUST be idempotent over the already-encoded flag: for every input `x` and set `S`,
`percentEncode(percentEncode(x, S, alreadyEncoded = false), S, alreadyEncoded = true)` MUST equal `percentEncode(x, S, alreadyEncoded = false)`.
That is, once data has been encoded, declaring the result already-encoded and encoding again is a no-op (modulo the triplet-case normalization of §5.4, which is itself idempotent).

**[PCT-38]** The `alreadyEncoded` flag controls **only** the treatment of `%` (PCT-34/PCT-35). It MUST NOT change which non-`%` code points are members of the active percent-encode set; a code point in the set is still encoded regardless of the flag.

**[PCT-39]** Tab (U+0009), line feed (U+000A), form feed (U+000C), and carriage return (U+000D) appearing in `alreadyEncoded = true` input that originates from raw URL text are removed by the parser's input-stripping stage (cross-ref §8) before this stage runs. When such a code point reaches the encoder as `alreadyEncoded = false` raw data, it is a C0 control and MUST be percent-encoded under [PCT-1](a) rather than removed. The two behaviours are distinguished by whether the code point is structural URL text (stripped) or component data (encoded).

## 6. Schemes

This section defines the scheme component: its registry of *special* schemes and their default ports, its syntax and validation rules, its normalization, its relationship to `effectivePort`, and the semantic consequences of a scheme being special or non-special. The scheme is the leading component of every absolute-form URI/URL and selects, in the `Url` profile, the host pipeline and serialization rules applied to the remainder of the input.

### 6.1 The special-scheme registry

A **scheme** is the component preceding the first `:` in an absolute-form URI/URL (grammar in §4, parsed in §8). A **special scheme** is one of the six schemes enumerated in Table 6-1. A URI/URL whose scheme is special is a **special URL**; any other URI/URL (including one with no scheme) is a **non-special URL**.

The special-scheme registry is fixed and closed. It is identical in both profiles; the registry itself does not depend on the profile. What differs by profile is whether the special-scheme *behaviours* (host pipeline, slash handling, default-port elision, eager canonicalization) are applied — see §6.5 and the per-topic sections referenced there.

**Table 6-1 — Special-scheme registry**

| Scheme (lowercase) | Default port | Has default port |
|---|---|---|
| `http` | 80 | yes |
| `https` | 443 | yes |
| `ws` | 80 | yes |
| `wss` | 443 | yes |
| `ftp` | 21 | yes |
| `file` | — | no |

**[SCH-1]** An implementation MUST recognize exactly the six schemes in Table 6-1 as special. No other scheme — including `gopher`, `data`, `mailto`, `blob`, `about`, or any user-supplied scheme — is special, regardless of profile.

**[SCH-2]** The default port of a special scheme MUST be exactly the value given in Table 6-1. `file` has no default port; an implementation MUST treat `file` as special yet portless (it has special host/path behaviour but never contributes a default port).

**[SCH-3]** Scheme comparison against the registry MUST be performed on the normalized (lowercased, per §6.3) scheme. A scheme that differs from a registry entry only in case (e.g. `HTTP`, `Https`, `WsS`) is the same special scheme; a scheme that differs in any non-case respect (e.g. `https ` with trailing space, `http2`, `xhttp`) is not special.

**[SCH-4]** The set of special schemes is closed. An implementation MUST NOT provide a public mechanism to register additional special schemes or to alter the default port of a registry entry. The registry is not extensible at runtime.

Note: derived from ada `scheme.h` / `scheme-inl.h` (`is_special_list`, `special_ports`); cf. WHATWG URL §3.1 (special scheme). The enum-with-default-port shape (HTTP/HTTPS/WS/WSS/FTP/FILE plus a NOT_SPECIAL sentinel) is reproduced here as a normative contract, not as an implementation mandate.

#### 6.1.1 Determining "is special"

**[SCH-5]** Given a normalized scheme, the determination of whether it is special, and the lookup of its default port, MUST be a total function with the result defined by Table 6-1: special schemes map to their row, and every other scheme maps to "non-special, no default port".

**[SCH-6]** The "is special" determination MUST run in time independent of the number of distinct schemes previously seen and independent of input length beyond the bounded scheme length (effectively O(1)). An implementation SHOULD model the result as a small closed enumeration with one constant per special scheme plus a single non-special case; it MUST NOT require a linear scan of the input against the registry on the hot path in a way that depends on registry size. The specific representation (perfect hash, length+first-character dispatch, enum lookup, or interned constant) is unspecified and left to the implementation, provided the observable result matches Table 6-1.

Note: ada computes `hash = (2 * len + scheme[0]) & 7` to index `is_special_list`, doing at most one string comparison. kuri is not required to reproduce that hash; only the O(1) closed-set semantics are normative.

### 6.2 Scheme syntax and validation

The scheme grammar is the same in both profiles. The grammar (ABNF) is given in §4 and restated here for the validation rules.

```
scheme = ALPHA *( ALPHA / DIGIT / "+" / "-" / "." )
```

where `ALPHA` is `%x41-5A / %x61-7A` (ASCII letters) and `DIGIT` is `%x30-39`.

**Table 6-2 — Scheme code-point rules**

| Position | Permitted code points |
|---|---|
| First code point | ASCII alpha: `A`–`Z`, `a`–`z` |
| Each subsequent code point | ASCII alpha, ASCII digit `0`–`9`, `+`, `-`, `.` |

**[SCH-7]** The first code point of a scheme MUST be an ASCII alpha. A scheme beginning with a digit, `+`, `-`, `.`, or any non-ASCII or non-alpha code point is not a valid scheme.

**[SCH-8]** Each code point of a scheme after the first MUST be an ASCII alpha, an ASCII digit, `+`, `-`, or `.`. Any other code point (including percent signs, whitespace, control characters, and non-ASCII code points) terminates or invalidates the scheme as defined below.

**[SCH-9]** Scheme code points are never percent-decoded and never percent-encoded. A `%` appearing where a scheme is expected means the scheme production does not match; the input is then reparsed as having no scheme (in the `Uri` profile, as a relative reference per [SCH-12]) or rejected (in the `Url` profile per [SCH-13]).

**[SCH-10]** A scheme MUST be non-empty. An input whose first significant code point is `:` (e.g. `://example`) has no scheme and MUST NOT be treated as having an empty scheme; it is handled as a no-scheme input ([SCH-12] / [SCH-13]).

**[SCH-11]** During parsing the scheme is delimited by the first `:` that follows a valid scheme prefix. If, while scanning candidate scheme code points from the start of input, a code point not permitted by Table 6-2 is encountered before any `:`, the input has no scheme. In the `Url` profile, a candidate scheme is only accepted if the code point immediately following the scanned run is `:` (the scheme-state requirement); otherwise the input has no scheme.

Note: this mirrors the WHATWG scheme-start and scheme states; the authority-introduction fault line is parsed in §8, not here.

#### 6.2.1 Scheme presence per profile

**[SCH-12]** In the `Uri` profile, a URI MAY be a relative reference with no scheme (RFC 3986 relative-ref). When the input has no scheme per [SCH-11], the parser MUST succeed (subject to the rest of the grammar) and produce a `Uri` whose scheme component is absent. A consumer distinguishes such a value as a relative reference; reference resolution against a base URL is defined in §9.

**[SCH-13]** In the `Url` profile, a `Url` MUST have a scheme. When parsing a `Url` with no base URL and the input has no scheme per [SCH-11], the parser MUST fail with a `ParseResult.Err` carrying a `UriParseError` indicating a missing scheme. When a base URL is supplied, scheme-relative resolution proceeds per §9 and the resulting `Url` inherits the base URL's scheme; the produced `Url` still always has a scheme.

**[SCH-14]** A scheme that satisfies Table 6-2 but is not in Table 6-1 is a valid non-special scheme in both profiles. In the `Uri` profile it is the normal case (the profile is scheme-agnostic). In the `Url` profile it produces a valid non-special `Url` governed by [SCH-22]–[SCH-26].

### 6.3 Scheme normalization

The scheme is case-insensitive in both profiles; its canonical form is lowercase.

**[SCH-15]** An implementation MUST normalize the scheme to lowercase by mapping each ASCII upper-case code point `A`–`Z` (`%x41-5A`) to its lower-case counterpart `a`–`z` (`%x61-7A`). This mapping applies in **both** the `Url` and `Uri` profiles and is performed unconditionally — it is not gated behind opt-in normalization.

**[SCH-16]** Scheme normalization MUST be ASCII-only and MUST NOT apply any locale-sensitive case mapping, Unicode case folding, or non-ASCII transformation. Because a valid scheme contains only ASCII code points ([SCH-7], [SCH-8]), no non-ASCII case mapping can arise; an implementation MUST NOT, for example, apply Turkish dotless-`i` mapping.

**[SCH-17]** The lowercased scheme is the value stored in the data model and the value emitted on serialization (§11). The scheme determines special-ness ([SCH-3]) only after this normalization. An implementation MUST NOT retain the original scheme case for round-tripping; scheme case is not a preserved distinction in either profile.

Note: scheme lowercasing is the one normalization that is always applied even in the otherwise PRESERVE-by-default `Uri` profile, because the scheme is case-insensitive per RFC 3986 §6.2.2.1 and lowercasing is loss-free.

### 6.4 Scheme, `effectivePort`, and default-port elision

The scheme supplies the default port used to compute `effectivePort` and to decide default-port elision on serialization. The authoritative definitions of port parsing, `effectivePort`, and elision are in §8 (port state) and §11 (serialization); this subsection states only the scheme's contribution.

**[SCH-18]** `effectivePort` MUST be computed as: the explicitly parsed port if one is present; otherwise the default port of the scheme from Table 6-1 if the scheme is special and has a default port; otherwise absent. Concretely, for a special scheme with no explicit port, `effectivePort` equals the Table 6-1 default; for `file` or any non-special scheme with no explicit port, `effectivePort` is absent.

**[SCH-19]** In the `Url` profile, when an explicitly present port equals the default port of the URL's special scheme (Table 6-1), the port MUST be elided from the stored model and from serialization (default-port elision; see §11). For example, an explicit `:80` under `http` and an explicit `:443` under `https` are removed. A port equal to a default but under a different or non-special scheme is not elided (e.g. `:80` under `https` or under a non-special scheme is retained).

**[SCH-20]** In the `Uri` profile, default-port elision MUST NOT occur by default; an explicitly present port is preserved verbatim for round-tripping, including a port that happens to equal a registry default. Default-port elision in the `Uri` profile is available only as an opt-in normalization operation (§11), and even then applies only when the scheme is special with a matching default.

**[SCH-21]** A scheme whose value is absent ([SCH-12], `Uri` relative reference) has no default port and contributes nothing to `effectivePort`.

### 6.5 Non-special scheme semantics

A scheme that is not in Table 6-1 ([SCH-14]) carries no special parsing or serialization machinery. The following requirements define the consequences; the cited sections are authoritative for the mechanisms.

**[SCH-22]** For a non-special scheme, the host (if an authority is present) MUST be parsed by the **opaque-host** rule rather than the special-host pipeline: no IDNA/UTS-46 mapping (§7, profile of [SCH-26]), no IPv4 shorthand/hex/octal interpretation, and no host lowercasing. The resulting host is a `Host.Opaque` (or `Host.Empty` for an empty authority, or `Host.Ipv6`/`Host.IpFuture` for a bracketed IP literal, which are scheme-independent). Forbidden-host code-point rules for opaque hosts are defined in §7.

**[SCH-23]** For a non-special scheme, a URI/URL MAY have an **opaque path** (a path that is not hierarchical, i.e. one not beginning with `/` and not introduced by an authority). Opaque-path eligibility is restricted to non-special schemes: in the `Url` profile a special URL MUST NOT have an opaque path (its path is always a list of segments and an empty special path serializes as `/`, per §9/§11). Opaque-path parsing and serialization are defined in §9.

**[SCH-24]** For a non-special scheme, the authority-introduction and backslash rules of special schemes MUST NOT apply: `\` is not treated as `/`, and an authority is introduced only by an explicit `//` (per §8). Backslash handling and slash-run collapsing are special-scheme behaviours; for non-special schemes a `\` that is not permitted in a component is percent-encoded per §5, not rewritten.

**[SCH-25]** For a non-special scheme, default-port elision and empty-path-to-`/` normalization MUST NOT be applied, because these are keyed on a special scheme with a default port (Table 6-1). A non-special scheme has no default port; its `effectivePort` is the explicit port if present, otherwise absent ([SCH-18]).

**[SCH-26]** The `Uri` profile is scheme-agnostic: it applies no special-scheme behaviour to any scheme, including the six schemes of Table 6-1. A `Uri` whose scheme is `http` is still parsed with the generic RFC 3986/3987 pipeline (reg-name host, no IDNA unless opted in, no backslash rewriting, no default-port elision by default). The special-scheme registry governs behaviour only in the `Url` profile; in the `Uri` profile Table 6-1 is consulted solely to populate the default port used by an opt-in default-port normalization ([SCH-20]) and by `effectivePort` ([SCH-18]). The distinction "is special" is therefore meaningful for serialization/normalization decisions in the `Url` profile and is inert (beyond default-port lookup) in the `Uri` profile.

Note: the special-scheme behaviours (backslash, slash collapsing, IDNA host, empty-host rules, eager normalization) are all profile-gated to `Url`; the `Uri` profile preserves RFC 3986/3987 semantics regardless of scheme.

## 7. Host Parsing

This section specifies the normative host parser. The parser is profile-aware: it consumes the host substring of an authority and produces a `Host` value (one of the sealed variants `RegName`, `Ipv4`, `Ipv6`, `IpFuture`, `Empty`, `Opaque`) or fails with a `UriParseError`. The host module is shared between profiles and configured by `ParseProfile`; where the two profiles diverge, the divergence is stated explicitly.

The input to the host parser is the host component as isolated by the authority splitter (§8): the substring after any userinfo `@` delimiter and before the port `:` delimiter or the path/query/fragment terminator. Bracketed IPv6/IP-future literals retain their surrounding `[` and `]` in this input. In the `Url` profile, ASCII tab (U+0009), line feed (U+000A), and carriage return (U+000D) have already been removed from the entire input before host parsing per §8; in the `Uri` profile they are not removed and are rejected as forbidden host code points (§7.6).

> Note: derived from ada `url.cpp` `parse_host`/`parse_ipv4`/`parse_ipv6`/`parse_opaque_host`, `helpers.cpp` `get_host_delimiter_location`, `checkers.cpp` `is_ipv4`/`verify_dns_length`, `serializers.cpp`, `unicode.cpp` host tables; cf. WHATWG URL §3.3–§3.5 / RFC 3986 §3.2.2 / RFC 5952 / RFC 5891 / UTS-46.

### 7.1 Host end-delimiter detection and dispatch

**[HOST-1]** The host substring SHALL be delimited from the port and the rest of the URI by scanning for the first host-terminating delimiter that occurs **outside** a bracketed range. The delimiter set is `:` (introduces a port), end-of-input, `/`, `?`, `#`, and — in the `Url` profile only — `\`. A `:` that appears between a `[` and its matching `]` is part of an IPv6/IP-future literal and MUST NOT be treated as the port delimiter.

**[HOST-2]** When scanning for the delimiter, an unescaped `[` opens a bracketed range that extends to the next `]`. If a `[` has no matching `]` before end-of-input, the bracketed range extends to end-of-input. Only delimiter characters found outside every bracketed range terminate the host.

**[HOST-3]** Host dispatch SHALL select the parsing branch by the following ordered tests, evaluated top to bottom; the first matching branch is taken:

| # | Condition | Branch | Profiles |
|---|-----------|--------|----------|
| 1 | Host begins with `[` | IP-literal: IPv6 (§7.2), or IP-future (§7.8) in `Uri` only | both |
| 2 | Host is empty | empty-host rules (§7.7) | both |
| 3 | `Url` profile, scheme is special | special host pipeline: IPv4 (§7.3) / domain via IDNA (§7.4) | `Url` |
| 4 | `Url` profile, scheme is not special | opaque host (§7.5) | `Url` |
| 5 | `Uri` profile | RFC 3986 host: `IPv4address` (§7.3) else reg-name (§7.5/§7.4) | `Uri` |

**[HOST-4]** In the `Url` profile, a host beginning with `[` MUST be parsed as an IPv6 literal regardless of scheme specialness; if it does not end with `]`, this is a validation error and host parsing MUST fail. In the `Url` profile there is no IP-future branch: a bracketed literal that is not a valid IPv6 address (including any `[vX.…]` form) MUST fail.

**[HOST-5]** In the `Uri` profile, a host beginning with `[` is an `IP-literal` and MUST end with `]`; the bracket contents MUST match either the RFC 3986 `IPv6address` production (§7.2) or the `IPvFuture` production (§7.8). Otherwise host parsing MUST fail.

### 7.2 IPv6 literals

The IPv6 parser operates on the bracket contents (the `[` and `]` removed). It builds an array of eight 16-bit pieces, all initially zero.

**[HOST-6]** The IPv6 parser SHALL maintain a current piece index (initially 0) and a single optional compression marker (initially unset). It MUST consume at most eight 16-bit pieces. If, while a further piece is expected, the piece index is already 8, this is a validation error and parsing MUST fail (too many pieces).

**[HOST-7]** If the contents are empty, parsing MUST fail.

**[HOST-8]** If the contents begin with `:`, the next code point MUST also be `:`; otherwise parsing MUST fail. The leading `::` sets the compression marker at piece index 0 and advances past both colons. A leading single `:` (not part of `::`) MUST fail.

**[HOST-9]** A `:` encountered where a piece is expected denotes zero-run compression. If the compression marker is already set, this is a validation error and parsing MUST fail (multiple `::`). Otherwise the compression marker is set at the current piece index and scanning continues.

**[HOST-10]** A piece is a run of 1 to 4 ASCII hex digits, interpreted as a hexadecimal number (case-insensitive on input). After a piece is read, the following code point MUST be either `:` (with at least one further code point after it — a trailing single `:` MUST fail) or end-of-input; any other code point is a validation error and MUST fail (bad piece / unexpected character). A run longer than 4 hex digits is not a valid piece.

**[HOST-11]** Leading zeros within a hex piece are permitted on input (e.g. `00a1`) but MUST NOT be preserved; serialization (§7.2.1) suppresses them.

**[HOST-12]** **Embedded IPv4 trailer.** If a `.` is encountered after one or more hex digits of the current piece, the parser MUST switch to embedded-IPv4 mode: the current piece must contain at least one digit (otherwise fail), the piece index MUST be ≤ 6 (otherwise fail), and the parser rewinds to the start of that piece and reads exactly four dotted-decimal octets, two octets packed into each of two consecutive 16-bit pieces.

**[HOST-13]** Each embedded-IPv4 octet MUST satisfy all of the following, else parsing MUST fail:
- it is a non-empty run of ASCII decimal digits (reject empty octet, reject a non-digit such as a hex letter);
- its value does not exceed 255 (reject 256 and above);
- it has no forbidden leading zero: an octet whose first digit is `0` followed by any further digit MUST fail (this rejects octal-looking and zero-padded octets such as `01` or `00`);
- octets are separated by exactly one `.` (reject `..` double-dot) and exactly four octets MUST be seen (reject fewer or more). A `.` may not appear after the fourth octet.

**[HOST-14]** After successful piece/embedded-IPv4 scanning: if the compression marker is set, the pieces written so far MUST be relocated to the high end of the address and the gap filled with zero pieces (the standard compression expansion). If the compression marker is unset, the piece index MUST equal 8 exactly; any other value is a validation error and MUST fail (too few pieces).

#### 7.2.1 RFC 5952 canonical serialization

**[HOST-15]** An `Ipv6` host MUST be serialized to its RFC 5952 canonical textual form by the following rules:
- each 16-bit piece is rendered in **lowercase hexadecimal** with **no leading zeros** (a zero piece renders as `0`);
- the **single longest run of two or more consecutive zero pieces** is replaced by `::`; on a tie in run length, the **leftmost** run is chosen;
- a run of exactly **one** zero piece MUST NOT be compressed;
- if no run of length ≥ 2 exists, no `::` compression is applied;
- pieces (and the compressed gap) are joined by single `:`;
- when serialized as a host within an authority, the result is wrapped in `[` and `]`.

**[HOST-16]** When the compression replaces a run that begins at piece index 0 or ends at piece index 7, the serialization yields a leading or trailing `::` respectively (e.g. `::1`, `2001:db8::`). Embedded-IPv4 syntax MUST NOT be reintroduced on output; the canonical form is always eight hexadecimal pieces (compressed), so e.g. the input `[::ffff:192.168.0.1]` serializes as `[::ffff:c0a8:1]`.

#### 7.2.2 Zone identifiers (RFC 6874)

**[HOST-17]** The RFC 6874 zone-id opt-in (`ParseOptions.allowIpv6ZoneId`, default off) is scoped to the **`Uri` profile**. By default `%` is a forbidden host code point (§7.6), so absent the opt-in an IPv6 literal containing `%` (a zone identifier) MUST fail to parse, rejected with `InvalidHost(ZoneIdRejected)`. The **`Url` profile** does not accept `ParseOptions` at all and MUST reject a zone identifier for **every** input regardless of any option: a `%` in a `Url` IPv6 literal always yields `InvalidHost(ZoneIdRejected)`. This is because the WHATWG URL parser defines no zone-id syntax — browsers reject `%` in IPv6 literals — so keeping the opt-in out of the `Url` profile preserves its unconditionally-WHATWG guarantee and keeps the two-profile boundary clean.

**[HOST-18]** Implementations MUST provide an explicit opt-in flag enabling RFC 6874 zone identifiers **for the `Uri` profile** (`ParseOptions.allowIpv6ZoneId`, default off; the `Url` profile takes no options and never enables this, per [HOST-17]). When enabled, an IPv6 literal of the form `[` IPv6address `%25` ZoneID `]` SHALL be accepted, where `ZoneID` is `1*( unreserved / pct-encoded )`. The address part before `%25` is parsed per §7.2; the `ZoneID` is stored **raw** — the exact text between `%25` and the closing bracket, in its `unreserved / pct-encoded` form and **not** percent-decoded — as the `zoneId` of the `Ipv6` host (consistent with [MODEL-19]). When a `zoneId` is present, serialization MUST re-emit it verbatim: `[` + canonical address + `%25` + the stored raw zone + `]`. A malformed zone under the opt-in (a `%` that does not begin the `%25` introducer, an empty `ZoneID`, or an illegal `ZoneID` code point) is `InvalidHost(Ipv6Malformed)`, distinct from the opt-off `InvalidHost(ZoneIdRejected)`. Because the zone text is stored raw rather than the options that unlocked it, a zoned value round-trips through serialize-then-parse only when re-parsed under the same opt-in. When the flag is disabled, [HOST-17] applies.

### 7.3 IPv4 addresses

The `Url`-profile parser of §7.3.1 (hex/octal/shorthand and width-aware overflow forms) is a sanctioned `Url`-profile deviation from RFC 3986 — see Appendix B [DEV-5].

#### 7.3.1 The `Url` profile IPv4 number parser

In the `Url` profile (special schemes), after the domain has been lowercased and forbidden-domain-scanned (§7.4), an **ends-in-a-number** test decides whether the host is an IPv4 address or a registered name.

**[HOST-19]** The ends-in-a-number test SHALL operate on the host string with a single optional trailing `.` removed (an empty result then fails the test). The host is treated as a candidate IPv4 address if and only if its **last label** (the substring after the final `.`, or the whole string if there is no `.`) is either:
- a non-empty run of ASCII decimal digits; or
- the two characters `0x` / `0X`, optionally followed by one or more ASCII **lowercase** hex digits (the host having been lowercased first).

A last label that is neither (e.g. it contains a non-hex letter) means the host is a registered name, not an IPv4 address.

**[HOST-20]** When the ends-in-a-number test is positive, the host MUST be parsed as an IPv4 address; if that parse fails, host parsing as a whole MUST fail. A positive ends-in-a-number host MUST NOT fall back to being treated as a registered name.

**[HOST-21]** The IPv4 number parser SHALL:
1. remove a single trailing `.` if present;
2. split the input on `.` into 1 to 4 parts; more than 4 parts MUST fail; an empty part MUST fail;
3. determine each part's radix independently: a `0x`/`0X` prefix ⇒ hexadecimal (the special cases `0x` and `0x` immediately followed by `.` or end ⇒ value 0); otherwise a leading `0` with at least one further digit ⇒ octal; otherwise decimal;
4. parse each part as an unsigned integer in its radix; a parse error (including a digit outside the radix) MUST fail.

**[HOST-22]** **Width-aware overflow.** Let `n` be the number of parts (1 ≤ `n` ≤ 4). Each of the first `n−1` parts MUST be ≤ 255 (otherwise fail) and contributes one octet. The final part MUST be strictly less than 2^(32 − 8·(n−1)); otherwise host parsing MUST fail. The final part supplies the remaining low-order octets. The resulting 32-bit value is the address.

> Note: thus `192.168.0.1` (4 parts) requires the last part ≤ 255; `192.168.1` (3 parts) packs the last part into 16 bits (< 65536); `0xC0A80001` (1 part) packs into 32 bits; `192.0xA80001` packs the last into 24 bits. Mixed radices per part are permitted (cf. ada `parse_ipv4`).

**[HOST-23]** Non-decimal or zero-padded part representations (hex, octal, multi-octet packing) are accepted in the `Url` profile but are non-canonical: they are validation errors that MUST NOT be fatal, and the address MUST be re-serialized canonically (§7.3.3).

#### 7.3.2 The `Uri` profile IPv4 grammar

**[HOST-24]** In the `Uri` profile there is no IPv4 shorthand, hex, or octal form. A host is classified as `Ipv4` if and only if it matches the RFC 3986 `IPv4address` ABNF: exactly four `dec-octet` parts separated by `.`, where each `dec-octet` is a decimal number in the range 0–255 with no leading zeros (`0`, `1`–`9`, `10`–`99`, `100`–`199`, `200`–`249`, `250`–`255`). A host that does not match this production is not an `Ipv4`; it is a reg-name (§7.5) unless it is an IP-literal (§7.2/§7.8).

#### 7.3.3 Canonical IPv4 serialization

**[HOST-25]** An `Ipv4` host MUST be serialized as dotted-decimal: the four octets of the 32-bit value, most-significant first, each rendered in base-10 with no leading zeros, joined by `.` (e.g. `192.168.0.1`). In the `Url` profile, when the input was already four pure-decimal octets it MAY be preserved verbatim (after stripping a trailing `.`); otherwise the address MUST be re-serialized from the 32-bit value.

### 7.4 Registered names and domains via IDNA (UTS-46)

In the `Url` profile, a special-scheme host that is not an IP literal and does not end in a number is a **domain**, processed through the bundled UTS-46 pipeline. (The IDNA/UTS-46 host mapping is a sanctioned `Url`-profile deviation from RFC 3986 — see Appendix B [DEV-6].)

**[HOST-26]** The domain-to-ASCII pipeline SHALL, in order:
1. UTF-8 percent-decode the input (decode without BOM);
2. **map** each code point using the bundled UTS-46 mapping table — valid code points are kept, mapped code points are replaced by their mapping, **ignored** code points (e.g. U+00AD SOFT HYPHEN) are dropped, and **disallowed** code points cause failure (see [HOST-30]);
3. apply **NFC** normalization;
4. split on `.` into labels and, for each label, apply Punycode: a label that is non-ASCII after mapping MUST be encoded with the `xn--` ACE prefix; a label already bearing an `xn--` prefix MUST be Punycode-decoded and validated;
5. validate each label per RFC 5891 (§7.4.1);
6. reassemble labels with `.` to form the ASCII domain.

**[HOST-27]** **Fast path.** An input that, after ASCII-lowercasing, contains no forbidden-domain code point (§7.6) **and** contains no `xn--` substring MAY bypass steps 1–5 and be used directly as the ASCII domain. The presence of an `xn--` substring anywhere in the host — even in otherwise pure-ASCII input — MUST force the full UTS-46 pipeline ([HOST-26]), because the Punycode payload must be decoded and validated.

**[HOST-28]** UTS-46 processing options for the `Url` profile (the non-strict, `beStrict = false` configuration) SHALL be exactly: `CheckHyphens = false`, `CheckBidi = true`, `CheckJoiners = true`, `UseSTD3ASCIIRules = false`, `Transitional_Processing = false`, `VerifyDnsLength = false` (DNS length handled separately by [HOST-31]), and `IgnoreInvalidPunycode = false`. `UseSTD3ASCIIRules` is a fixed `false`, **not** advisory: the WHATWG domain parser mandates `false` here and instead enforces the STD3-style restrictions through the mandatory forbidden-domain re-scan of [HOST-30] (the forbidden-domain set being a subset of the code points that `UseSTD3ASCIIRules = true` would disallow). ToUnicode (the inverse display transform) MUST be provided and MUST be run with the same parameters: `CheckHyphens = false`, `CheckBidi = true`, `CheckJoiners = true`, `UseSTD3ASCIIRules = false`, `Transitional_Processing = false`, and `IgnoreInvalidPunycode = false`.

**[HOST-29]** After the ASCII domain is produced, the parser MUST re-run the ends-in-a-number test ([HOST-19]); if positive, the ASCII domain MUST be parsed as an IPv4 address ([HOST-21]). The resulting `RegName` (when not an IPv4) holds the lowercase ASCII domain.

**[HOST-48]** In the `Url` profile (non-strict, `beStrict = false`), when the percent-decoded domain is already an ASCII string, the UTS-46 ToASCII step is run **only to surface validation errors**: a ToASCII validity failure (including a label whose `xn--` Punycode decodes successfully but then fails UTS-46 validity, e.g. `xn--8i7caa`) is recorded as a *domain-to-ASCII* validation error and is **non-fatal**, and the resulting ASCII domain is the input *lowercased* (not the ToASCII output). The mandatory forbidden-domain re-scan ([HOST-30]) and, in strict mode, the DNS-length checks ([HOST-31]) still apply to that lowercased result and may still fail. This web-compatibility rule means [HOST-26] step 4's `xn--` decode/validate and [HOST-27]'s forced full pipeline MUST NOT, for ASCII input, turn a pure UTS-46 validity failure into a fatal host-parse failure. For non-ASCII input, a ToASCII failure value or an empty ToASCII result remains fatal per [HOST-26].

#### 7.4.1 Validity and length

**[HOST-30]** **Mandatory forbidden-domain re-scan.** After ToASCII completes, the parser MUST scan the resulting ASCII domain for forbidden-domain code points (§7.6); if any is present, host parsing MUST fail. An empty ToASCII result MUST also fail. This re-scan is required even on the fast path having matched it before mapping.

**[HOST-31]** **DNS length limits.** Each label MUST be 1 to 63 octets and the whole domain MUST be at most 253 octets (or 254 when a single trailing-dot empty label is included). By default these limits are **advisory**: a violation is a validation error that does not fail parsing. In **strict mode** these limits MUST be enforced as fatal: a zero-length label (other than a single trailing empty label), a label exceeding 63 octets, or a domain exceeding the byte bound MUST fail.

#### 7.4.2 `Uri` profile reg-names

**[HOST-32]** In the `Uri` profile, registered names are **not** processed through IDNA and are **not** lowercased or otherwise canonicalized by default. A `Uri` reg-name is handled by the preserve pipeline of §7.5. An `xn--` label in a `Uri` reg-name is preserved verbatim and is not decoded.

### 7.5 Opaque host / reg-name preserve pipeline

This shared pipeline serves (a) `Url` non-special-scheme hosts and (b) `Uri` registered names. It performs no IDNA, no IPv4 interpretation, and no lowercasing.

**[HOST-33]** The pipeline SHALL first scan the input for forbidden **host** code points (§7.6, the less-strict table); if any is present, host parsing MUST fail. Note that `%` is permitted here (it is not in the forbidden-host table), so percent-encoded octets pass through. In the `Uri` profile the pipeline MUST additionally enforce the RFC 3986 §3.2.2 `reg-name` production `*( unreserved / pct-encoded / sub-delims )`: any code point that is neither `unreserved` nor a `sub-delims` character nor part of a `pct-encoded` triplet MUST cause host parsing to fail. This is stricter than the forbidden-host table, which permits `"`, `` ` ``, `{`, and `}` — none of which are valid in an RFC 3986 reg-name.

**[HOST-34]** The pipeline SHALL then UTF-8 percent-encode the input using the **C0 control percent-encode set** (C0 controls and every code point greater than U+007E). No other code points are encoded. The encoded result is stored as-is (case preserved).

**[HOST-47]** When parsing an opaque host ([HOST-33]/[HOST-34]) in the `Url` profile, after confirming the input contains no forbidden host code point the parser MUST additionally record (non-fatally) the following WHATWG validation errors: (a) an *invalid-URL-unit* validation error if the input contains a code point that is neither a URL code point (§4.2.5) nor U+0025 (`%`); and (b) an *invalid-URL-unit* validation error if the input contains a U+0025 (`%`) that is not immediately followed by two ASCII hex digits. Neither condition is fatal; both are surfaced as `ValidationError` records only, and parsing proceeds to the C0-control percent-encode step ([HOST-34]).

**[HOST-35]** Profile-dependent classification of the result:
- In the `Url` profile (non-special scheme), the result is an `Opaque` host.
- In the `Uri` profile, the result is a `RegName` host carrying the preserved (percent-normalized) name.

### 7.6 Forbidden code-point tables (normative)

Two tables govern host validation. The **forbidden-host** table applies to opaque hosts (§7.5); the **forbidden-domain** table is strictly larger and applies to the IDNA domain pipeline (§7.4). These tables are the host-validation counterparts of the code-point classes named in §4.2.3–§4.2.4.

**[HOST-36]** A code point is a **forbidden host code point** if and only if it is one of:

| Code point | Name |
|-----------|------|
| U+0000 | NULL |
| U+0009 | TAB |
| U+000A | LF |
| U+000D | CR |
| U+0020 | SPACE |
| U+0023 | `#` |
| U+002F | `/` |
| U+003A | `:` |
| U+003C | `<` |
| U+003E | `>` |
| U+003F | `?` |
| U+0040 | `@` |
| U+005B | `[` |
| U+005C | `\` |
| U+005D | `]` |
| U+005E | `^` |
| U+007C | `\|` |

**[HOST-37]** A code point is a **forbidden domain code point** if and only if it is a forbidden host code point ([HOST-36]), **or** U+0025 (`%`), **or** a C0 control (any code point in the range U+0000 through U+001F inclusive), **or** U+007F DELETE. (Relative to the forbidden-host set this additionally forbids `%`, every C0 control, and U+007F DELETE; U+0020 SPACE is already a forbidden host code point.) Per the WHATWG definition the forbidden-domain set does **not** additionally forbid non-ASCII code points U+0080 and above. A byte-table implementation MAY treat every octet greater than or equal to `0x7F` as forbidden when scanning a post-IDNA ASCII domain, because a conformant ToASCII result is pure ASCII and any high octet is necessarily a defect (see the note at [GRAM-12]); that shortcut is an equivalent realization for post-IDNA strings only and MUST NOT be read into the definition of the class.

### 7.7 Empty-host rules

**[HOST-38]** In the `Url` profile, for a **special** scheme other than `file`, an empty host is a host-missing validation error and host parsing MUST fail. This also applies when credentials or a port are present with an empty host.

**[HOST-39]** In the `Url` profile, for the `file` scheme, an empty host is permitted and yields `Empty`. Additionally, a `file` host that parses to `localhost` MUST be replaced by the empty host (`Empty`).

**[HOST-40]** In the `Url` profile, for a **non-special** scheme, an empty host is permitted and yields `Empty` (equivalently an `Opaque` host with empty encoded value).

**[HOST-41]** In the `Uri` profile, an empty authority and an empty host are permitted and yield `Empty` (e.g. `file:///path` has authority present with empty host; `foo://` has an empty authority). The `Uri` profile applies no `localhost` rewriting.

### 7.8 IP-future (`Uri` profile only)

**[HOST-42]** In the `Uri` profile, an `IP-literal` whose bracket contents begin with `v` (case-insensitive) MUST be parsed as `IPvFuture`, matching the RFC 3986 ABNF `"v" 1*HEXDIG "." 1*( unreserved / sub-delims / ":" )`. A conforming match yields an `IpFuture` host whose `raw` field holds the bracket contents (the version, the `.`, and the payload). Contents that begin with `v` but do not match this production MUST fail.

**[HOST-43]** In the `Url` profile, `IPvFuture` is not recognized: a bracketed literal that is not a valid IPv6 address MUST fail ([HOST-4]). An `IpFuture` host therefore never arises in the `Url` profile.

**[HOST-44]** An `IpFuture` host MUST be serialized within an authority by wrapping its `raw` contents in `[` and `]`, preserving the contents verbatim.

### 7.9 Mapping of outcomes to `Host` variants

**[HOST-45]** Every successful host parse MUST map to exactly one `Host` variant per the following table; any input not covered MUST fail with a `UriParseError`:

| Outcome | Profile / condition | `Host` variant |
|---------|---------------------|----------------|
| IPv6 literal parsed | both (`[` … `]`, valid IPv6) | `Ipv6` (with optional `zoneId`) |
| IP-future literal parsed | `Uri` only (`[v…]`) | `IpFuture` |
| IPv4 address parsed | `Url` special (ends-in-number) or `Uri` `IPv4address` | `Ipv4` |
| Domain via IDNA | `Url` special, not IP | `RegName` (lowercase ASCII) |
| Opaque host | `Url` non-special | `Opaque` |
| Registered name preserved | `Uri`, not IP-literal/IPv4address | `RegName` (preserved) |
| Empty host | per §7.7 | `Empty` |

**[HOST-46]** When serializing a `Host` back into an authority string, brackets MUST be added for `Ipv6` and `IpFuture` and MUST NOT be added for `Ipv4`, `RegName`, `Opaque`, or `Empty`. `Empty` serializes to the empty string.

## 8. The Parsing Algorithm

This section specifies the normative parsing algorithm shared by both public types. The algorithm is a single-pass state machine driven by one monotonically non-decreasing input pointer. It is the WHATWG basic URL parser, generalized so that profile-gated branches are switched on by the active `ParseProfile`. An implementer who reads only this section, §5 (percent-encoding), §6 (schemes), §7 (host parsing), and §9 (paths and reference resolution) MUST be able to produce a conformant parser.

The algorithm consumes a sequence of octets that has already been decoded as UTF-8 where applicable (the input is a Unicode string for `Url`; for `Uri` it is the caller-supplied scalar sequence). Its output is a `ParseResult` (`Ok` carrying the populated value type, or `Err` carrying a `UriParseError`). Non-fatal anomalies are reported as `ValidationError` records and do not, by themselves, change the `Ok`/`Err` outcome unless a requirement below makes them fatal.

> Note: the state set and per-state behaviour are derived from ada `src/parser.cpp` and `include/ada/state.h`; cf. WHATWG URL §4.4 ("URL parsing") and RFC 3986 §3. Each state below carries a non-normative cross-reference to its WHATWG section.

### 8.1 Pre-processing

Pre-processing runs once, before the state loop, on the whole input. It is profile-gated. (The `Url`-profile tab/LF/CR removal and leading/trailing C0-control-or-space trimming are a sanctioned `Url`-profile deviation from RFC 3986 — see Appendix B [DEV-8].)

**Input length bound.** **[PARSE-1]** The parser MUST enforce a fixed maximum input length (the configured limit, see §12). If the input length in octets exceeds the limit, the parser MUST return `Err` without entering the state loop. **[PARSE-2]** After the state loop completes, if normalization (percent-encoding, IDNA, default-port elision, dot-segment removal) has expanded the serialized result beyond the same limit, the parser MUST return `Err`.

**Tab and newline removal (`Url` profile only).** **[PARSE-3]** In the `Url` profile, after the leading/trailing C0-control-or-space trim ([PARSE-5]), the parser MUST remove every occurrence of U+0009 (TAB), U+000A (LF), and U+000D (CR) from anywhere in the input, and MUST record one `ValidationError` of kind *tab-or-newline* if at least one such code point was present. The removal applies across the entire input including within components. **[PARSE-4]** In the `Uri` profile, the parser MUST NOT remove tab, LF, or CR; an embedded U+0000–U+001F C0 control or other disallowed code point appearing where the grammar of §4 forbids it is a fatal error and MUST cause the parser to return `Err`.

> Note: the `Url` strip-anywhere behaviour means `ht\ntp://a\tb/c` parses as `http://ab/c`. The `Uri` profile is PRESERVE-by-default and treats control octets as grammar violations rather than silently editing the input.

**Leading and trailing trim (`Url` profile only).** **[PARSE-5]** In the `Url` profile, before tab/newline removal ([PARSE-3]), the parser MUST remove all leading and all trailing code points that are a *C0 control or space* (any code point in U+0000–U+0020 inclusive), and MUST record one `ValidationError` of kind *leading-or-trailing-c0-or-space* if at least one such code point was removed. **[PARSE-6]** In the `Uri` profile, the parser MUST NOT trim leading or trailing control-or-space code points; if present where the grammar forbids them, the parser MUST return `Err`.

**Fragment pruning (both profiles).** **[PARSE-7]** After the trim steps, the parser MUST locate the FIRST U+0023 (`#`) in the remaining input. If present, everything from that `#` to the end of input (excluding the `#` itself) is the *fragment component*; the parser MUST split it off and process the remainder of the input (everything before the first `#`) through the state loop, re-attaching the fragment last (see §8.3, FRAGMENT). If no `#` is present, the value has no fragment. **[PARSE-8]** The presence of a `#` makes the fragment component defined even when it is the empty string (`http://a/#` has an empty, present fragment; `http://a/` has no fragment); the two MUST be distinguishable in the data model (§3).

Pruning the fragment up front is observationally equivalent to a per-code-point FRAGMENT state, because no state reachable after a `#` can invalidate the URL: the fragment is always structurally valid and is only percent-encoded (`Url`) or preserved (`Uri`).

### 8.2 Pointer and EOF model

The state loop operates on the pre-processed, fragment-stripped input, denoted `input`, of length `len` octets.

**[PARSE-9]** The parser MUST maintain a single index `pos`, initialized to 0, that is non-decreasing across the lifetime of the loop except for the explicit one-time restart in the SCHEME state (§8.3). The parser MUST NOT introduce any second backing cursor.

**[PARSE-10]** The loop condition MUST be `pos <= len`. The value `pos == len` is a real, in-band sentinel denoting the EOF code point; states MUST be able to execute with `pos == len` and MUST treat "the code point at `pos`" as the EOF code point in that case. Reading `input[pos]` when `pos == len` is forbidden; a state MUST test for EOF before dereferencing.

**[PARSE-11]** The WHATWG "decrease pointer by one" instruction MUST be emulated by re-entering the target state WITHOUT advancing `pos` (i.e., setting the next state and breaking out of the current `switch`/`when` arm without incrementing `pos`). The parser MUST NOT physically decrement `pos`. A state that consumes the current code point advances `pos` by exactly 1 (or by the count it explicitly consumed) before the next iteration; a state that "backs up" leaves `pos` unchanged so the next state sees the same code point.

> Note: this is the ada discipline — `while (input_position <= input_size)`, EOF == size, never decrement. It eliminates the off-by-one class of bugs that afflict cursor-pair parsers.

### 8.3 States

Each state is specified as: **entry**, **profiles** it applies to, **action** per current code point `c` (where `c` may be EOF), and **transitions**. Unless a state says otherwise, after its action the parser advances `pos` by 1. A transition that says "reconsume in STATE X" means: set state to X and do not advance (per [PARSE-11]).

The special-scheme set (http, https, ws, wss, ftp, file) and default ports are defined in §6. **[PARSE-12]** In the `Uri` profile the predicate *is special* MUST always evaluate to false: no scheme is special, no default-port elision occurs, the FILE / FILE_SLASH / FILE_HOST and SPECIAL_* states are unreachable, and U+005C (`\`) is never a delimiter (it is an ordinary code point, percent-encoded where the component's encode set requires it). All "special" behaviour below is therefore `Url`-profile only, as marked.

#### 8.3.1 SCHEME START — *#scheme-start-state*

Entry: initial state. Profiles: both.

- **[PARSE-13]** If `c` is an ASCII alpha (`A`–`Z`, `a`–`z`): set state to SCHEME and reconsume `c` (do not advance). The SCHEME state itself accumulates the scheme.
- Otherwise: set state to NO_SCHEME and reconsume `c`.

#### 8.3.2 SCHEME — *#scheme-state*

Entry: from SCHEME START. Profiles: both.

- **[PARSE-14]** While `c` is an ASCII alphanumeric, U+002B (`+`), U+002D (`-`), or U+002E (`.`): advance. (The scheme buffer is the input slice from the start of input to the current `pos`.)
- **[PARSE-15]** If `c` is U+003A (`:`): the slice `[0, pos)` is the scheme. The parser MUST validate and record it via `parseScheme` (§6); an invalid scheme MUST yield `Err`. The scheme MUST be ASCII-lowercased for storage in the `Url` profile; in the `Uri` profile the scheme MUST be lowercased on output for comparison but its parse is case-insensitive (§6/§11). Then dispatch:
  - If the scheme is `file` (`Url` profile): set state to FILE. Advance. (See [PARSE-55] for the validation error recorded when the remaining input does not begin with `//`.)
  - Else if (`Url` profile) the scheme is special, a base URL is present, and the base URL's scheme equals this scheme: set state to SPECIAL RELATIVE OR AUTHORITY. Advance.
  - Else if (`Url` profile) the scheme is special: set state to SPECIAL AUTHORITY SLASHES. Advance.
  - Else if the code point immediately after the `:` (i.e. `input[pos+1]`) exists and is U+002F (`/`): set state to PATH OR AUTHORITY and advance by 2 (past `:` and the `/`). In the `Uri` profile this is the only authority-introduction path.
  - Else: the value has an opaque path; set state to OPAQUE PATH. Advance past the `:`.
- **[PARSE-55]** In the `Url` profile, when the scheme is `file`, if the input remaining after the `:` does not begin with `//`, the parser MUST record a `ValidationError` of kind *special-scheme-missing-following-solidus* before transitioning to FILE.
- **[PARSE-16]** Otherwise (`c` is not `:` and not a valid scheme code point, including EOF): the leading run was not a scheme. The parser MUST set state to NO_SCHEME and restart the scan from the first code point — i.e. set `pos = 0` and reconsume. This is the single permitted reset of `pos` ([PARSE-9]).

> Note: restart-from-zero handles inputs like `a/b:c` where `a/b` is not a scheme but a relative path whose first segment contains a later `:`.

#### 8.3.3 NO SCHEME — *#no-scheme-state*

Entry: from SCHEME START / SCHEME when no scheme is found. Profiles: both.

- **[PARSE-17]** If there is no base URL, OR the base URL has an opaque path and `c` is not the (already-pruned) fragment marker context: the parser MUST return `Err` (a relative reference requires a base, and a relative reference against an opaque-path base is only valid as a pure fragment).
- **[PARSE-18]** If the base URL has an opaque path and the entire remaining input is empty (the input was a bare fragment): copy the base URL's scheme, path, and query into the result, attach the pruned fragment (§8.1), and return `Ok`.
- Else, in the `Url` profile: if the base URL's scheme is `file`, set state to FILE and reconsume; otherwise set state to RELATIVE and reconsume.
- **[PARSE-19]** In the `Uri` profile, the parser MUST NOT perform in-state base merging here. It MUST parse the input as a standalone *relative reference* — capturing whichever of authority, path, query are present — and defer the merge with the base URL to the RFC 3986 §5 resolution algorithm specified in §9. The RELATIVE / RELATIVE SLASH / SPECIAL_* base-copy branches below are `Url`-profile only.

#### 8.3.4 SPECIAL RELATIVE OR AUTHORITY — *#special-relative-or-authority-state*

Entry: from SCHEME (special scheme, same-scheme base). Profiles: `Url` only.

- **[PARSE-20]** If `c` is U+002F (`/`) and the next code point is U+002F (`/`): set state to SPECIAL AUTHORITY IGNORE SLASHES and advance by 2.
- Otherwise: record a `ValidationError` of kind *special-scheme-missing-following-solidus*, set state to RELATIVE, and reconsume.

#### 8.3.5 PATH OR AUTHORITY — *#path-or-authority-state*

Entry: from SCHEME (non-special scheme whose remainder begins `/`). Profiles: both. This is the `Uri`-profile authority gate.

- **[PARSE-21]** If `c` is U+002F (`/`): set state to AUTHORITY and advance. (Combined with the SCHEME-state step that consumed the first `/`, this requires exactly `//` after `scheme:` to introduce an authority in the `Uri` profile.)
- Otherwise: set state to PATH and reconsume.

#### 8.3.6 RELATIVE — *#relative-state*

Entry: from NO_SCHEME / SPECIAL RELATIVE OR AUTHORITY. Profiles: `Url` only (see [PARSE-19]).

- The result's scheme is set to the base URL's scheme.
- **[PARSE-22]** If `c` is U+002F (`/`): set state to RELATIVE SLASH and advance. If the scheme is special and `c` is U+005C (`\`): record a `ValidationError` of kind *invalid-reverse-solidus*, set state to RELATIVE SLASH, and advance.
- Otherwise: copy the base URL's username, password, host, port, path, and query into the result, then:
  - If `c` is U+003F (`?`): set the result's query to empty and set state to QUERY. Advance.
  - Else if `c` is EOF: done (the result equals the base URL up to its query, plus the pruned fragment).
  - Else: set the result's query to null, shorten the result's path by one segment (the §9 *shorten* operation), set state to PATH, and reconsume.

#### 8.3.7 RELATIVE SLASH — *#relative-slash-state*

Entry: from RELATIVE. Profiles: `Url` only.

- **[PARSE-23]** If the scheme is special and `c` is U+002F (`/`) or U+005C (`\`): (recording *invalid-reverse-solidus* if `\`) set state to SPECIAL AUTHORITY IGNORE SLASHES and advance.
- Else if `c` is U+002F (`/`): set state to AUTHORITY and advance.
- Otherwise: copy the base URL's username, password, host, and port into the result, set state to PATH, and reconsume.

#### 8.3.8 SPECIAL AUTHORITY SLASHES — *#special-authority-slashes-state*

Entry: from SCHEME (special scheme, no same-scheme base). Profiles: `Url` only.

- **[PARSE-24]** If `c` is U+002F (`/`) and the next code point is U+002F (`/`): advance by 2, then fall through to SPECIAL AUTHORITY IGNORE SLASHES. Otherwise: record *special-scheme-missing-following-solidus* and fall through to SPECIAL AUTHORITY IGNORE SLASHES without advancing.

#### 8.3.9 SPECIAL AUTHORITY IGNORE SLASHES — *#special-authority-ignore-slashes-state*

Entry: from the two states above / RELATIVE SLASH. Profiles: `Url` only.

- **[PARSE-25]** While `c` is U+002F (`/`) or U+005C (`\`): advance, recording one `ValidationError` of kind *special-scheme-missing-following-solidus* if any such code point is skipped here. When `c` is neither: set state to AUTHORITY and reconsume.

> Note: [PARSE-24]/[PARSE-25] collapse any run of `/` and `\` after `scheme:` for special schemes — `http:\\\/example.com`, `http:////example.com`, and `http://example.com` all reach AUTHORITY at `example.com`. This collapsing is `Url`-only (§8.5).

#### 8.3.10 AUTHORITY — *#authority-state*

Entry: from the authority-introduction states. Profiles: both. This state splits userinfo from the host using the rules of §8.4.

- **[PARSE-26]** The parser MUST scan forward to the *authority delimiter*: the first code point in the set defined by the active profile:

  | Profile / scheme | Authority delimiter set | Source |
  |---|---|---|
  | `Url`, special scheme | `@`  `/`  `\`  `?`  EOF | derived from ada `authority_delimiter_special` |
  | `Url`, non-special, or `Uri` | `@`  `/`  `?`  EOF | derived from ada `authority_delimiter` |

  The slice from `pos` up to (but excluding) the delimiter is the current *authority segment*.
- **[PARSE-27]** If the delimiter code point is U+0040 (`@`): apply the userinfo accumulation rules of §8.4 to the authority segment, set `pos` to one past the `@`, and continue scanning (the host may follow, or a further `@`).
- **[PARSE-56]** In the `Url` profile, each time an `@` is encountered while in the AUTHORITY state, the parser MUST record a `ValidationError` of kind *invalid-credentials* (mapped to `CredentialsInAuthority`) at the offset of the `@`, in addition to applying the userinfo accumulation rules of §8.4.
- **[PARSE-28]** Otherwise (delimiter is `/`, `?`, special-scheme `\`, or EOF): if an `@` was seen during this state and the remaining authority segment (the host candidate) is empty, the parser MUST return `Err` (*host-missing*). Otherwise set state to HOST and reconsume the delimiter.

#### 8.3.11 HOST — *#host-state* / *#hostname-state*

Entry: from AUTHORITY, FILE HOST, or PATH OR AUTHORITY chains. Profiles: both. Host parsing itself is delegated to §7; this state only locates the host's end and the optional port colon.

- **[PARSE-29]** The parser MUST scan for the *host delimiter*, the first code point in the set below that occurs OUTSIDE a `[...]` IPv6 bracket span:

  | Profile / scheme | Host delimiter set (outside brackets) | Source |
  |---|---|---|
  | `Url`, special scheme | `:`  `/`  `\`  `?`  EOF, plus `[` opens a bracket span | derived from ada `find_next_host_delimiter_special` |
  | `Url`, non-special, or `Uri` | `:`  `/`  `?`  EOF, plus `[` opens a bracket span | derived from ada `find_next_host_delimiter` |

  A `[` MUST start a bracket span that ends at the next `]`; any delimiter code point inside the span (notably `:`) MUST be ignored for host-termination purposes. An unterminated `[` consumes to EOF.
- **[PARSE-30]** If the located delimiter is U+003A (`:`) found outside brackets: the slice `[pos, colon)` is the host. If that host slice is empty the parser MUST return `Err` (*host-missing*). Otherwise call `parseHost` (§7) on the slice; on failure return `Err`. Set state to PORT and advance past the `:`.
- **[PARSE-31]** Otherwise (delimiter is `/`, `?`, special-scheme `\`, or EOF): if the host slice is empty AND the active configuration is a special scheme (`Url`), the parser MUST return `Err` (*host-missing*); a special-scheme URL MUST have a non-empty host. If the host slice is empty and the scheme is non-special (or `Uri`), the host is the empty host (`Host.Empty`). Otherwise call `parseHost` (§7). Set state to PATH START and reconsume the delimiter.

> Note: the `[`/`]` bracket awareness is what lets `[2001:db8::1]:80` split host from port at the LAST `:` rather than an interior IPv6 `:`.

#### 8.3.12 PORT — *#port-state*

Entry: from HOST. Profiles: both.

- **[PARSE-32]** The parser MUST consume the maximal run of ASCII digits beginning at `pos` as the port. A non-digit, non-EOF code point that is not one of `/`, `?`, or (special `Url`) `\` MUST cause `Err` (*port-invalid*).
- **[PARSE-33]** The accumulated digits MUST be interpreted as a base-10 integer. In the `Url` profile, if it exceeds 65535 the parser MUST return `Err` (*port-out-of-range*). In the `Uri` profile the port is `*DIGIT` per RFC 3986 §3.2.3 and is NOT range-limited at parse time: a value in `0..Int.MAX_VALUE` MUST NOT be fatal — it is accepted and stored as its decimal `Int` value ([MODEL-23]), with any 16-bit range validation deferred to the scheme. A digit run whose value exceeds `Int.MAX_VALUE` is rejected as `InvalidPort`, and leading zeros are not preserved. The empty port (e.g. `host:/path`) is permitted and denotes no explicit port. (The `Url`-profile 65535 cap is a sanctioned deviation from RFC 3986 — see Appendix B [DEV-3].)
- **[PARSE-34]** In the `Url` profile, if the parsed port equals the default port for the scheme (§6), the port MUST be elided (stored as absent) and `effectivePort` resolves to that default. In the `Uri` profile the port MUST be preserved exactly as written (no default-port elision); `effectivePort` resolves the scheme default only as a computed accessor, not a stored mutation.
- After consuming the port, set state to PATH START and reconsume the terminating delimiter (or finish at EOF).

#### 8.3.13 FILE — *#file-state*

Entry: from SCHEME (`file`) / NO_SCHEME (file base). Profiles: `Url` only.

- The result's scheme is set to `file` and its host to the empty string.
- **[PARSE-35]** If `c` is U+002F (`/`) or U+005C (`\`): (recording *invalid-reverse-solidus* if `\`) set state to FILE SLASH and advance.
- Else if a base URL with scheme `file` is present: copy the base URL's host, path, and query; then if `c` is `?` set query empty and go to QUERY (advance); else if `c` is EOF, finish; else clear query, and — if the remaining input does not begin with a Windows drive letter — shorten the copied path (§9), then set state to PATH and reconsume. If it does begin with a Windows drive letter, clear the path before reconsuming PATH.
- Otherwise: set state to PATH and reconsume.
- **[PARSE-57]** In the `Url` profile FILE state file-base branch, when the remaining input does begin with a Windows drive letter (the branch that clears the copied path before reconsuming PATH), the parser MUST record a `ValidationError` of kind *file-invalid-Windows-drive-letter*.

#### 8.3.14 FILE SLASH — *#file-slash-state*

Entry: from FILE. Profiles: `Url` only.

- **[PARSE-36]** If `c` is U+002F (`/`) or U+005C (`\`): (recording *invalid-reverse-solidus* if `\`) set state to FILE HOST and advance.
- Otherwise: if a base URL with scheme `file` is present, copy its host; and if the remaining input does not start with a Windows drive letter but the base URL's first path segment is a normalized Windows drive letter, prepend that segment to the result's path. Set state to PATH and reconsume.

#### 8.3.15 FILE HOST — *#file-host-state*

Entry: from FILE SLASH. Profiles: `Url` only.

- **[PARSE-37]** Scan to the first of `/`, `\`, `?`, or EOF; the slice is the file-host buffer. If that buffer is a Windows drive letter (e.g. `C:`): set state to PATH and reconsume (the buffer is a path, not a host). If the buffer is empty: set the host to empty and set state to PATH START. Otherwise call `parseHost` (§7); if the resulting host is `localhost`, replace it with the empty host. Set state to PATH START.
- **[PARSE-58]** In the `Url` profile FILE HOST state, when the scanned buffer is a Windows drive letter (so it is reinterpreted as a path rather than a host), the parser MUST record a `ValidationError` of kind *file-invalid-Windows-drive-letter-host* before transitioning to PATH (the buffer is not reset and is consumed by the PATH state).

#### 8.3.16 PATH START — *#path-start-state*

Entry: from HOST/PORT/FILE HOST. Profiles: both.

- **[PARSE-38]** If the scheme is special (`Url`): set state to PATH. If `c` is neither `/` nor `\`, reconsume (do not advance); otherwise advance. (Recording *invalid-reverse-solidus* if `c` is `\`.) A special URL whose authority is immediately followed by EOF MUST receive the path `/`.
- Else if `c` is U+003F (`?`): set the query to empty and set state to QUERY. Advance.
- Else if `c` is not EOF: set state to PATH; if `c` is not `/`, reconsume; otherwise advance.
- Else (EOF, non-special): the path is empty; finish.

#### 8.3.17 PATH — *#path-state*

Entry: from PATH START and others. Profiles: both. Segment splitting, dot-segment handling, and the percent-encode set for path segments are specified in §9 and §5; this state only delimits the path and feeds slices to §9.

- **[PARSE-39]** The parser MUST scan to the next U+003F (`?`) or EOF. The path slice is `[pos, end)`. In the `Url` profile, U+002F (`/`) and (special scheme) U+005C (`\`) within the slice are segment separators and `\` MUST be treated as `/` (§8.5); dot segments (`.`, `..`, and their percent-encoded/backslash equivalents) MUST be resolved per §9. In the `Uri` profile, only U+002F (`/`) separates segments, `\` is an ordinary code point, and dot-segment removal is applied only when normalization is requested (§9/§11).
- **[PARSE-40]** Each path segment MUST be percent-encoded with the path percent-encode set of §5 in the `Url` profile; in the `Uri` profile the path is preserved as written (already-present `%hh` triplets MUST survive verbatim) unless encoding/normalization is explicitly requested.
- On reaching `?`: set state to QUERY and advance past the `?`. On EOF: finish.

#### 8.3.18 OPAQUE PATH — *#cannot-be-a-base-url-path-state*

Entry: from SCHEME for a non-special scheme with no `//` authority. Profiles: both.

- **[PARSE-41]** The result is marked as having an opaque path. The parser MUST scan to the first U+003F (`?`) or EOF. The slice up to that point is the opaque path. In the `Url` profile it MUST be percent-encoded with the C0-control percent-encode set (§5); because U+0020 (space) is not in that set it is normally left literal, EXCEPT that a U+0020 (space) immediately followed by U+003F (`?`) or U+0023 (`#`) MUST be encoded as `%20`. In the `Uri` profile the opaque path is preserved as written (PRESERVE default).
- On `?`: set the query to empty, set state to QUERY, advance past the `?`. On EOF: finish.

> Note: an opaque-path value (e.g. `mailto:a@b`, `urn:isbn:0`) has no authority and cannot be a base for path-relative resolution; only fragment-only relative references resolve against it (see [PARSE-17]/[PARSE-18]).

#### 8.3.19 QUERY — *#query-state*

Entry: from PATH/PATH START/OPAQUE PATH/RELATIVE/FILE. Profiles: both. The full query and form-encoding model is §10; the percent-encode set selection is delegated to §5.

- **[PARSE-42]** The query is the remaining input up to EOF (the fragment was already pruned in §8.1). The parser MUST select the encode set as follows: in the `Url` profile, the *special-query percent-encode set* if the scheme is special, otherwise the *query percent-encode set* (§5). In the `Uri` profile the query is preserved as written by default; encoding with the query set is applied only when requested (§10/§11).
- Percent-encode the query slice with the selected set and store it. Then finish (attach the pruned fragment).

#### 8.3.20 FRAGMENT — *#fragment-state*

Entry: not entered as a loop state; the fragment is pruned in §8.1 and attached last. Profiles: both.

- **[PARSE-43]** The fragment component captured by [PARSE-7] MUST, in the `Url` profile, be percent-encoded with the *fragment percent-encode set* (§5) before storage. In the `Uri` profile the fragment is preserved as written by default; encoding is applied only on request. A present-but-empty fragment ([PARSE-8]) MUST be retained as an empty string, distinct from an absent fragment.
- **[PARSE-59]** In the `Url` profile, while consuming code points in the PATH ([PARSE-39]/[PARSE-40]), OPAQUE PATH ([PARSE-41]), QUERY ([PARSE-42]), and FRAGMENT ([PARSE-43]) states, the parser MUST record a `ValidationError` of kind *invalid-URL-unit* when (a) the code point is not a URL code point (§4.2.5) and not U+0025 (`%`) — recorded as `InvalidCodePointPercentEncoded` — or (b) the code point is U+0025 (`%`) and the next two code points are not both ASCII hex digits — recorded as `MalformedPercentEncoding`. Recording these errors does not change the produced value.

### 8.4 Userinfo splitting

These rules govern how the AUTHORITY state ([PARSE-27]) turns authority segments into username and password. The username and password are accumulated across the whole authority because additional `@` code points may appear inside userinfo.

- **[PARSE-44]** Within the authority, the boundary between userinfo and host is the LAST U+0040 (`@`) that lies before the host delimiter. The parser MUST treat every `@` except the last as part of the userinfo and percent-encode it as `%40`.
- **[PARSE-45]** The first U+003A (`:`) within the userinfo splits it into username (before the `:`) and password (after the `:`). Subsequent `:` code points are part of the password and are percent-encoded by the userinfo set, not treated as further splits.
- **[PARSE-46]** The parser MUST track two flags while scanning authority segments: *at-sign-seen* and *password-token-seen*. For each `@`-terminated segment:
  - If *at-sign-seen* is already true, the parser MUST prepend `%40` to the buffer currently being filled (password if *password-token-seen*, else username) before appending this segment's content.
  - Set *at-sign-seen* to true.
  - If *password-token-seen* is false, search this segment for the first `:`. If none, append the whole segment (userinfo-encoded) to the username. If found, set *password-token-seen* true, append the part before the `:` (userinfo-encoded) to the username and the part after (userinfo-encoded) to the password.
  - If *password-token-seen* is already true, append the whole segment (userinfo-encoded) to the password.
- **[PARSE-47]** The percent-encode set applied to username and password content is the *userinfo percent-encode set* (§5) in the `Url` profile. In the `Uri` profile, userinfo is preserved by default and encoded with the userinfo set only on request; the LAST-`@` boundary rule and the first-`:` split rule of [PARSE-44]/[PARSE-45] still apply identically.
- **[PARSE-48]** If `@` was seen but the host candidate following the userinfo is empty, the parser MUST return `Err` (*host-missing*) — this is enforced by [PARSE-28].

> Note: `http://a@b@c.example/` stores username `a%40b`, host `c.example`; `http://u:p:q@h/` stores username `u`, password `p%3Aq`. Both follow from the last-`@` boundary plus first-`:` split.

### 8.5 Profile-gated quirk branches

This subsection consolidates the divergences that the active `ParseProfile` switches on. Each is normative and is also reflected in the per-state text above. (The special-scheme backslash-as-solidus and slash-run-collapsing quirks are sanctioned `Url`-profile deviations from RFC 3986 — see Appendix B [DEV-1] and [DEV-2].)

| Quirk | `Url` profile | `Uri` profile |
|---|---|---|
| **[PARSE-49]** Backslash as solidus | For special schemes, U+005C (`\`) MUST be treated as U+002F (`/`) wherever a `/` is a delimiter: after `scheme:` (slash collapsing), as an authority terminator, as a path-segment separator, and in FILE/FILE SLASH/FILE HOST. Each such `\` MUST record a `ValidationError` of kind *invalid-reverse-solidus*. | `\` is never a delimiter or separator. It is an ordinary code point, percent-encoded only where a component's encode set requires it; it produces no validation error. |
| **[PARSE-50]** Authority introduction | Special schemes reach the authority via SPECIAL AUTHORITY SLASHES / SPECIAL AUTHORITY IGNORE SLASHES, which collapse ANY run of `/` and `\` after `scheme:` (so `0`, `1`, `2`, or more slashes all reach AUTHORITY). Non-special schemes use PATH OR AUTHORITY and require `//`. | An authority is introduced ONLY by exactly `//` immediately after `scheme:` (via PATH OR AUTHORITY), or by a leading `//` in a scheme-relative reference. Three or more leading slashes do NOT collapse; the third slash begins the path (`http://h//p` has host `h`, path `//p` only via the authority rule; `scheme:///x` yields empty host then path `/x`). The parser MUST NOT collapse slash runs. |
| **[PARSE-51]** Special-scheme magic | Default-port elision (§6), the `file` sub-machine, IDNA/host canonicalization, and IPv4 shorthand (§7) apply. *is special* may be true. | *is special* is always false ([PARSE-12]); none of the special-scheme magic applies. Host is parsed per RFC 3986 generic syntax (§7), ports are preserved, no `file` sub-machine. |
| **[PARSE-52]** Canonicalization timing | EAGER: percent-encoding per-component encode sets, host pipeline, dot-segment removal, and default-port elision are applied during parsing. | PRESERVE by default: components are stored as written; normalization (percent-triplet casing, dot-segment removal, host lowercasing, encoding) is applied only when explicitly requested (§11). |
| **[PARSE-53]** Pre-processing | Strip tab/LF/CR anywhere; trim leading/trailing C0-or-space ([PARSE-3]/[PARSE-5]). | No stripping, no trimming; embedded controls where the grammar forbids them are fatal ([PARSE-4]/[PARSE-6]). |

**[PARSE-54]** A conformant implementation MUST gate every quirk in the table above solely on the active `ParseProfile` and MUST NOT let `Url`-profile behaviour leak into a `Uri` parse or vice versa; the two profiles share the state set and delimiter-scanning machinery but differ exactly by these branches.

> Note: state-to-spec mapping for auditing — SCHEME START → #scheme-start-state, SCHEME → #scheme-state, NO_SCHEME → #no-scheme-state, SPECIAL RELATIVE OR AUTHORITY → #special-relative-or-authority-state, PATH OR AUTHORITY → #path-or-authority-state, RELATIVE → #relative-state, RELATIVE SLASH → #relative-slash-state, SPECIAL AUTHORITY SLASHES → #special-authority-slashes-state, SPECIAL AUTHORITY IGNORE SLASHES → #special-authority-ignore-slashes-state, AUTHORITY → #authority-state, HOST → #host-state / #hostname-state, PORT → #port-state, FILE → #file-state, FILE SLASH → #file-slash-state, FILE HOST → #file-host-state, PATH START → #path-start-state, PATH → #path-state, OPAQUE PATH → #cannot-be-a-base-url-path-state, QUERY → #query-state, FRAGMENT → #fragment-state. cf. ada `src/parser.cpp` and `include/ada/state.h`.

## 9. Paths & Reference Resolution

This section specifies the path component model, dot-segment removal, profile-specific path rewriting (backslash conversion, Windows drive letters), opaque (cannot-be-a-base) paths, the path/authority structural constraints, and the RFC 3986 §5 reference-resolution algorithm. The percent-encoding of path code points is governed by §5 (PCT); the host pipeline by §7 (HOST). Cross-references to those sections are non-normative where they appear in prose.

Throughout this section, "the path" denotes the value of the path `component` of a `Uri` or `Url`, and "the active profile" is the `ParseProfile` (`URI` or `URL`) under which the value was produced (§3, MODEL).

### 9.1 Path model

A path is one of exactly two shapes, fixed at parse time and recorded in the data model:

| Shape | Held as | Produced when |
|---|---|---|
| List path | An ordered list of zero or more `segment`s | The URI has an authority, or the path begins with `/`, or (in the `Url` profile) the scheme is special |
| Opaque path | A single percent-encoded string with no segment structure (§9.5) | The URI is a cannot-be-a-base URL (no authority, scheme present, path does not begin with `/`) in the `Url` profile; or a `Uri`-profile rootless/opaque path the model is configured to keep opaque |

**[PATH-1]** A list path SHALL be modelled as an ordered, index-addressable list of segments. Each segment is a code-point string. The serialization of a list path is the concatenation, for each segment in order, of `/` followed by the segment's encoded form. A list path therefore serializes either empty (no segments) or with a leading `/`.

**[PATH-2]** Empty segments are significant and MUST be preserved. The serialized forms `/`, `//`, and `/a//b` denote, respectively, the segment lists `[""]`, `["", ""]`, and `["a", "", "b"]`. Parsing a serialized path and re-serializing it MUST reproduce the original segment count and emptiness exactly (round-trip).

**[PATH-3]** A trailing empty segment denotes a directory path. A path whose last segment is the empty string (equivalently, whose serialization ends in `/`) SHALL be reported as a directory path by any `isDirectory`/`hasTrailingSlash`-style accessor. `addPathSegment("")` (appending an empty segment) SHALL append a trailing slash; appending a non-empty segment to a directory path SHALL replace the trailing empty segment's position (i.e. `["a",""] + "b"` serializes `/a/b`, not `/a//b`), whereas appending to a non-directory path SHALL add a new segment.

**[PATH-4]** Each segment exposes two views: an *encoded* view (the segment as it appears in the serialization, with percent-encoding intact) and a *decoded* view (the segment after percent-decoding its octets and interpreting them as UTF-8, per §5). A consumer reading the decoded view MUST receive the percent-decoded code points; a consumer reading the encoded view MUST receive the bytes as serialized.

**[PATH-5]** A `%2F` or `%2f` triplet inside a segment denotes a literal `/` code point in that segment's DECODED view and MUST NOT be treated as a segment boundary. Parsing `a%2Fb%2Fc` (with no unencoded `/`) SHALL yield exactly one segment whose encoded view is `a%2Fb%2Fc` and whose decoded view is `a/b/c`. Segment boundaries are determined SOLELY by unencoded `/` (and, in the `Url` special-scheme profile, unencoded `\`; see §9.3) code points in the input.

**[PATH-6]** The encoded and decoded views compose without double-encoding ambiguity. An already-encoded triplet supplied through an "encoded" mutator (e.g. `addEncodedPathSegment`) MUST be retained verbatim, while a raw value supplied through a "decoded" mutator (e.g. `addPathSegment`) MUST have every code point of the path percent-encode set (§5, PCT) — including a literal `%` — percent-encoded. For example `addPathSegment("d%25e")` yields encoded segment `d%2525e`, while `addEncodedPathSegment("f%25g")` yields encoded segment `f%25g`.

### 9.2 Dot-segment removal

Dot-segment removal is the RFC 3986 `remove_dot_segments` procedure. It operates on the serialized path string (not on the segment list) and is total: it terminates for every finite input.

**[PATH-7]** `removeDotSegments(input)` SHALL be computed by the following algorithm. Let *input* be the path string and *output* be an initially empty string. While *input* is non-empty, apply the FIRST matching rule:

| # | If *input* begins with… | Action |
|---|---|---|
| A | `../` or `./` | Remove that prefix from *input*. |
| B | `/./`, or `/.` where `.` is a complete final segment | Replace that prefix in *input* with `/`. |
| C | `/../`, or `/..` where `..` is a complete final segment | Replace that prefix in *input* with `/`, and remove the last segment together with its preceding `/` (if any) from *output*. |
| D | *input* is exactly `.` or `..` | Remove it from *input*. |
| E | (otherwise) | Move the first path segment of *input* — the initial `/` (if any) plus all characters up to but not including the next `/` or end of *input* — to the end of *output*. |

When *input* is empty, the result is *output*. "A complete final segment" in rules B and C means the `.`/`..` is followed by end-of-input (so `/.` and `/..` match only at the very end).

**[PATH-8]** Rule C MUST clamp on overshoot: removing the last segment from an empty or root-only *output* removes nothing and leaves *output* unchanged. Thus excess `..` segments are silently absorbed and the result never ascends above the root. (E.g. base-relative `../../../g` against `http://a/b/c/d;p?q` resolves to `http://a/g`, not above `a`.)

**[PATH-9]** In the `Url` profile, during dot-segment removal performed as part of reference resolution (§9.7), the triplets `%2e` and `%2E` SHALL be treated as the code point `.` for the purpose of recognizing `.` and `..` segments. In the `Uri` profile, `remove_dot_segments` (RFC 3986 §5.2.4) operates on the literal characters `.` and `..` ONLY and SHALL NOT decode `%2e`/`%2E`; collapsing percent-encoded dots is a separate, optional normalization (RFC 3986 §6.2.2.2/§6.2.2.3) performed after resolution, never as part of the resolution algorithm. (This `Url`-profile recognition of `%2e`/`%2E` is a sanctioned deviation from RFC 3986 — see Appendix B [DEV-12].) Equivalently, in the `Url` profile, the implementation MUST recognize `%2e`, `%2E`, `..`, `.%2e`, `%2e.`, etc. as dot or double-dot segments. A segment such as `%2E%2E` therefore pops a segment, and `%2E` collapses to a directory marker, exactly as `..` and `.` would.

**[PATH-10]** The treatment in [PATH-9] is confined to resolution/normalization. When a literal segment is added through `addPathSegment` or `addEncodedPathSegment`, an encoded dot (`%2e`, `%2E`) MUST remain a literal, ordinary segment and MUST NOT be collapsed; `addPathSegment("%2e")` yields the encoded segment `%252e` (the `%` is encoded per [PATH-6]) and `addEncodedPathSegment("%2e")` yields the literal encoded segment `%2e`. Neither participates in dot-segment removal.

**[PATH-11]** `setPathSegment(i, value)` SHALL reject a *decoded* value equal to `.` or `..` with a `UriParseError`, because such a segment cannot be represented as a literal at that index without being subject to [PATH-9] on re-resolution. `addPathSegment("..")` and `addPathSegment(".")` are NOT rejected by the model when used as relative-mutation operators: `addPathSegment("..")` SHALL pop the last segment (directory semantics) and `addPathSegment(".")` SHALL normalize to a directory marker, consistent with [PATH-7].

### 9.3 Backslash conversion (special schemes, `Url` profile)

**[PATH-12]** In the `Url` profile, when the scheme is special (§6, SCH), every `\` (U+005C) code point occurring in the path-parsing portion of the input SHALL be treated as `/` for the purpose of segment splitting and dot-segment recognition. Resolving the reference `d\e\f` against a special base therefore yields the path `/d/e/f`. In the `Uri` profile, and in the `Url` profile for non-special schemes, `\` is NOT a segment delimiter: it is an ordinary code point and MUST be percent-encoded (`%5C`) wherever the path percent-encode set (§5) requires it.

**[PATH-13]** Backslash conversion under [PATH-12] is a parse-time rewrite: the resulting segments contain `/` boundaries and the model retains no record of the original `\`. A `\` that is percent-encoded in the input (`%5C`) is NOT a delimiter and stays a literal backslash code point in the decoded segment.

### 9.4 `file` Windows drive letters

These rules apply only when the scheme is `file` (necessarily the `Url` profile; the `Uri` profile has no `file` special handling).

**[PATH-14]** A *Windows drive letter* is two code points: an ASCII alpha followed by either `:` or `|`. A *normalized Windows drive letter* is an ASCII alpha followed by `:`. A path-segment string "is a Windows drive letter" when it is exactly those two code points, and "starts with a Windows drive letter" when its first two code points are a Windows drive letter and it is either exactly two code points long or its third code point is one of `/`, `\`, `?`, or `#`.

**[PATH-15]** When the scheme is `file`, the path is empty, and a segment being appended is (or starts with) a Windows drive letter, the second code point of that drive letter SHALL be normalized from `|` to `:`. Thus `C|` becomes `C:` and `C|/foo` becomes `C:/foo`. The normalization applies to the drive-letter detection position only; a `|` elsewhere in the path is unaffected.

**[PATH-16]** Dot-segment removal (`shorten_path`) MUST NOT remove a sole drive-letter segment. Specifically, when the scheme is `file`, the path consists of a single segment, and that segment is a normalized Windows drive letter, the "remove last segment" step of rule C / [PATH-7] SHALL be a no-op. (E.g. `file:///C:/..` retains `/C:/`, not `/`.)

### 9.5 Opaque paths (cannot-be-a-base URLs)

**[PATH-17]** In the `Url` profile, a URL with a scheme but no authority whose path does not begin with `/` is a *cannot-be-a-base* URL and its path is an *opaque path*: a single string with NO segment structure. The segment-list mutators ([PATH-1]–[PATH-6], [PATH-11]) and dot-segment removal ([PATH-7]) SHALL NOT apply to an opaque path; any segment-indexed operation on such a value SHALL fail with a `UriParseError`. Examples: `mailto:John.Doe@example.com`, `tel:+1-816-555-1212`, `urn:oasis:names:specification:docbook`, `data:text/plain,hi`.

**[PATH-18]** An opaque path SHALL be percent-encoded using the C0-control percent-encode set (§5, PCT): code points in the C0 control range (U+0000–U+001F), code points greater than U+007E, and U+007F SHALL be percent-encoded; all other code points are appended literally. No `\`→`/` rewriting (§9.3) and no dot-segment removal (§9.2) are performed on an opaque path.

**[PATH-19]** Trailing U+0020 SPACE code points in an opaque path SHALL be stripped if and only if the URL has neither a query nor a fragment. When a query or fragment is subsequently set, previously significant trailing spaces are not retroactively restored; when a single trailing space must be preserved (e.g. the input ends with a space and no query/fragment follows), it is stripped per this rule. A space that must be carried as data within an opaque path (not trailing, or shielded by a following query/fragment) SHALL appear percent-encoded as `%20` only where the C0 set ([PATH-18]) requires it; SPACE itself is not in the C0 set, so a non-trailing space remains literal.

### 9.6 Path/authority structural constraints

**[PATH-20]** When a URI has an authority (the authority component is present, even if empty), the path MUST be either empty or begin with `/`. A non-empty path that does not begin with `/` is invalid in the presence of an authority and SHALL be rejected with a `UriParseError`; on construction via builder, the implementation MUST reject such a combination rather than emit unparseable output.

**[PATH-21]** When a URI has no authority, the path MUST NOT begin with `//`, because a serialized `scheme:` followed by `//` would be re-parsed as introducing an authority. A value model that holds no authority but a path beginning with `//` SHALL serialize it using the leading-`/.`-sentinel: the recomposed output emits `/.` immediately before the path so that `//x` is written `/.//x`. On re-parse, the `/.` is removed by dot-segment recognition and the original hostless path is recovered. This sentinel SHALL be emitted only when required to preserve the no-authority + leading-`//` case on round-trip, and MUST NOT alter the segment list observable through the model.

**[PATH-22]** A relative-reference path with no scheme and no authority (a *path-noscheme*) MUST NOT have a `:` in its first segment, since a leading `seg:` would be parsed as a scheme. An implementation that builds such a reference SHALL percent-encode or otherwise guard the first-segment colon, or reject the value with a `UriParseError`.

### 9.7 Reference resolution

Reference resolution computes a target URI *T* from a *base URI* *B* and a *relative reference* *R*, per RFC 3986 §5.2–§5.3.

**[PATH-23]** The base URI *B* MUST have a scheme. Resolving against a base with no scheme SHALL fail with a `UriParseError`. In the `Url` profile, *B* MUST additionally be a valid `Url`; in the `Uri` profile, *B* MUST be a valid `Uri`.

**[PATH-24]** `transformReferences(B, R)` SHALL compute *T* as follows (RFC 3986 §5.2.2). "defined(X)" means component X is present (non-null); an empty string is present:

```
if defined(R.scheme):
    T.scheme    = R.scheme
    T.authority = R.authority
    T.path      = removeDotSegments(R.path)
    T.query     = R.query
else:
    if defined(R.authority):
        T.authority = R.authority
        T.path      = removeDotSegments(R.path)
        T.query     = R.query
    else:
        if R.path == "":
            T.path = B.path
            if defined(R.query): T.query = R.query
            else:                T.query = B.query
        else:
            if R.path starts-with "/":
                T.path = removeDotSegments(R.path)
            else:
                T.path = removeDotSegments(merge(B, R))
            T.query = R.query
        T.authority = B.authority
    T.scheme = B.scheme
T.fragment = R.fragment
```

**[PATH-25]** `merge(B, R)` (RFC 3986 §5.2.3) SHALL be: if *B* has an authority and *B*'s path is empty, the result is `/` concatenated with *R*'s path; otherwise the result is all of *B*'s path up to and including its last `/` (or the empty string if *B*'s path contains no `/`), concatenated with *R*'s path.

**[PATH-26]** The target *T* SHALL be recomposed (RFC 3986 §5.3) into a string by appending, in order: *scheme* + `:` if a scheme is defined; `//` + *authority* if an authority is defined; *path*; `?` + *query* if a query is defined; `#` + *fragment* if a fragment is defined. The path/authority constraints of §9.6 apply to the recomposed result.

**[PATH-27]** Resolving the empty reference `""` (no scheme, no authority, empty path, no query, no fragment) SHALL yield *B* unchanged except that *T*'s fragment is *R*'s fragment — i.e. the empty reference yields *B* with its fragment removed. A reference of the form `#frag` SHALL yield *B* with its fragment replaced; `?query` SHALL yield *B* with query replaced and fragment cleared per the algorithm.

**[PATH-28]** In the `Url` profile, a reference whose scheme is present and equals *B*'s scheme but which is a special scheme is resolved relative to *B* (WHATWG "special relative" behavior), so `http:g` against base `http://a/b/c/d;p?q` resolves to `http://a/b/c/g`, not `http:g`. A reference with a *different* (foreign) scheme is absolute and replaces the base entirely; if that absolute reference is not itself a valid `Url` (e.g. `g:h` as a relative input expected to be a special URL), resolution SHALL return `ParseResult.Err`. In the `Uri` profile, a present scheme is always treated as absolute per [PATH-24] (the RFC strict reading), so `http:g` resolves to `http:g`.

**[PATH-29]** The WHATWG same-scheme authority subtleties MUST be honored in the `Url` profile: `http:host/p`, `http:/host/p`, and `http://host/p` are distinguished by how many slash code points follow the scheme — for special schemes any run of `/` and `\` after `scheme:` introduces the authority, so all three forms resolve to host `host` with path `/p`. The number and kind of leading slashes after `scheme:` SHALL be interpreted under the special-scheme slash rules of §6/§8, not retained literally.

**[PATH-30]** The following RFC 3986 §5.4 vectors, resolved against base `http://a/b/c/d;p?q`, are REQUIRED conformance vectors; an implementation MUST reproduce every listed result exactly (in both profiles, except `http:g`/`g:h`, which differ by profile per [PATH-28] and are validated against the profile-appropriate column).

§5.4.1 (normal):

| Reference | Result |
|---|---|
| `g:h` | `g:h` |
| `g` | `http://a/b/c/g` |
| `./g` | `http://a/b/c/g` |
| `g/` | `http://a/b/c/g/` |
| `/g` | `http://a/g` |
| `//g` | `http://g` |
| `?y` | `http://a/b/c/d;p?y` |
| `g?y` | `http://a/b/c/g?y` |
| `#s` | `http://a/b/c/d;p?q#s` |
| `g#s` | `http://a/b/c/g#s` |
| `g?y#s` | `http://a/b/c/g?y#s` |
| `;x` | `http://a/b/c/;x` |
| `g;x` | `http://a/b/c/g;x` |
| `g;x?y#s` | `http://a/b/c/g;x?y#s` |
| `` (empty) | `http://a/b/c/d;p?q` |
| `.` | `http://a/b/c/` |
| `./` | `http://a/b/c/` |
| `..` | `http://a/b/` |
| `../` | `http://a/b/` |
| `../g` | `http://a/b/g` |
| `../..` | `http://a/` |
| `../../` | `http://a/` |
| `../../g` | `http://a/g` |

§5.4.2 (abnormal):

| Reference | Result |
|---|---|
| `../../../g` | `http://a/g` |
| `../../../../g` | `http://a/g` |
| `/./g` | `http://a/g` |
| `/../g` | `http://a/g` |
| `g.` | `http://a/b/c/g.` |
| `.g` | `http://a/b/c/.g` |
| `g..` | `http://a/b/c/g..` |
| `..g` | `http://a/b/c/..g` |
| `./../g` | `http://a/b/g` |
| `./g/.` | `http://a/b/c/g/` |
| `g/./h` | `http://a/b/c/g/h` |
| `g/../h` | `http://a/b/c/h` |
| `g;x=1/./y` | `http://a/b/c/g;x=1/y` |
| `g;x=1/../y` | `http://a/b/c/y` |
| `g?y/./x` | `http://a/b/c/g?y/./x` |
| `g?y/../x` | `http://a/b/c/g?y/../x` |
| `g#s/./x` | `http://a/b/c/g#s/./x` |
| `g#s/../x` | `http://a/b/c/g#s/../x` |
| `http:g` | `Uri` profile: `http:g`  ·  `Url` profile: `http://a/b/c/g` |

Note: the `remove_dot_segments`, `merge`, and `transform-references` procedures are derived from RFC 3986 §5.2–§5.3; the special-scheme slash and `shorten_path` behaviors are derived from ada `helpers.cpp` (`shorten_path`, `parse_prepared_path`) and the WHATWG URL Standard §4.4. The `http:g` profile split reflects RFC 3986 §5.4.2's strict-vs-backward-compatible note resolved per the `ParseProfile`.

## 10. Query & Form Encoding

This section defines two distinct layers: the **raw query** (an opaque string component of `Uri`/`Url`, §10.1) and the structured **`QueryParameters`** view derived from it (§10.2–§10.3). It also defines the `application/x-www-form-urlencoded` codec (§10.4) as a deliberately separate dialect, and the resource bound on pair parsing (§10.5).

Requirements in this section are tagged **[QUERY-n]**.

### 10.1 The raw query component

The query is the component delimited by the first unescaped `?` and the first unescaped `#` (or the end of input). It is modelled as a single nullable string.

**[QUERY-1]** A `Uri`/`Url` MUST distinguish three states of the query component, and the model MUST preserve the distinction across parse and serialization:

| Input fragment | `query` value | Meaning |
| --- | --- | --- |
| (no `?`) | `null` | query absent |
| `…?` (then `#` or end) | `""` | query present, empty |
| `…?abc` | `"abc"` | query present, non-empty |

**[QUERY-2]** The raw query spans from the code point after the leading `?` up to but excluding the first unescaped `#` (or end of input). The query body MAY itself contain the code points `?`, `/`, `:`, `@`, `=`, and `&` literally; parsing MUST NOT terminate the query on any of these. The query terminates only at the first unescaped `#` (start of fragment) or end of input. (For example, `ldap://host/?objectClass?one` has query `objectClass?one`.)

**[QUERY-3]** Query serialization is profile-dependent:

- In the `Uri` profile, the raw query is **preserved verbatim** (PRESERVE default). The only transformation applied is percent-encoding of code points that are not admissible in a query component, using the `Uri` query percent-encode set defined in §5 (PCT). Existing percent-encoded triplets MUST NOT be decoded or re-encoded.
- In the `Url` profile, the raw query is **eagerly percent-encoded** at parse time using the WHATWG query percent-encode set (special-scheme variant including `'`, or the non-special variant) defined in §5 (PCT). The stored query is the canonical encoded form.

**[QUERY-4]** When a query is present (`non-null`), serialization MUST emit the `?` delimiter followed by the stored query octets. When the query is `null`, the `?` delimiter MUST NOT be emitted. An empty present query (`""`) therefore serializes as a bare `?`.

> Note: the three-state model mirrors okhttp `HttpUrl.query`/`encodedQuery` and the corpus assertion that `?#frag` yields query `""` (present-empty) plus a fragment, not `null`. cf. RFC 3986 §3.4 / WHATWG URL §4.4.

### 10.2 The `QueryParameters` model

`QueryParameters` is the structured interpretation of the raw query as a sequence of name/value pairs. It is a value type in the data model (§3, MODEL).

**[QUERY-5]** `QueryParameters` MUST be an **ordered**, **duplicate-preserving**, **case-sensitive** list of `(name, value?)` pairs, where each `value` is either a decoded string or `null`. Specifically:

- Order MUST follow appearance order in the raw query (insertion order under construction).
- Duplicate names MUST be retained as separate pairs; implementations MUST NOT collapse, deduplicate, or overwrite pairs that share a name.
- Name matching for all read and mutation operations MUST be byte-exact and **case-sensitive**.

Implementations MUST NOT model the query as a case-insensitive multimap, and MUST NOT model it as a map keyed by name. (A case-insensitive model is incorrect for RFC 3986/3987 query semantics; a map silently drops duplicate names.)

> Note: this is the okhttp/ada model (ordered list-of-pairs, case-sensitive, duplicate-preserving). It explicitly rejects ktor `Parameters` (case-insensitive `StringValues`) and chrynan's `Map<String,String?>` (drops duplicates).

#### 10.2.1 Derivation from the raw query

**[QUERY-6]** `QueryParameters` MUST be derived from the raw query string `S` (the component body, with no leading `?`) by the following algorithm. If the query component is `null`, the result MUST be the empty list (zero pairs).

```
pairs ← empty list
pos   ← 0
while pos ≤ length(S):                      // note: ≤, not <
    amp ← index of first '&' in S at or after pos, else length(S)
    eq  ← index of first '=' in S in the range [pos, amp), else NONE
    if eq = NONE:
        rawName  ← S[pos, amp)
        rawValue ← NONE                     // pair had no '='
    else:
        rawName  ← S[pos, eq)
        rawValue ← S[eq+1, amp)
    name  ← decode(rawName)
    value ← (rawValue = NONE) ? null : decode(rawValue)
    append (name, value) to pairs
    pos ← amp + 1
return pairs
```

Here `decode(x)` is the percent-decoder of §5 (PCT): octet-wise percent-decoding of `%XX` triplets (the two hex digits matched case-insensitively), with the resulting octets interpreted as UTF-8 to produce a string.

**[QUERY-7]** Within the `QueryParameters` derivation, the `+` code point MUST be treated as a literal `+` (it MUST NOT be converted to a space). Plus-as-space is exclusive to the form dialect (§10.4) and MUST NOT be applied here.

**[QUERY-8]** The split algorithm fixes the following value **sentinels**, all of which MUST be preserved (never collapsed):

| Raw query | Resulting pairs | Notes |
| --- | --- | --- |
| `k` | `[(k, null)]` | no `=` ⇒ value is `null` |
| `k=` | `[(k, "")]` | `=` present, empty value ⇒ `""` |
| `` (present-empty `?`) | `[("", null)]` | exactly one pair, empty name, `null` value |
| `&` | `[("", null), ("", null)]` | two such pairs |
| `a=1&b=2` | `[(a,1), (b,2)]` | ordinary case |
| `===3===` | `[("", "==3===")]` | only the **first** `=` splits; round-trips |

The `null` value (no `=`) and the `""` value (empty after `=`) MUST remain distinguishable.

> Note: the `pos ≤ length` loop bound (giving the present-empty query exactly one pair) and the first-`=`-splits rule are taken from okhttp `toQueryNamesAndValues`; the sentinels are jointly asserted by okhttp, ada `url_search_params::initialize`, and the test corpus.

### 10.3 Read API and Builder

`QueryParameters` exposes a read-only API; mutation is performed through a nested `Builder`.

#### 10.3.1 Read API

For a `QueryParameters` holding pairs `p[0..size)` where `p[i] = (nameAt(i), valueAt(i))`:

**[QUERY-9]** `size(): Int` MUST return the total pair count (counting duplicates).

**[QUERY-10]** `nameAt(index): String` MUST return the decoded name of the pair at `index`, and `valueAt(index): String?` MUST return its decoded value, where `null` denotes a pair that had no `=`. Both MUST throw an index-out-of-bounds error for `index < 0` or `index ≥ size()`.

**[QUERY-11]** `get(name): String?` MUST return the decoded value of the **first** pair whose decoded name equals `name` (case-sensitive). If no pair matches, or if the first matching pair has a `null` value, the result is `null`. (`get` therefore cannot distinguish "absent name" from "present name with no `=`"; callers needing that distinction MUST use `names()`/`nameAt`/`valueAt`.)

**[QUERY-12]** `getAll(name): List<String?>` MUST return the decoded values of **all** pairs whose decoded name equals `name`, in appearance order. The list MUST be read-only and MUST preserve `null` value entries (no `=`) distinctly from `""` entries. If no pair matches, the result MUST be the empty list.

**[QUERY-13]** `names(): Set<String>` MUST return the **distinct** decoded names in first-appearance (insertion) order. Implementations MUST back this by an insertion-ordered set (e.g. `LinkedHashSet`). It MUST NOT reorder names and MUST contain each name exactly once even when duplicated in the pair list.

**[QUERY-14]** `QueryParameters` MUST be a **snapshot value**: it is an immutable copy that is not a live view of any parent `Uri`/`Url`. Reading from it MUST NOT observe later mutations of any source, and obtaining it MUST NOT mutate the source.

#### 10.3.2 Builder

`QueryParameters.Builder` accumulates an ordered, duplicate-preserving pair list and produces an immutable `QueryParameters` via `build()`.

**[QUERY-15]** `add(name: String, value: String?): Builder` MUST append a new pair `(name, value)` to the end of the list. A `null` value MUST be retained as the no-`=` sentinel; an empty-string value MUST be retained as the `=`-with-empty-value sentinel. `add` MUST NOT deduplicate.

**[QUERY-16]** `set(name: String, value: String?): Builder` MUST perform **replace-first / remove-rest / keep-position**: it MUST replace the value of the **first** pair whose name equals `name` (leaving that pair at its original index), remove every **later** pair whose name equals `name`, and leave all other pairs untouched. If no pair has the given name, `set` MUST append `(name, value)` at the end (equivalent to `add`).

**[QUERY-17]** `removeAll(name: String): Builder` MUST remove every pair whose name equals `name`, preserving the relative order of the remaining pairs. If no pair matches, the list MUST be unchanged.

**[QUERY-18]** `sort(): Builder` MUST perform a **stable** sort of the pair list by **name only**, comparing names by Unicode **code point** sequence. The comparison MUST be surrogate-aware: a name MUST be compared as a sequence of code points (UTF-16 surrogate pairs combined into a single supplementary code point), so that supplementary-plane characters (`> U+FFFF`) sort after all Basic-Multilingual-Plane characters. Stability MUST be preserved: pairs with equal names MUST retain their pre-sort relative order (and thus their associated values' order). The comparison MUST NOT consider values.

> Note: ada `url_search_params::sort` uses a `stable_sort` and decodes UTF-8 into code points (combining surrogates) rather than comparing raw code units; kuri adopts code-point order per this section. WHATWG's own `sort()` is specified over code units — kuri deliberately specifies code-point order for surrogate correctness.

#### 10.3.3 Serialization back to a raw query

**[QUERY-19]** Building a `Uri`/`Url` from a `QueryParameters` MUST produce a **new** value; `QueryParameters` is never a mutable handle onto an existing `Uri`/`Url`. Re-serializing a `QueryParameters` to a raw query string MUST proceed as follows, joining pairs with `&`:

For each pair `(name, value)`, in order:
1. Emit `encodeName(name)`.
2. If `value` is non-`null`, emit `=` followed by `encodeValue(value)`. If `value` is `null`, emit no `=` and no value.

Where the per-component encode sets, applied octet-wise over the UTF-8 encoding of the string, are:

| Encoder | Encode set (in addition to the §5 query percent-encode set for the active profile) | Rationale |
| --- | --- | --- |
| `encodeName` | `&` `=` | a name terminates at the first `=` or `&`; both must be escaped to survive a round-trip |
| `encodeValue` | `&` | a value terminates only at `&`; `=` MAY remain literal because only the first `=` of a pair splits |

`+` MUST NOT be specially encoded by `encodeName`/`encodeValue` (it is a literal `+` in the generic query). Re-serializing the pairs derived from a raw query that contains no characters requiring escaping MUST reproduce that raw query exactly; in particular `===3===` MUST round-trip (`("", "==3===")` → `=` + `==3===` → `===3===`).

> Note: leaving `=` literal in `encodeValue` (unlike okhttp's `QUERY_COMPONENT` set, which escapes `=`) is what makes `?===3===` round-trip; `&` and the query set still protect every true delimiter.

### 10.4 `application/x-www-form-urlencoded`

The form dialect is a **separate** serializer/parser, not the generic query model. It is the only place where `+` means space.

**[QUERY-20]** The form **serializer** MUST encode a sequence of `(name, value)` pairs to an `application/x-www-form-urlencoded` string by joining pairs with `&`, emitting `name=value` per pair, and encoding each name and value octet (over the UTF-8 encoding of the string) as follows:

| Source octet | Output |
| --- | --- |
| `0x20` space | `+` |
| `0x2A *`, `0x2D -`, `0x2E .`, `0x30–0x39 0–9`, `0x41–0x5A A–Z`, `0x5F _`, `0x61–0x7A a–z` | the literal octet |
| any other octet | `%XX` (uppercase hex) |

Consequently: space → `+`; literal `+` (`0x2B`) → `%2B`; `&` (`0x26`) → `%26`; `=` (`0x3D`) → `%3D`. The set of unescaped octets is exactly the application/x-www-form-urlencoded percent-encode set of §5 (PCT), with space rendered as `+`.

**[QUERY-21]** The form **parser** MUST decode an `application/x-www-form-urlencoded` string as follows: split on `&`; **skip every empty segment (an empty byte sequence — as produced by a leading, trailing, or doubled `&` — MUST be discarded and MUST NOT yield a pair, per the WHATWG form parser);** within each remaining segment split on the first `=` (no `=` ⇒ empty value); then, in both name and value, replace every `+` (`0x2B`) with a space (`0x20`); then percent-decode (`%XX`, hex digits matched case-insensitively), interpreting the resulting octets as UTF-8. (The empty-value default also follows WHATWG: a segment with no `=` has the empty string — not `null` — as its value in the form dialect.)

**[QUERY-22]** The form codec MUST use UTF-8 for all name/value text. (kuri does not implement the legacy `_charset_` override.)

**[QUERY-23]** Plus-as-space MUST be confined to the form dialect ([QUERY-20]/[QUERY-21]). It MUST NOT be applied when deriving or serializing `QueryParameters` from/to a generic raw query (§10.2–§10.3), where `+` is always a literal `+`. Implementations MUST NOT silently apply the form parser to a generic query string.

> Note: ktor explicitly warns that its url-encoded parser "shouldn't be used for urlencoded forms because of `+`" and gates plus-as-space behind a `plusIsSpace` flag; kuri makes the two codecs separate types so the dialect can never be confused. The serializer matches ada `url_search_params::to_string` (form percent-encode set + space→`+`).

### 10.5 Resource bound on pair parsing

**[QUERY-24]** Deriving `QueryParameters` (§10.2) and parsing form input (§10.4) MUST enforce a maximum parsed-pair count. The default limit MUST be **1000** pairs and SHOULD be configurable. A query or form body whose pair count would exceed the limit MUST fail with a `UriParseError` (the failure is surfaced as `ParseResult.Err`; see §12, ERR — this bound is the `QueryPairs` `ResourceLimit` whose canonical registry entry is fixed there). Implementations MUST NOT silently truncate the input at the limit and discard the remainder.

> Note: the 1000-pair default matches ktor's `parseUrlEncodedParameters(limit = 1000)` bound; kuri tightens it to a hard failure rather than ktor's silent `split(limit=…)` truncation, per the "limits on everything" rule. The companion input-length and percent-decode bounds, and the canonical `ResourceLimit` registry, are defined in §12.

## 11. Normalization, Serialization & Equivalence

This section specifies how the two public value types transform their stored components into canonical form (normalization), how components are recomposed into a string (serialization), when two values are equal, and the guaranteed stability of the parse/serialize/build/resolve cycle. Throughout, "stored components" means the immutable component fields defined in §3 (`scheme`, `userInfo`, `Host?`, `port: Int?`, path segments, `query: String?`, `fragment: String?`), and "serialization" means the canonical string produced by §11.2.

The governing distinction is the profile contract:

- In the `Url` profile, **all** normalizations in §11.1 are applied EAGERLY: they are performed during parsing/building, are baked into the stored components (or applied deterministically at serialize time), and a `Url` therefore has exactly one canonical form. There is no "unnormalized `Url`".
- In the `Uri` profile, the stored components PRESERVE the parsed input. Normalizations are OPT-IN, exposed as composable operations on `normalize()` mapped to RFC 3986 §6.2, and are never applied implicitly. A `Uri` therefore has two relevant forms: its *canonical-but-unnormalized* serialization (faithful recomposition of preserved components) and its *normalized* serialization (after the requested §6.2 operations).

Note: derived from the two-profile design; cf. RFC 3986 §5.3/§6 and the WHATWG URL serializer.

### 11.1 Normalization

**[NORM-1]** In the `Url` profile, the operations marked "always" in the table below MUST be applied so that every `Url` value is fully canonical at all times; an implementation MUST NOT expose a `Url` whose serialization differs from the result of applying these operations. (This eager, always-on canonicalization is a sanctioned `Url`-profile deviation from RFC 3986, whose normalization is optional — see Appendix B [DEV-13].)

**[NORM-2]** In the `Uri` profile, an implementation MUST NOT apply any operation in the table below implicitly during parsing or building. Each operation MUST be available only through an explicit, individually selectable `normalize()` step, and the result MUST be a new `Uri` value (the receiver is unchanged, per the immutability contract of §3).

**[NORM-3]** The `Uri` `normalize()` operations MUST be composable and order-independent in their observable result: applying any subset in any order MUST yield the same serialization as applying that same subset in the canonical order *scheme-case → host-case/IDNA → percent-triplet-case → unreserved-decoding → dot-segment-removal → default-port-elision*. An implementation MAY internally fix this order; it MUST NOT expose an ordering by which two selections of the same subset produce different output.

The normalization matrix (each row: operation; `Url` behaviour; `Uri` behaviour):

| # | Operation | `Url` profile | `Uri` profile | Detail |
|---|---|---|---|---|
| **[NORM-4]** | Scheme case | Always lowercased | Always lowercased | The scheme is case-insensitive (RFC 3986 §3.1). Lowercasing is safe to apply unconditionally in both profiles; it is the one normalization the `Uri` profile performs without opt-in. |
| **[NORM-5]** | Host case / IDNA | Always: the full host pipeline (§7) runs, ASCII reg-name letters are lowercased, and IDNA ToASCII (§7, UTS-46) is applied | Opt-in: when selected, ASCII letters of a `RegName` are lowercased and (if separately selected) IDNA is applied. Otherwise the host is preserved verbatim | The host is case-insensitive, but RFC normalization is OPTIONAL, so the `Uri` profile defers it. An `Ipv4`/`Ipv6` host is already canonical from §7 and is unaffected. |
| **[NORM-6]** | Percent-triplet **case** | Existing triplets are NOT re-cased: the `Url` profile preserves the case of every `%XY` triplet already present in the input verbatim (matching the WHATWG parser, which never recanonicalizes existing triplets — the `%` and its two hex digits are in no percent-encode set). Only triplets the implementation itself emits while percent-encoding use uppercase hex ([PCT-19]); thus input `%2f` survives as `%2f`, not `%2F` | Opt-in; when selected, every `%XY` triplet is uppercased on output | The hexadecimal digits of a percent-encoding are case-insensitive (RFC 3986 §6.2.2.1). In the `Url` profile this normalization is NOT applied to pre-existing triplets per WHATWG. |
| **[NORM-7]** | Percent triplets in host MUST NOT be lowercased | Existing triplets are preserved verbatim (case unchanged); a `Url` reg-name contains no raw triplets after IDNA, but any that survive in an `Opaque` host obey [NORM-6] (preservation of existing-triplet case) | When host-case normalization ([NORM-5]) lowercases reg-name letters, it MUST NOT lowercase the hex digits of percent triplets within the host; those follow [NORM-6] (uppercase) instead | A naive "lowercase the whole host string" is incorrect: it corrupts `%XY` triplets. Letters that are part of a triplet are excluded from case folding. |
| **[NORM-8]** | Decode unreserved-but-encoded octets (`%41`→`A`) | Not performed: the `Url` profile does NOT decode existing percent triplets in path, query, or fragment | Opt-in (RFC 3986 §6.2.2.2): when selected, every triplet whose decoded octet is in the `unreserved` set (`ALPHA` / `DIGIT` / `-` / `.` / `_` / `~`) is replaced by that octet | The WHATWG engine deliberately preserves already-present triplets rather than decode-then-recanonicalize; the `Url` profile matches that. Only the `Uri` profile's opt-in decoding rewrites them, and only for the `unreserved` set — reserved and other octets are left encoded. |
| **[NORM-9]** | Dot-segment removal | Always: the `remove_dot_segments` algorithm (§9) is applied to the encoded path, including the percent-encoded forms `%2e`/`%2E` of `.` and `..` | Opt-in (RFC 3986 §6.2.2.3): when selected, `remove_dot_segments` (RFC 3986 §5.2.4) is applied to the encoded path. RFC §5.2.4 operates only on the literal complete path segments `.` and `..` and does not percent-decode, so `%2e`/`%2E` forms are NOT treated as dot-segments in the `Uri` profile | Removal operates on the **encoded** path. In the `Url` profile it MUST also recognise the `%2e`/`%2E` forms (a WHATWG behaviour that defeats percent-encoded traversal smuggling); in the `Uri` profile RFC 3986 §5.2.4 recognises only the literal `.`/`..` complete path segments and MUST NOT treat `%2e`/`%2E` as a dot-segment. Note: per §9, literal `.`/`..` introduced through segment-builder APIs are encoded to `%2E` and are not subject to this collapse. |
| **[NORM-10]** | Default-port elision | Always at serialize time: when `effectivePort` equals the scheme default for a special scheme (§6), the port is omitted from the serialization | Opt-in: when selected, a `port` equal to a known scheme default is omitted from the serialization. Otherwise `port: Int?` is preserved exactly, including a port equal to the default | The stored `port: Int?` distinction ("present" vs "absent") is retained in both profiles for strict round-tripping; elision is a serialization-time choice, never a mutation of the stored value. |
| **[NORM-11]** | Empty special path → `/` | Always: in the `Url` profile, if the host is non-null, the path is empty, and the scheme is special, the serialized path is `/` | Not applicable: the `Uri` profile never synthesises a path segment | A `Url` with an authority and an empty path always serializes with a single `/` path. |
| **[NORM-12]** | Backslash → slash | Always for special schemes: U+005C (`\`) occurring where a path/authority separator is expected is treated as U+002F (`/`) during parsing (§8) and never reappears in output | Never: a literal `\` in a path or other component is percent-encoded as `%5C` per the component encode set (§5); it is not rewritten to `/` | Backslash rewriting is a WHATWG special-scheme behaviour only. |
| **[NORM-13]** | `+` as space | Form-layer only: `+`↔space conversion belongs exclusively to `application/x-www-form-urlencoded` decoding/encoding (§10) and MUST NOT be applied to a generic query string in either profile | Same: form-layer only | A `+` in a generic query component is a literal `+`; it is neither produced from nor decoded to a space by serialization or normalization. The conversion exists only in `QueryParameters` form encoding (§10). |

**[NORM-14]** Normalization MUST NOT alter the meaning-bearing distinctions of the data model: it MUST NOT change a null component to an empty component or vice versa (a null `query` stays null; `?` with an empty query stays present-but-empty), and it MUST NOT remove an empty password that is distinct from an absent password (§3). The only structural elisions permitted are the default-port elision ([NORM-10]) and the special-path synthesis ([NORM-11]), both defined above.

### 11.2 Serialization

Serialization recomposes the stored components into the canonical string returned by `toString()` and backing equality (§11.3). The algorithm is common to both profiles; the profiles differ only in *which* components reach this step (a `Url`'s components are already normalized per §11.1; a `Uri`'s are as preserved or as explicitly normalized).

**[NORM-15]** An implementation MUST serialize a value by the following recomposition, in this exact order (cf. RFC 3986 §5.3 and the WHATWG URL serializer):

1. If `scheme` is non-null, append `scheme`, then `:`.
2. **Authority.** If the value has an authority (i.e. `Host?` is non-null, including `Host.Empty`), append `//`, then the serialized authority defined by [NORM-16]. If the value has no authority (`Host` is null), append nothing here — but apply the leading-`/.` guard of [NORM-18].
3. Append the serialized path (the encoded path; for a `Url` with authority and empty path, this is `/` per [NORM-11]).
4. If `query` is non-null, append `?`, then `query`.
5. If `fragment` is non-null, append `#`, then `fragment`.

**[NORM-16]** The authority MUST be serialized as `[ userinfo "@" ] host [ ":" port ]`, where:

- **userinfo** is emitted only if `user` is non-null OR `password` is non-null. When emitted, it is `user` (empty string if `user` is null but `password` is non-null), and if `password` is non-null, followed by `:` then `password`. The trailing `@` is appended only when userinfo is emitted. An absent password (null) emits no `:`; an empty password (`""`) emits `:` followed by nothing.
- **host** is the serialization of the `Host` per §7 (a bracketed `[…]` form for `Ipv6`/`IpFuture`; the empty string for `Host.Empty`; the reg-name/`Opaque`/`Ipv4` text otherwise). Brackets are produced at serialize time and are not part of the stored host value.
- **port** is emitted only if `port` is non-null and not elided by [NORM-10]; when emitted it is `:` followed by the decimal port.

**[NORM-30]** In the `Url` profile, userinfo serialization MUST follow the WHATWG URL serializer rather than the null/empty rule of [NORM-16]: credentials are emitted only if the URL *includes credentials* (its `user` OR its `password` is a non-empty string, per [MODEL-48]), and the password portion (`:` + password) is emitted only if the password is a non-empty string. Consequently a `Url` whose `user` and `password` are both empty emits no `@`, and a `Url` with a non-empty user but empty password serializes as `user@host` (no trailing `:`). The null-vs-empty password distinction of [MODEL-13]/§3 is a `Uri`-profile feature only; a `Url` has no such distinction (its credentials are always strings, possibly empty). A `Url` value that *cannot have a username/password/port* ([MODEL-47]) carries none of these components, so they never reach this step.

**[NORM-31]** The serialization algorithm SHOULD accept an optional `excludeFragment` boolean (default `false`); when `true`, step 5 of [NORM-15] (append `#` + fragment) is skipped. This mirrors the WHATWG URL serializer's `exclude fragment` parameter and underpins a fragment-insensitive equality (WHATWG *URL equivalence* with *exclude fragments* true). Default equality ([NORM-20]) uses `excludeFragment = false`, so the fragment participates in `equals`/`hashCode`.

**[NORM-17]** An implementation MUST NOT emit `//` unless the value has an authority (a non-null `Host`). A path that begins with `//` while the value has no authority is handled solely by [NORM-18]; it MUST NOT cause `//` to be emitted as an authority marker.

**[NORM-18]** Leading-`/.` guard. When the value has no authority (`Host` is null), `scheme` may be present, and the serialized path begins with `//` (its first segment is empty and at least one more segment follows), the implementation MUST prepend `/.` to the path before appending it, so that the recomposed string does not re-parse with a spurious authority. This guard MUST NOT be applied to a value that has an authority, and MUST NOT be applied to an opaque/`Opaque`-host or opaque-path `Uri` whose path is not hierarchical.

The `Uri`-profile serializer applies this same leading-`/.` guard AND the RFC 3986 §4.2 `./` dot-segment guard for a scheme-less, authority-less path whose first segment contains a colon (which would otherwise re-parse as a scheme), so every serialized `Uri` — including the output of `normalized()`/`resolve()`, whose `removeDotSegments` can strip a builder-applied guard and leave the stored path in exactly such an unsafe state — re-parses to the same structure.

Note: the leading-`/.` trick corresponds to the WHATWG serializer step that prepends `/.` when the host is null and the path would otherwise begin with two slashes; cf. RFC 3986 §5.3.

### 11.3 Equality & hashCode contract

**[NORM-19]** Equality MUST be a pure function of the in-memory value. An implementation MUST NOT perform DNS resolution, host-name canonicalization via the network, or any other I/O during `equals` or `hashCode`. (This forbids the `java.net.URL` anti-pattern, whose `equals` performs blocking DNS lookups.)

**[NORM-20]** A `Url` MUST define `equals` as equality of its canonical href: two `Url` values are equal if and only if their §11.2 serializations (each already fully normalized per §11.1) are equal as code-point sequences. `hashCode` MUST be derived from that same canonical serialization. Because a `Url` is always canonical, there is exactly one equality notion for `Url`.

**[NORM-21]** A `Uri` MUST define structural `equals`/`hashCode` over its **canonical-but-unnormalized** serialization: the §11.2 recomposition of its preserved components, with no §11.1 opt-in operation applied beyond the always-on scheme lowercasing ([NORM-4]). Two `Uri` values are structurally equal if and only if these serializations are equal as code-point sequences, and `hashCode` MUST be derived from that same string. Consequently, two `Uri` values that differ only in percent-triplet case, host case, or dot-segments are NOT structurally equal.

**[NORM-22]** A `Uri` MUST additionally provide a normalization-aware comparison `normalizedEquals(other)` and a `normalized()` accessor such that `a.normalizedEquals(b)` is equivalent to `a.normalized() == b.normalized()`, where `normalized()` applies the full set of RFC 3986 §6.2 operations defined as opt-in in §11.1 ([NORM-5]–[NORM-10], plus [NORM-4]). `normalizedEquals` is reflexive, symmetric, and transitive, and MUST also obey [NORM-19] (no I/O). An implementation MUST clearly distinguish the two notions in its API surface so a caller cannot mistake structural equality for semantic equivalence.

**[NORM-23]** Both `Url` and `Uri` MUST be safe to use as keys in `Map`/`Set` and across hash-based collections: `equals`/`hashCode` MUST be consistent (equal values have equal hash codes), stable for the lifetime of the value (the value is immutable, so the canonical string and its hash MAY be computed once and cached), and free of side effects. For a `Uri`, the default `equals`/`hashCode` used by collections is the structural pair of [NORM-21]; `normalizedEquals` is an explicit call and MUST NOT silently back collection membership.

### 11.4 Round-trip & idempotency

Let `serialize` be §11.2, `parse_P` be parsing under profile `P` (§8), and `normalize` be the `Uri` opt-in operations of §11.1.

**[NORM-24]** Parse∘serialize stability. For any value `v` of either type, `parse_P(serialize(v))` MUST succeed and produce a value whose serialization equals `serialize(v)`. That is, serializing a parsed value and re-parsing it is a fixed point: `serialize(parse_P(serialize(v))) == serialize(v)`.

**[NORM-25]** Serialization idempotency. `serialize` MUST be idempotent in the sense that re-serializing an already-serialized-and-reparsed value yields the identical string; for a `Url`, since parsing is canonicalizing, `parse(s)` for any `Url`-serialization `s` of a value MUST satisfy `serialize(parse(s)) == s`.

**[NORM-26]** Normalization idempotency. For a `Uri`, applying the same `normalize()` selection twice MUST equal applying it once: `normalize(normalize(v)) == normalize(v)` for every selectable subset, and `normalized(normalized(v)) == normalized(v)`. A `Url`, being eagerly normalized, satisfies this trivially (it has no opt-in normalization step).

**[NORM-27]** Builder round-trip. For any value `v`, `v.newBuilder().build()` MUST produce a value equal to `v` under that type's default equality (§11.3) and with an identical serialization; the pre-filled builder MUST reproduce every stored component, including null-vs-empty distinctions and an empty-but-present password, without re-normalizing a `Uri`'s preserved components.

**[NORM-28]** Empty-reference resolution stability. For any value `v`, resolving the empty relative reference against it, `v.resolve("")`, MUST produce a value whose serialization equals `serialize(v)` after the reference-resolution normalization mandated by the value's profile. In the `Url` profile this is `serialize(v)` unchanged (the result is already canonical); in the `Uri` profile, `resolve("")` MUST NOT apply any opt-in §11.1 normalization beyond what reference resolution itself requires (§9), so a preserved `Uri` round-trips through `resolve("")` unchanged save for the dot-segment removal that §9 reference resolution performs on the target path.

### 11.5 `toUri()` / `toUrl()` bridges

The two public types convert to one another. The conversions are asymmetric because the `Uri` model is a strict superset of what the `Url` model can represent.

**[NORM-29]** `Url.toUri()` MUST be total (it always succeeds) and near-lossless: it produces a `Uri` whose stored components are exactly the canonical components of the `Url` (scheme, userinfo, host, port, path, query, fragment), so that `url.toUri()` serializes to the same string as `url`. The resulting `Uri` is in preserve mode: it carries the already-canonical component values but applies no further `Uri` normalization, and its structural equality (§11.3) is over that canonical serialization. The only permitted difference is representational, never textual: a `Host` variant that exists only in one profile (e.g. an `Opaque` host) MUST be carried across unchanged so the serialization is identical.

**[NORM-30]** `Uri.toUrl()` MUST be fallible and MUST return `ParseResult<Url>` (`Ok`/`Err` with `UriParseError`), never a throwing or null-punning API on the failure path. The conversion MUST apply the full `Url` profile (special-scheme detection §6, the host pipeline §7 including IDNA, eager canonicalization §11.1). It MUST return `Err` when the `Uri` cannot be a valid `Url`, including at least: a null/relative `scheme` (a `Url` always has a scheme); a host that fails the `Url` host pipeline (e.g. a reg-name that is not a valid IDNA/forbidden-host-code-point–free host, or an `IpFuture` host, which the `Url` model does not admit); a special scheme with a missing or empty required host where the `Url` model forbids it; or a port outside the permitted range. On success it returns `Ok(url)` where `url` is fully canonical per §11.1.

**[NORM-31]** Bridge consistency. For any `Url` value `u`, `u.toUri().toUrl()` MUST return `Ok(u')` with `serialize(u') == serialize(u)` (the round trip through `Uri` is value-preserving for anything that originated as a `Url`). The reverse round trip `someUri.toUrl()` followed by `.toUri()` is NOT required to reproduce the original `Uri` byte-for-byte, because `toUrl()` canonicalizes; an implementation MUST document that `Uri → Url → Uri` is canonicalizing, not preserving.

Beyond the two type-to-type bridges above, the `Uri` profile also exposes an RFC 3987 dialect bridge — the `Iri` conversion facility — so internationalized input has an explicit home without weakening strict `Uri` parsing (§1.1, [PCT-2]):

- `Iri.toUri(iri)` is **fallible** and returns `ParseResult<Uri>`. It applies the RFC 3987 §3.1 IRI-to-URI mapping (host via IDNA/UTS-46 ToASCII, every other component via UTF-8 percent-encoding of its non-ASCII octets), then validates and stores the fully-ASCII result through the unchanged strict `Uri` engine. It returns `Err` when the mapping cannot yield a valid URI — for example an IDNA failure in the host, or an expanded form that exceeds the input-length bound of §12.
- `Iri.toUnicode(uri)` is **total** and returns a best-effort display IRI (RFC 3987 §3.2): a reg-name host is rendered through IDNA ToUnicode and every other component has its non-ASCII UTF-8 triplet runs decoded, while ASCII triplets and non-UTF-8 runs are preserved so structure survives. The result is for presentation only and is NOT guaranteed to re-parse as the original `uri`.

### 11.6 Origin (`Url` profile)

The `Url` profile exposes an `origin` projection modelling the WHATWG URL Living Standard's "origin of a URL". Origin is defined for the `Url` profile only; it is a derived projection ([MODEL-34]), never stored authoritative state.

**[NORM-32]** In the `Url` profile, `origin` MUST return the ASCII serialization of the URL's WHATWG origin, computed as follows:

- **(a) Tuple origin.** For a special scheme other than `file` (i.e. `ftp`, `http`, `https`, `ws`, `wss`), the origin serializes as `scheme "://" host [ ":" port ]`. The `port` is emitted only when it is non-null (a default-elided port ([NORM-10]) therefore does not appear), and userinfo is never included.
- **(b) Blob unwrapping.** For the `blob` scheme, the origin is the origin of the URL obtained by parsing this URL's path (the URL-path serialization, an opaque path). When that inner URL parses and its scheme is `http`, `https`, or `file`, `origin` returns that inner URL's origin; otherwise (the inner path is not a URL, or its scheme is not one of those three) the origin is opaque.
- **(c) Opaque origin.** For the `file` scheme and for every non-special scheme, the origin is opaque. An opaque origin MUST serialize as the literal string `"null"`.

`origin` MUST NOT be relied upon to round-trip and is not part of the stored record; it is a pure function of the stored components ([MODEL-34]).

## 12. Error Handling, Validation & Resource Limits

This section defines how `kuri` reports failures, records non-fatal anomalies, and bounds resource consumption. It governs both profiles; where behaviour differs it is stated as "In the `Url` profile … In the `Uri` profile …". Every testable requirement is tagged **[ERR-n]** for reference from §13 (CONF).

The governing principle is that recoverable parse failures are *values*, not control flow. Programmer errors — violations of a method's documented contract — are a separate category and are the only failures that may use exceptions.

### 12.1 The `ParseResult<out T>` ADT

Parsing, reference resolution, and profile conversion return their outcome as a sealed two-case result rather than throwing.

```kotlin
public sealed interface ParseResult<out T> {
    public data class Ok<out T>(
        val value: T,
        val validationErrors: List<ValidationError> = emptyList(),
    ) : ParseResult<T>

    public data class Err(val error: UriParseError) : ParseResult<Nothing>
}
```

**[ERR-1]** Every parsing entry point that can fail recoverably (`Uri.parse(String): ParseResult<Uri>`, `Url.parse(String, base): ParseResult<Url>`, `resolve`, `toUrl`) MUST return a `ParseResult`. It MUST NOT throw to signal a recoverable parse failure (malformed input, forbidden code point, overflow, limit exceeded, etc.).

**[ERR-2]** A successful parse MUST be reported as exactly one `ParseResult.Ok` whose `value` is the produced `Uri`/`Url`. A failed parse MUST be reported as exactly one `ParseResult.Err` whose `error` is a single `UriParseError` describing the first fatal condition encountered (in input order; see [ERR-26]).

**[ERR-3]** When a parse succeeds, any non-fatal validation errors observed during parsing MUST be carried in `Ok.validationErrors`, in the order they were observed. When a parse fails, `Err` carries no validation-error list; the single `UriParseError` is the complete report.

**[ERR-4]** `ParseResult.Ok` and `ParseResult.Err` MUST be the only two cases. The hierarchy MUST be exhaustively matchable in a `when` without an `else` branch.

**[ERR-5]** The convenience entry points MUST be defined in terms of `ParseResult` as follows, and MUST NOT introduce divergent validation behaviour:
- `parseOrNull(input): T?` returns `value` for `Ok`, `null` for `Err`.
- The throwing `parse(input): T` (mirroring `java.net.URI`) returns `value` for `Ok` and MUST throw `IllegalArgumentException` for `Err`, with the `UriParseError` attached (as message and, where supported, as `cause`). This is the only sanctioned conversion of a recoverable parse failure into an exception, and it occurs only because the caller explicitly chose the throwing overload.
- `canParse(input): Boolean` returns `true` iff the corresponding `parse` would return `Ok`. It MUST NOT throw for malformed input and MUST NOT allocate the produced value.

**[ERR-6]** Programmer errors — calls that violate a method's documented precondition — MAY throw `IllegalStateException` or `IllegalArgumentException` and MUST NOT be reported through `ParseResult`. The following are programmer errors:
- `Url.Builder.build()` invoked with no scheme set, or with no host on a special non-`file` scheme — MUST throw `IllegalStateException`.
- A builder setter given a structurally impossible argument that the caller is contractually required to pre-validate (e.g. a non-numeric `port(Int)` cannot occur by typing; a `scheme(String)` containing a colon) — MUST throw `IllegalArgumentException`.

**[ERR-7]** Exceptions MUST NOT be used as normal control flow inside the parser. The parser MUST NOT catch its own thrown exceptions to implement branching; all internal failure paths produce a `UriParseError` value (see also [ERR-30]).

### 12.2 `UriParseError` — the fatal-error catalog

A fatal error aborts production of a value. Every variant carries enough context — at minimum a code-unit `at` offset into the original input, or an explanatory sub-value — for a caller to locate and explain the failure.

```kotlin
public sealed interface UriParseError {
    public data class InvalidScheme(val at: Int, val detail: String) : UriParseError
    public data object MissingScheme : UriParseError
    public data class InvalidAuthority(val at: Int, val detail: String) : UriParseError
    public data class InvalidHost(val host: String, val reason: HostError) : UriParseError
    public data class ForbiddenHostCodePoint(val codePoint: Int, val at: Int) : UriParseError
    public data class EmptyHost(val at: Int) : UriParseError
    public data class InvalidPort(val text: String, val at: Int) : UriParseError
    public data class InvalidPercentEncoding(val at: Int) : UriParseError
    public data class InputTooLong(val length: Long, val max: Long) : UriParseError
    public data class LimitExceeded(val limit: ResourceLimit, val observed: Long, val max: Long) : UriParseError
}

public enum class HostError {
    IdnaFailed,        // UTS-46 ToASCII/ToUnicode produced an error (see §7 HOST)
    Ipv4Overflow,      // a numeric host or octet exceeds its width-bounded maximum
    Ipv4NonNumeric,    // ends-in-number host with an invalid numeric part (Url profile)
    Ipv6Malformed,     // bad group count, misplaced "::", bad embedded IPv4, stray chars
    ZoneIdRejected,    // RFC 6874 "%25"-zone present and zone-id support not opted in
    LabelTooLong,      // a domain label exceeds the label limit (strict)
    HostTooLong,       // total host length exceeds the DNS limit (strict)
    EmptyLabel,        // empty label where not permitted (e.g. "a..b")
}
```

**[ERR-8]** Every `UriParseError` variant that denotes a position in the input MUST carry an `at` offset measured in UTF-16 code units into the *original* input string (before any tab/newline stripping or C0 trimming). `at` MUST satisfy `0 <= at <= input.length`.

**[ERR-9]** `InvalidScheme` MUST be produced when a scheme component is present but ill-formed (first character not ALPHA, or a subsequent character outside `ALPHA / DIGIT / "+" / "-" / "."`). `detail` MUST identify the offending condition. In the `Uri` profile a missing scheme on input that is not a valid relative reference MUST be reported as `MissingScheme`. In the `Url` profile, `Url.parse` with no scheme and no usable base MUST be reported as `MissingScheme`.

**[ERR-10]** `InvalidAuthority` MUST be produced for an authority that cannot be decomposed (e.g. an unterminated IPv6 literal `[` with no `]`, or userinfo/host structure that no profile rule accepts). Host-specific failures MUST instead be reported as `InvalidHost`.

**[ERR-11]** `InvalidHost` MUST carry the host substring as seen (post-strip, pre-IDNA) and a `HostError` discriminating the cause. The host pipeline failures enumerated in §7 (HOST) MUST map onto `HostError` as named above; an implementation MUST NOT collapse distinct causes into a single opaque value.

**[ERR-12]** `ForbiddenHostCodePoint` MUST be produced when a forbidden host code point (or, for a domain host, a forbidden-domain code point) is encountered, carrying the offending Unicode `codePoint` and its `at` offset. This variant is distinct from `InvalidHost` so that callers can report the exact code point.

**[ERR-13]** `EmptyHost` MUST be produced when a host is empty in a context that forbids an empty host. In the `Url` profile this applies to a special scheme other than `file`. In the `Uri` profile an empty host is permitted (RFC 3986 allows an empty authority) and MUST NOT produce `EmptyHost`.

**[ERR-14]** `InvalidPort` MUST be produced when a port is present and is not a sequence of ASCII digits, or when its numeric value exceeds 65535 (see [ERR-22]). `text` MUST be the port substring as seen.

**[ERR-15]** `InvalidPercentEncoding` MUST be produced only where a malformed percent sequence (`%` not followed by two ASCII hex digits) is *fatal*. By the reject-vs-normalize table (§12.5) this is fatal in `Uri` strict mode; in lenient parsing a malformed `%` is non-fatal and is recorded as a `ValidationError` instead (see [ERR-19]).

**[ERR-16]** `InputTooLong` MUST be produced when the input length exceeds the configured maximum (see [ERR-31]), carrying the observed `length` and the configured `max`. The length check MUST also be re-applied after percent-decoding/IDNA expansion (see [ERR-32]); the post-expansion failure MUST also be reported as `InputTooLong`.

**[ERR-17]** `LimitExceeded` MUST be produced when a configured resource bound other than total input length is exceeded (e.g. query-pair count). It carries which `ResourceLimit` was hit, the `observed` count, and the configured `max`. (`InputTooLong` is retained as a dedicated variant for the headline length bound; all other bounds use `LimitExceeded`.)

**[ERR-18]** The `UriParseError` hierarchy MUST be `sealed` and exhaustively matchable without an `else`. Adding a variant is a breaking API change governed by binary-compatibility-validation.

### 12.3 `ValidationError` — non-fatal anomalies

WHATWG parsing is a lenient repair process: it accepts and silently corrects inputs that a strict reader would reject, but it records each correction as a *validation error*. `kuri` preserves these so that strict or security-sensitive callers can inspect what was repaired, while lenient callers ignore them.

```kotlin
public data class ValidationError(
    val kind: ValidationErrorKind,
    val at: Int,
) {
    public val isFailure: Boolean   // true only for the WHATWG "validation error == failure" cases
}

public enum class ValidationErrorKind {
    TabOrNewlineRemoved,        // U+0009/000A/000D stripped from the input
    LeadingControlOrSpaceTrimmed,
    TrailingControlOrSpaceTrimmed,
    BackslashAsSlash,           // "\" interpreted as "/" in a special scheme
    MissingAuthoritySlashes,    // "//" run was not exactly two (e.g. "http:/host", "http:host")
    ExtraAuthoritySlashes,      // more than two leading slashes collapsed
    CredentialsInAuthority,     // userinfo present (advisory; WHATWG flags it)
    EmptyPasswordSegment,       // ":" present with empty password
    UnencodedSpaceInPath,
    MalformedPercentEncoding,   // "%" not followed by two hex digits (left literal)
    InvalidCodePointPercentEncoded,  // a code point outside the component set was encoded
    DefaultPortRemoved,
    EmptyHostForFileScheme,     // "file://" empty host normalized to localhost semantics
    FileSchemeWindowsDriveQuirk,
    UnexpectedHostSeparator,
}
```

**[ERR-19]** In the `Url` profile, each of the following repairs MUST be recorded as a `ValidationError` with the indicated `kind`, at the offset where it occurred, while parsing continues and (absent a fatal condition) yields `Ok`:
- stripping a tab/LF/CR from anywhere in the input → `TabOrNewlineRemoved`;
- trimming leading C0-or-space → `LeadingControlOrSpaceTrimmed`; trailing → `TrailingControlOrSpaceTrimmed`;
- treating `\` as `/` under a special scheme → `BackslashAsSlash`;
- an authority-introducing slash run that is not exactly `//` → `MissingAuthoritySlashes` or `ExtraAuthoritySlashes`;
- a malformed `%` left literal → `MalformedPercentEncoding`;
- elision of a default port → `DefaultPortRemoved`.

**[ERR-20]** A `ValidationError` MUST NOT alter the produced value relative to an otherwise identical run; it is observational only. Two parses of inputs that differ only in repaired anomalies MAY produce equal values while differing in `Ok.validationErrors`.

**[ERR-21]** In the `Uri` profile (default, preserve-by-RFC), the conditions in [ERR-19] are not repairs but either rejections or verbatim preservation per §12.5. Where the `Uri` default preserves a condition that WHATWG would flag (e.g. a literal backslash, encoded as `%5C`), the implementation MAY still record an advisory `ValidationError`, but it MUST NOT do so in a way that changes the value or its serialization.

### 12.4 Strict vs lenient semantics per profile

Strictness is a property of the profile, with one orthogonal escalation knob. It is not a free-floating mode that can turn `Url` into `Uri` or vice versa.

**[ERR-22]** The baseline strictness of each profile is fixed:
- The `Url` profile is **lenient by specification**: it strips tab/newline, trims leading/trailing C0+space, repairs slash runs and backslashes, and records validation errors, per WHATWG. It only fails for the conditions WHATWG defines as failures (e.g. forbidden host code point, empty host on a special non-`file` scheme, IPv4 overflow, port > 65535).
- The `Uri` profile is **structurally strict but content-non-validating**: it splits at the RFC 3986 structural delimiters (the Appendix B reference regex) without re-validating each component against the full grammar. It rejects the structural failures it detects (an ill-formed scheme, an invalid or overflowing port, a rejected host) and, in the path/query/fragment, a raw C0/DEL control or a malformed `%xx` triplet; other ASCII the grammar forbids there (space, `"`, `<`, `>`, `` ` ``, `{`, `}`) is preserved verbatim, not rejected. It is *preserve-by-default* for the input it admits (no eager normalization).

**[ERR-23]** An optional `strict: Boolean` flag (default `false`) MAY be supplied to either profile's parse configuration. When `strict = true`, advisory checks that are otherwise non-fatal MUST be escalated to a fatal `UriParseError` (`Err`) at the first such condition. The escalated checks are exactly those listed in the rightmost column of the §12.5 table, including:
- DNS length overflow (label > 63, host > 253) → `InvalidHost(HostError.LabelTooLong | HostTooLong)`;
- in the `Uri` profile, a malformed `%xx` → `InvalidPercentEncoding`;
- in the `Uri` profile, port `0` → `InvalidPort`;
- any condition recorded as a non-failure `ValidationError` that the profile's strict column marks "reject".

**[ERR-24]** With `strict = false`, the advisory checks in [ERR-23] MUST NOT be fatal; DNS-length violations and (in `Url`) malformed percent sequences MUST remain non-fatal (recorded, then preserved/repaired). An implementation MUST NOT silently apply strict semantics; the escalation is opt-in and explicit.

**[ERR-25]** Setting `strict = true` MUST NOT *relax* any check that is fatal in the baseline profile, and MUST NOT change the produced value for inputs that contain none of the escalated conditions (it only converts certain `Ok`-with-validation-errors outcomes into `Err`).

### 12.5 Reject-vs-normalize decision table

For each listed condition, the table gives the mandated behaviour. "strip"/"trim"/"→/"/"elide" denote a non-fatal repair recorded per §12.3; "preserve" denotes verbatim retention; "encode" denotes percent-encoding; "reject" denotes a fatal `Err`.

**[ERR-26]** An implementation MUST behave exactly as this table prescribes for each condition and profile. When multiple fatal conditions are present, the `Err` reported MUST be the one occurring earliest in input order (smallest `at`).

| # | Condition | `Url` (WHATWG) | `Uri` default (lenient-preserve) | `Uri` strict |
|---|---|---|---|---|
| a | Embedded tab/LF/CR | strip (record `TabOrNewlineRemoved`) | reject (`InvalidAuthority`/component error; RFC admits no control) | reject |
| b | Leading/trailing C0+space | trim (record) | preserve as part of component, percent-encode where the component set requires | reject |
| c | Backslash in special scheme | `\` → `/` (record `BackslashAsSlash`) | encode as `%5C` (not a delimiter) | reject (not in grammar slot) |
| d | IPv4 numeric/octet overflow (`192.168.0.257`, width-aware) | reject `InvalidHost(Ipv4Overflow)` | treat host as reg-name (no shorthand IPv4 in `Uri`) | reject `InvalidHost(Ipv4Overflow)` if it parses as an IPv4 literal, else reg-name validation |
| e | Port > 65535 | reject `InvalidPort` | reject `InvalidPort` | reject `InvalidPort` |
| f | Port `0` | allow (WHATWG permits) | reject `InvalidPort` | reject `InvalidPort` |
| g | Empty host, special scheme ≠ `file` | reject `EmptyHost` | (no special-scheme concept) allow empty authority | allow per RFC |
| h | Empty host, `file` scheme | allow (localhost semantics; record `EmptyHostForFileScheme`) | allow empty authority | allow |
| i | Malformed `%xx` | leave literal (record `MalformedPercentEncoding`); `%`-escape to `%25` on `toUri()` export | preserve literal | reject `InvalidPercentEncoding` |
| j | IPv6 zone id (`[fe80::1%25eth0]`) | reject `InvalidHost(ZoneIdRejected)` unless zone-id support opted in | reject `InvalidHost(ZoneIdRejected)` unless opted in | reject unless opted in |
| k | DNS length: label > 63 / host > 253 | advisory (non-fatal; value produced) | advisory | reject `InvalidHost(LabelTooLong/HostTooLong)` |
| l | Forbidden host code point | reject `ForbiddenHostCodePoint` | reject `ForbiddenHostCodePoint` (RFC reg-name set) | reject |
| m | Missing authority slashes (`http:/host`, `http:host`) | repair to `//` (record `MissingAuthoritySlashes`) | exactly `//` required to introduce an authority; otherwise the text is a path, not an authority | same as default |
| n | Unencoded space in path/query/fragment | percent-encode per component set (record where applicable) | preserve verbatim (non-validating splitter; not re-encoded, not rejected) | preserve verbatim (non-validating splitter; not re-encoded, not rejected) — strict escalation adds no component-grammar validation |

**[ERR-27]** Row (d): in the `Url` profile, "ends in a number" host detection and width-aware overflow MUST be applied per §7; an overflowing numeric host is fatal, not reinterpreted as a reg-name. In the `Uri` profile there is no shorthand IPv4, so `192.168.0.257` is parsed as a reg-name and is fatal only if it contains a code point outside the reg-name set.

**[ERR-28]** Row (i): the `toUri()` bridge MUST re-escape an unrepairable literal `%` as `%25` so the exported `Uri` is RFC-valid; this transformation is part of conversion, not of the original `Url` value, whose `encodedPath`/`encodedQuery` retain the literal `%`.

### 12.6 Resource limits / DoS bounds

Every unbounded dimension of the input is capped. Each limit has a documented default and MUST be configurable through the parse configuration. The full set is enumerated by `ResourceLimit`. This subsection is the canonical home for the resource-limit registry; other sections (e.g. the query-pair bound of §10.5) reference these limits rather than redefining them.

```kotlin
public enum class ResourceLimit {
    InputLength,       // default 64 KiB (65_536) UTF-16 code units
    ExpandedLength,    // default equals InputLength; re-checked post percent/IDNA expansion
    QueryPairs,        // default 1000
    PathSegments,      // default 10_000
    HostLabelLength,   // 63 (DNS); fixed by RFC 5890, not lowered below 63
    HostTotalLength,   // 253 (DNS)
    PortMax,           // 65_535
    ResolutionDepth,   // default 256 (bound on reference-resolution / dot-segment work)
}
```

**[ERR-29]** Each limit MUST be enforced and MUST have the documented default value above. Exceeding a limit MUST produce `InputTooLong` (for `InputLength`/`ExpandedLength`) or `LimitExceeded(limit, observed, max)` (for all others), never an unbounded allocation, a `StackOverflowError`, or a hang. Specifically:

**[ERR-30]** **[InputLength]** The original input length MUST be checked before substantive parsing; if it exceeds `InputLength`, parsing MUST short-circuit with `InputTooLong` without scanning the whole input beyond what is needed to establish the overflow.

**[ERR-31]** **[ExpandedLength]** Because percent-decoding and IDNA ToUnicode/ToASCII can lengthen a string, the total serialized length MUST be re-checked after expansion; an expansion that exceeds `ExpandedLength` MUST produce `InputTooLong` with the post-expansion `length`.

**[ERR-32]** **[QueryPairs]** When materializing `QueryParameters`, the number of name/value pairs MUST be capped at `QueryPairs` (default 1000). Exceeding it MUST produce `LimitExceeded(QueryPairs, …)`. The raw `query`/`encodedQuery` string is not split and is bounded only by `InputLength`. (This is the canonical registry entry for the query-pair bound referenced by [QUERY-24].)

**[ERR-33]** **[PathSegments / ResolutionDepth]** Path-segment splitting and dot-segment removal MUST be bounded: segment count by `PathSegments`, and reference-resolution work by `ResolutionDepth`. Resolution and dot-segment removal MUST run in bounded time and MUST NOT recurse without a fixed depth bound; a `..` sequence that would underflow the path MUST clamp at the root (per §9) rather than loop.

**[ERR-34]** **[HostLabelLength / HostTotalLength]** Label length (≤ 63) and total host length (≤ 253) MUST be computed during host parsing. By [ERR-22]/[ERR-23] they are advisory by default and fatal under `strict`. These limits MUST NOT be raised by configuration above the DNS-defined maxima for a host that is to be treated as a domain.

**[ERR-35]** **[PortMax]** A port value MUST be rejected when it exceeds 65535 (`PortMax`), in both profiles and at all strictness levels (table row e).

**[ERR-36]** Limit defaults MUST be documented in the public API and MUST be overridable per parse via configuration. Lowering a limit MUST NOT change the outcome for inputs already within the lower bound; raising one MUST NOT lift the fixed protocol maxima in [ERR-34]/[ERR-35].

### 12.7 Foreign-exception translation boundary

The parser is built from internal routines (host pipeline, IDNA/UTS-46 mapper, percent codec, numeric parsing) and may, at its boundary, touch platform facilities. None of their exceptions may reach a `kuri` caller as themselves.

**[ERR-37]** Any exception thrown by an underlying routine invoked during parsing — including `NumberFormatException`, `IllegalArgumentException` from a mapping/IDNA step, `IndexOutOfBoundsException`, character-decoding errors, or any platform-thrown `RuntimeException` — MUST be caught at the parser boundary and translated into the most specific applicable `UriParseError`, then returned as `ParseResult.Err`. The original exception MUST NOT propagate out of a `ParseResult`-returning entry point.

**[ERR-38]** The translation MUST preserve diagnostic context: the produced `UriParseError` MUST carry the relevant `at` offset (or `HostError`) for the component being processed when the foreign failure occurred, and the original exception SHOULD be retained as the `cause` where the result is later converted to an exception via the throwing `parse` overload ([ERR-5]).

**[ERR-39]** The boundary catch MUST be narrow: it MUST be placed around the specific foreign call (e.g. the IDNA invocation, a `String`→`Int` port conversion), not as a blanket `catch (Throwable)` around the whole parse. `Error` subtypes that indicate genuine VM failure (`OutOfMemoryError`, `StackOverflowError`, `VirtualMachineError`) MUST NOT be swallowed or translated; they MUST propagate. The resource limits in §12.6 exist precisely so that well-formed-but-hostile input never reaches those conditions.

**[ERR-40]** The translation boundary MUST be deterministic and side-effect-free: translating a foreign exception MUST NOT retry, MUST NOT consult external state (DNS, network, filesystem, locale-dependent behaviour beyond the bundled UTS-46 table), and MUST yield the same `UriParseError` for the same input every time.

> Note: the strict/lenient split, the validation-error concept, and the reject-vs-normalize rows are derived from WHATWG URL §4.4 and RFC 3986 §3/§6; the IPv4 overflow and forbidden-host-code-point behaviour mirror ada (`parser.cpp`, `unicode.cpp`); the query-pair and input-length caps follow the ktor and ada DoS bounds. The normative text above stands independently of these sources.

## 13. Conformance Requirements & Test Corpora

This section defines what it means for an implementation to conform to this specification. It restates the conformance classes (§13.1), enumerates the external test corpora that each conformance class is gated on (§13.2), promotes the edge-case master checklist into numbered, individually testable normative requirements (§13.3), defines the policy for documented deviations and opt-in features (§13.4), and fixes the versioning and stability rules for the specification itself (§13.5).

Every requirement in this section carries an identifier of the form **[CONF-n]**. The conformance class of a requirement (URI-conformant, URL-conformant, or both) is stated inline; where a requirement applies only to one profile it is qualified "In the `Url` profile …" / "In the `Uri` profile …".

### 13.1 Conformance classes

This specification defines three conformance classes (introduced in §1.3). An implementation declares which class or classes it satisfies.

| Class | Profile(s) covered | Backing model |
| --- | --- | --- |
| **URI-conformant** | `ParseProfile.URI` only | RFC 3986 / RFC 3987 generic URI, PRESERVE-by-default |
| **URL-conformant** | `ParseProfile.URL` only | WHATWG URL Living Standard, eager canonicalization |
| **fully-conformant** | both `URI` and `URL` | the shared engine under both profiles |

- **[CONF-1]** A **URI-conformant** implementation MUST satisfy every requirement in this specification whose conformance class includes the `Uri` profile, evaluated under `ParseProfile.URI`. It MUST expose the `Uri` public value type.
- **[CONF-2]** A **URL-conformant** implementation MUST satisfy every requirement whose conformance class includes the `Url` profile, evaluated under `ParseProfile.URL`. It MUST expose the `Url` public value type.
- **[CONF-3]** A **fully-conformant** implementation MUST satisfy [CONF-1] and [CONF-2] simultaneously over a single shared engine parameterized by `ParseProfile`, and MUST expose both `Uri` and `Url`. v1 of this library targets the fully-conformant class.
- **[CONF-4]** Requirements stated with **MUST**/**MUST NOT**/**REQUIRED**/**SHALL** are mandatory for the applicable conformance class; a conforming implementation MUST NOT violate them. Requirements stated with **SHOULD**/**SHOULD NOT**/**RECOMMENDED** are strong recommendations: an implementation MAY deviate only with a documented entry in the `KNOWN_FAILURES` register (§13.4). Behaviour stated with **MAY**/**OPTIONAL** is at implementer discretion and is not gated.
- **[CONF-5]** Profile behaviour MUST NOT cross-contaminate: parsing under `ParseProfile.URI` MUST NOT apply any `Url`-only transformation (tab/newline stripping, backslash rewriting, special-scheme slash coalescing, IPv4 shorthand, default-port elision, eager host canonicalization), and parsing under `ParseProfile.URL` MUST apply all of them for special schemes. A single input parsed under both profiles MUST yield the profile-defined results independently.

### 13.2 Required external test corpora

A conforming implementation MUST vendor the corpora below at pinned, recorded revisions and execute them as parameterized suites in CI. The pinned revisions are recorded in `docs/idna-unicode-update.md` (Recorded corpus revisions). Each corpus gates a defined slice of behaviour; "gates" means a non-`KNOWN_FAILURES` regression in that corpus MUST fail the build (`check`). Where a corpus encodes its own expected-failure markers (e.g. WPT `failure: true`), the implementation MUST honour them.

| Corpus | Source family | Gates | Profile |
| --- | --- | --- | --- |
| `urltestdata.json` | web-platform-tests (WPT) | end-to-end parsing + serialization of every component | `Url` |
| `setters_tests.json` | WPT | builder/setter mutation semantics per component | `Url` |
| `percent-encoding.json` | WPT | per-component percent-encode-set application | `Url` |
| `toascii.json` | WPT | IDNA ToASCII (reg-name) host pipeline | `Url` |
| `IdnaTestV2.json` | Unicode UTS-46 | bundled UTS-46 mapping/ToASCII/ToUnicode conformance | both (host module) |
| `urlsearchparams-*.any.js` vectors | WPT | `QueryParameters` parse/serialize/sort/mutation | `Url` |
| `HttpUrlTest` / `HttpUrlJvmTest` tables | okhttp | scheme/host/port/path/query/fragment corner cases, builder API | `Url` (cross-checked against `Uri`) |
| `UrlComponentEncodingTester` matrix | okhttp | per-code-point × per-component encode classification | both |
| RFC 3986 §5.4.1 / §5.4.2 example tables | RFC 3986 | reference resolution (normal + abnormal) | both |

- **[CONF-6]** Under `ParseProfile.URL`, the implementation MUST pass every WPT `urltestdata.json` case: for each non-failure case the parsed `Url` MUST serialize to the expected `href` and expose the expected `protocol`/`username`/`password`/`host`/`hostname`/`port`/`pathname`/`search`/`hash`; for each `failure: true` case parsing MUST yield `ParseResult.Err`.
- **[CONF-7]** The implementation MUST pass every WPT `setters_tests.json` case: applying the named setter on a parsed `Url` builder MUST produce the listed expected component values (or be rejected where the corpus marks the value invalid).
- **[CONF-8]** The implementation MUST pass every WPT `percent-encoding.json` case: each input code point encoded in the named component MUST match the corpus output for that component's encode set.
- **[CONF-9]** The implementation MUST pass every WPT `toascii.json` case: the reg-name host pipeline MUST produce the listed ASCII output, or reject the input where the corpus output is `null`.
- **[CONF-10]** The host/IDNA module MUST pass the Unicode `IdnaTestV2.json` corpus for ToASCII and ToUnicode (with the corpus's transitional/non-transitional and bidi/contextual flags). Because IDNA is implemented via the bundled UTS-46 table (not `java.net.IDN`), divergence from `IdnaTestV2.json` is a conformance failure, not an accepted backend quirk.
- **[CONF-11]** Under `ParseProfile.URL`, `QueryParameters` MUST pass the WPT `urlsearchparams` vectors covering construction, `get`/`getAll`/`has`, append/set/delete, iteration order, `sort()`, and stringification.
- **[CONF-12]** The implementation MUST pass the okhttp `HttpUrlTest`/`HttpUrlJvmTest` tables under `ParseProfile.URL`. Cases that assert RFC 3986-divergent WHATWG behaviour MUST also be cross-executed under `ParseProfile.URI` with the `Uri`-profile expectations recorded in this specification; divergences MUST be expected, not incidental.
- **[CONF-13]** The implementation MUST pass the okhttp `UrlComponentEncodingTester` matrix: for code points `0x00..0x7F` plus a representative sample of 2-, 3-, and 4-byte code points, the classification (IDENTITY / PERCENT / FORBIDDEN / PUNYCODE / SKIP) in each component MUST match the per-component encode-set matrix defined in §5.
- **[CONF-14]** Both profiles MUST pass the RFC 3986 §5.4.1 (normal) and §5.4.2 (abnormal) reference-resolution example tables against base `http://a/b/c/d;p?q`, producing the RFC's target URIs exactly.
- **[CONF-15]** Corpora MUST be pinned by revision and the pinned revision recorded; a corpus upgrade MUST be a reviewed change, and any new case that fails MUST be either fixed or entered into `KNOWN_FAILURES` (§13.4) before merge.

### 13.3 Edge-case master checklist (normative)

Each requirement below is a single testable behaviour with its example input and required result. Unless qualified, the requirement applies to both profiles. Where an example would be rejected, "→ `Err`" denotes a `ParseResult.Err` carrying a `UriParseError`; non-fatal observations are `ValidationError`s and MUST NOT abort parsing.

#### A. Input preprocessing / whitespace

- **[CONF-16]** In the `Url` profile, ASCII tab (U+0009), LF (U+000A), and CR (U+000D) MUST be removed from anywhere in the input before parsing, with a `ValidationError` recorded: `http://exa\tmp\nle.org/` MUST parse to host `example.org`. In the `Uri` profile these code points MUST NOT be removed.
- **[CONF-17]** Leading and trailing C0-controls-or-space (code points `0x00..0x20`) MUST be trimmed from the input before parsing, with a `ValidationError`: both `"  http://h/  "` and `"\r\n\f\thttp://h/"` MUST parse as `http://h/`.
- **[CONF-18]** Unicode whitespace that is not C0/space and not tab/LF/CR (e.g. U+000B, U+001C–U+001F treated as C0 only within the trim set, U+0085, U+00A0, U+1680, U+2000–U+200F, U+2028, U+2029, U+202F, U+205F, U+3000) MUST NOT be stripped; when it appears in a path it MUST be percent-encoded per the path encode set, not removed.
- **[CONF-19]** Empty or all-trimmed input MUST be a defined outcome: standalone parse of `""` MUST yield `Err`; relative resolution of `""` against a base MUST follow [CONF-116].
- **[CONF-20]** `resolve("   ")` and `resolve("  .  ")` against a base MUST, after trimming, resolve to the base with its fragment removed.
- **[CONF-21]** A fixed maximum input length MUST be enforced (see §12), and the length bound MUST be re-checked after any expansion (percent-decoding, IDNA/Punycode label expansion); inputs whose post-expansion size exceeds the bound MUST yield `Err`.

#### B. Scheme

- **[CONF-22]** A scheme MUST begin with an ASCII letter followed by zero or more of letter, digit, `+`, `-`, `.`: `ht+tp:`, `ht-tp:`, `ht.tp:` have valid scheme characters; a leading digit or other character MUST cause the scheme not to be recognized.
- **[CONF-23]** A recognized scheme MUST be lowercased in the stored and serialized form: `HTTP://h/` → scheme `http`.
- **[CONF-24]** Input with no `:` before any `/`, `?`, or `#`, or with `/` before the first `:`, MUST be treated as a relative reference with no scheme: `http//b/` MUST parse as a path (no scheme), and `/a:b/c` MUST parse as path `/a:b/c` with no scheme.
- **[CONF-25]** A `:` occurring inside the query, path, or fragment MUST NOT be mistaken for the scheme delimiter: in a relative reference `a?query:` the `:` belongs to the query.
- **[CONF-26]** The three scheme states MUST be distinct and independently serializable: scheme present (`http:`), scheme `null` (no scheme, a relative reference), and the empty-scheme/protocol-relative case `//host` (authority present, no scheme). An implementation MUST NOT conflate `null` scheme with empty scheme.
- **[CONF-27]** In the `Url` profile, an unsupported but syntactically valid scheme (any scheme not in the special set http/https/ws/wss/ftp/file) MUST be treated as a non-special URL: no special-scheme slash coalescing, no host pipeline, opaque-or-empty host, opaque path where applicable.

#### C. Authority / slashes

- **[CONF-28]** In the `Url` profile, for a special scheme the count and direction of slashes after the scheme MUST be irrelevant: `http:host`, `http:/host`, `http://host`, `http:///host`, `http:////host`, and any mixture using `\` MUST all parse to authority host `host` with a `ValidationError` for each non-canonical form. In the `Uri` profile slash count is significant and backslashes are not rewritten.
- **[CONF-29]** Slash count MUST remain significant in same-scheme relative resolution: against a same-scheme base, `http:host/path`, `http:/host/path`, and `http://host/path` MUST resolve to distinct results per the resolution algorithm.
- **[CONF-30]** An empty authority (`""`) MUST be distinct from an absent authority (`null`): `file:///path`, `///sup`, and `foo://` have an empty authority; a URL with no `//` has `null` authority. The two MUST serialize differently and MUST compare unequal.
- **[CONF-31]** A reference beginning `//host2` MUST be parsed as a protocol-relative (network-path) reference and resolved by replacing the base authority.

#### D. Userinfo

- **[CONF-32]** Userinfo MUST be split into user and password on the **first** `:`, and the host boundary MUST be the **last** `@` in the authority: `foo@bar@baz` → user `foo@bar` (serialized `foo%40bar`), host `baz`; `foo:pass1@bar:pass2@baz` → user `foo`, password `pass1@bar:pass2`, host `baz`.
- **[CONF-33]** Empty user together with empty password MUST collapse, leaving no userinfo: `http://@host/` and `http://:@host/` MUST serialize as `http://host/`.
- **[CONF-34]** An empty user with a non-empty password MUST be preserved: `http://:password@host/` MUST retain the empty user and the password.
- **[CONF-35]** An empty password MUST be distinct from an absent password: `http://user:@host/` (user, empty password) MUST NOT serialize identically to `http://user@host/` (user, absent password).
- **[CONF-36]** Userinfo MUST round-trip through percent-decoding for accessor values while preserving the encoded form for serialization: encoded userinfo `%F0%9F%8D%A9@host` MUST expose decoded user `🍩`.

#### E. Host — IPv4

- **[CONF-37]** A dotted-decimal IPv4 host MUST parse to `Host.Ipv4`: `192.0.2.16`.
- **[CONF-38]** IPv4 octet overflow MUST be rejected: `192.168.0.257` → `Err`.
- **[CONF-39]** A single trailing dot on an IPv4 host MUST be stripped before parsing.
- **[CONF-40]** In the `Url` profile, WHATWG IPv4 shorthand and non-decimal forms MUST be parsed: hex (`192.0x00A80001`), mixed hex/octal (`0Xc0.0250.01`), fullwidth digits (`０Ｘｃ０．０２５０．０１`), percent-encoded equivalents, and 1–4 parts where the final part absorbs the remaining bits with width-aware overflow (`value ≥ 1 << (32 − 8 × count)` rejected); `0x` and `0x.` denote `0`; base is detected per octet. In the `Uri` profile only plain dotted-decimal is accepted.
- **[CONF-41]** A parsed IPv4 host MUST re-serialize in canonical dotted-decimal form regardless of input radix: `192.0x00A80001` → `192.168.0.1`.

#### F. Host — IPv6

- **[CONF-42]** An IPv6 host MUST be bracketed in input and serialization, but the brackets MUST NOT be part of the stored `Host.Ipv6` value.
- **[CONF-43]** Bracketed IPv6 contents MUST be percent-decoded before parsing: `[%3A%3A%31]` → `::1`, and `%5B%3A%3A1%5D` (encoded brackets) → `::1`.
- **[CONF-44]** `::` zero compression MUST be parsed and the host re-serialized per RFC 5952 (longest zero run compressed, leftmost on tie, lowercase hex, no leading zeros): `[2001:db8:0:0:1:0:0:1]` → `2001:db8::1:0:0:1`.
- **[CONF-45]** Leading/trailing zero groups MUST compress: `[::0001]` and `[0000::0001]` → `::1`; `[0001::0000]` → `1::`.
- **[CONF-46]** An embedded IPv4 suffix MUST be parsed into the low 32 bits: `[::1:255.255.255.255]` → `::1:ffff:ffff`; `[0:0:0:0:0:1:0.0.0.0]` → `::1:0:0`.
- **[CONF-47]** Embedded-IPv4 octets MUST follow strict decimal rules: reject `256`, hex (`ff`, `0x10`), octal (`010`, `000001`), empty (`.255`), double dot (`255..255`), leading/trailing dot, and incomplete groups → `Err`.
- **[CONF-48]** An IPv4-mapped or IPv4-embedded IPv6 address MUST re-serialize in the canonical eight-piece pure-hexadecimal form of [HOST-16]; dotted-decimal notation MUST NOT be reintroduced on output: `[::ffff:192.168.1.254]` → `[::ffff:c0a8:1fe]` (never `[::ffff:192.168.1.254]`).
- **[CONF-49]** Invalid IPv6 forms MUST be rejected: too many digits per group (`[::00001]`), misplaced or excess colons (`[:1]`, `[:::1]`, `[1:::]`, `[1:::1]`), trailing colon, more than 8 groups, more than one `::`, and any group with disallowed leading content → `Err`.
- **[CONF-50]** A `:` inside `[...]` MUST NOT be treated as the port delimiter.
- **[CONF-51]** A builder MUST accept an IPv6 host supplied with or without surrounding brackets and store the bracket-free value.
- **[CONF-52]** An IPv6 zone identifier MUST be rejected by default: `[::1%2544]` → `Err`, unless the RFC 6874 zone-id opt-in is enabled (§13.4), in which case it is parsed and stored.

#### G. Host — reg-name / IDNA / UTS-46

- **[CONF-53]** A reg-name host MUST be ASCII-lowercased, but percent-encoded triplets within it MUST be uppercased, not lowercased: `EXAMPLE.COM` → `example.com`; an embedded `%2f` triplet → `%2F`.
- **[CONF-54]** IDNA mapping plus Punycode MUST be applied per the bundled UTS-46 table: `σ` and `Σ` → `xn--4xa`; `ABCD` → `abcd`.
- **[CONF-55]** Code points classified "ignored" by UTS-46 MUST be dropped: soft hyphen U+00AD in `AB­CD` → `abcd`; other zero-width ignored code points likewise removed.
- **[CONF-56]** Single-code-point and length-expanding UTS-46 mappings MUST be applied: `℡` (U+2121) → `tel`, `K` (U+212A) → `k`, `™` mapped to `tm`; an expansion that pushes a label past its length bound MUST be rejected under [CONF-58].
- **[CONF-57]** NFC normalization MUST precede mapping: precomposed `café` and decomposed (NFD) `café` MUST both map to `xn--caf-dma`; a pre-encoded NFD Punycode label (`xn--cafe-yvc`) MUST be rejected.
- **[CONF-58]** A label length bound of 63 and a total host bound of 253 MUST be enforced for the domain pipeline; the presence of an `xn--` substring MUST force the IDNA validation path even for otherwise-ASCII input.
- **[CONF-59]** Empty labels MUST be rejected: `a..b`, `.a`, `..`, `...`, and a bare empty host where the scheme forbids it → `Err`; a single trailing dot (`a.`) MUST be accepted.
- **[CONF-60]** The forbidden-host code-point set (space, `#`, `%`, `/`, `:`, `?`, `@`, `[`, `\`, `]`, `^`, `|`, C0 controls, tab/newline) MUST cause rejection of a special-scheme host; the stricter forbidden-domain set additionally forbids `%`, all code points `≤ 0x20`, and all code points `≥ 0x7F`.
- **[CONF-61]** The forbidden code-point scan MUST be re-run **after** IDNA processing, so that mappings producing a forbidden code point are rejected.
- **[CONF-62]** For a non-special or opaque host, only C0 controls and the forbidden-host set MUST cause rejection; all other disallowed code points MUST be percent-encoded rather than rejected.
- **[CONF-63]** Empty-host rules MUST follow the scheme: a special non-`file` scheme MUST reject an empty host (`https://` → `Err`); `file` MUST allow an empty host; `file://localhost/` MUST normalize the host to the empty string.
- **[CONF-64]** In the `Uri` profile, an IP-future host (`[v1.fe80::a+en1]`) MUST be parsed as `Host.IpFuture`; this form is not produced by the `Url` profile.
- **[CONF-65]** Hosts containing characters that are valid in `Url` but invalid for a `Uri` MUST be handled per profile: `http://>/` parses (host `>`) in `Url`, but `toUri()` on the result MUST expose a `null`/absent `Uri` host or yield `Err` on conversion; `http://example".com/` similarly diverges by profile.

#### H. Port

- **[CONF-66]** A port MUST be decimal in `1..65535`; `f:999999` and `f:00000000000000` MUST be rejected → `Err`.
- **[CONF-67]** In the `Url` profile, port `0` MUST be accepted. In the `Uri` strict mode, port `0` MUST be rejected.
- **[CONF-68]** An empty port MUST be elided, and a port equal to the scheme's default MUST be elided in serialization: `http://h:80/` → `http://h/`.
- **[CONF-69]** An explicit non-default port MUST be preserved across a scheme change: setting scheme `http` on `https://h:1234/` MUST keep port `1234`.
- **[CONF-70]** A port with a leading sign, or a newline, MUST be rejected: `h:-1` → `Err`; a port with insignificant leading zeros (`h:080`) MUST normalize to `80`.
- **[CONF-71]** The stored `port: Int?` (explicit port, `null` when absent) MUST be distinct from `effectivePort` (explicit port if present, else the scheme default).

#### I. Path & dot-segments

- **[CONF-72]** RFC 3986 §5.4 dot-segment removal MUST be applied during resolution: `../../../g` → `/g`, `/./g` → `/g`, `/../g` → `/g`, `g/../h` → `/h`, `./g/.` → `/g/`; an overshoot of `..` MUST clamp at the root.
- **[CONF-73]** Non-dot segments that merely resemble dot segments MUST be kept literally: `g.`, `.g`, `g..`, `..g`, `..e/`, `e/f../`.
- **[CONF-74]** `%2E` / `%2e` MUST be treated as `.` for the purpose of dot-segment processing during **resolution** (`resolve("%2E%2E")` pops a segment; `resolve("%2E")` yields a trailing-slash directory), but MUST remain literal when added via the encoded/decoded builder API: `addPathSegment("%2e")` → segment `%252e`.
- **[CONF-75]** `setPathSegment` MUST reject `.` and `..` as segment values; `addPathSegment("..")` MUST pop the preceding segment.
- **[CONF-76]** `addEncodedPathSegment(".\n")` MUST strip the ignored character and contribute nothing, whereas `addPathSegment(".\n")` MUST percent-encode to `.%0A`.
- **[CONF-77]** Empty segments MUST be significant: `addPathSegment("")` adds a trailing slash; consecutive slashes (`//d`) make each slash a boundary; an empty path on a URL that has an authority MUST normalize to a single empty segment serialized as `/`.
- **[CONF-78]** A `%2F` inside a single path segment MUST remain a literal (encoded) slash and MUST NOT split the segment: encoded `a%2Fb%2Fc` is one segment whose decoded value is `a/b/c`.
- **[CONF-79]** Mixed encoded and decoded path operations MUST compose with correct double-encoding: starting from encoded path `/a%2fb/c`, then `addPathSegment("d%25e")`, then `addEncodedPathSegment("f%25g")` MUST yield `/a%2fb/c/d%2525e/f%25g`.
- **[CONF-80]** In the `Url` profile for special schemes, backslashes in the path MUST be rewritten to slashes: `resolve("d\\e\\f")` → `/d/e/f`. In the `Uri` profile backslashes MUST be left literal (percent-encoded where the path encode set requires).
- **[CONF-81]** In the `Url` `file` scheme, Windows drive letters MUST be handled: `C:` and `C|` recognized when the third character is `/`, `\`, `?`, `#`, or end of input; a drive letter MUST be normalized to the `:` form (`C|` → `C:`); the path-shortening step MUST NOT pop a lone drive-letter segment.
- **[CONF-82]** `file` host variants MUST be handled: `file://localhost/p` → empty host; an empty `file` host is allowed; the 1-, 2-, and 3-slash `file` forms MUST parse per the WHATWG `file` state.
- **[CONF-83]** A path with an authority MUST start with `/`; a path without an authority MUST NOT start with `//`. The `/.`-prefix sentinel MUST be emitted on serialization when an authority-less path would otherwise begin `//`, preserving round-trip correctness.
- **[CONF-84]** A path-noscheme relative reference MUST forbid a `:` in its first segment so the segment is not mistaken for a scheme.
- **[CONF-85]** A trailing slash MUST carry directory semantics (a trailing empty segment), surfaced by `isDirectory`/`hasTrailingSlash` accessors.
- **[CONF-86]** For an opaque (cannot-be-a-base) path, a single trailing space MUST be percent-encoded to `%20` and otherwise preserved verbatim.

#### J. Percent-encoding

- **[CONF-87]** Basic percent-decoding MUST be correct: `%00` → NUL, `%E2%98%83` → `☃`, `%F0%9F%8D%A9` → `🍩`, `%62` → `b`, and both `%7A` and `%7a` → `z`.
- **[CONF-88]** Malformed percent sequences MUST be left literal on parse: `a%f/b` → segments `['a%f','b']`; `%/b` → `['%','b']`; a lone `%` → `['%']`; `%%30%30` → `%00`.
- **[CONF-89]** Percent sequences that decode to malformed UTF-8 MUST decode to the replacement character: `%E2%98x` → `�x`.
- **[CONF-90]** Percent-triplets the implementation itself emits MUST use uppercase hex digits, but a raw triplet already present in the input MUST survive verbatim: in the `Uri` profile `%6d%6D` is preserved as written when not normalized, and normalized to `%6D%6D` only when normalization is requested; in the `Url` profile `%6d%6D` is likewise preserved as written (the WHATWG parser does not re-case existing triplets — [PCT-32]/[NORM-6]), even though the `Url` profile canonicalizes eagerly.
- **[CONF-91]** Non-ASCII code points requiring encoding in a component MUST be UTF-8 percent-encoded: `🍩` → `%F0%9F%8D%A9`.
- **[CONF-92]** `Url.toUri()` MUST re-escape invalid percent sequences for RFC validity: `/%xx` → `/%25xx`, `/%a` → `/%25a`, `/%` → `/%25`.
- **[CONF-93]** The per-component encode set MUST match the §5 matrix for every code point in `0x00..0x7F` plus sampled 2-, 3-, and 4-byte code points, classified as IDENTITY / PERCENT / FORBIDDEN / PUNYCODE / SKIP per component (the `UrlComponentEncodingTester` contract).
- **[CONF-94]** Encoding MUST be idempotent under the already-encoded flag: re-encoding input that is already percent-encoded with `encodeEncoded = false` MUST NOT double-encode existing valid triplets.

#### K. Query

- **[CONF-95]** Query presence models MUST be distinguished: absent query (`null`) → 0 pairs; `?` (empty query) → one pair with empty name and `null` value; `?&` → two such pairs; `?foo` → name `foo`, value `null`; `?foo=` → name `foo`, value `""`.
- **[CONF-96]** `QueryParameters` MUST be ordered and duplicate-preserving: `?foo[]=1&foo[]=2&foo[]=3` retains all three pairs in order.
- **[CONF-97]** A query MUST be allowed to contain `?` and MUST terminate at `#`: `?ldap://x?objectClass?one#frag` keeps the inner `?`s in the query and starts the fragment at `#`.
- **[CONF-98]** `?#fragment` MUST yield an empty (not `null`) query plus the fragment.
- **[CONF-99]** Form-dialect lookup MUST treat `+` as space and be insensitive to `%xx` casing in keys: `?%6d=m&+=%20` exposes names `m` and (a single space).
- **[CONF-100]** `addQueryParameter` MUST encode `+`, `=`, `&`, and space (`a+=& b` → `a%2B%3D%26%20b`), whereas `addEncodedQueryParameter` MUST keep `+` literal but still encode `=` and `&`.
- **[CONF-101]** A query value containing special characters (`` !$(),/:;?@[]\^`{|}~ ``) MUST be fully percent-encoded by the encoding setter path.
- **[CONF-102]** Form serialization MUST map space → `+`, `+` → `%2B`, `&` → `%26`, `=` → `%3D`, and UTF-8 accents (`été` → `%C3%A9t%C3%A9`); empty pairs MUST round-trip (`a=&=&=b`).
- **[CONF-103]** `sort()` MUST be stable and ordered by Unicode code point (surrogate-aware), falling back to raw-byte ordering for truncated or invalid UTF-8.
- **[CONF-104]** A query parse pair-count limit MUST be enforced as a DoS bound (see §12), beyond which parsing yields `Err` or truncates per the defined policy.
- **[CONF-105]** A `=` inside an empty-key pair MUST be left literal so `?===3===` round-trips, while `=` inside a non-empty-key pair is encoded.

#### L. Fragment

- **[CONF-106]** Most code points, including non-ASCII, MUST pass through the fragment with identity: `#Σ` → `Σ`; `#%C2%80` exposes U+0080 and serializes `%C2%80`.
- **[CONF-107]** The fragment encode set MUST encode space, `"`, `<`, `>`, and `` ` ``.
- **[CONF-108]** A truncated percent sequence in a fragment MUST decode to the replacement character while the encoded accessor preserves the raw bytes: `#%80` → decoded `�`, `encodedFragment` `%80`.
- **[CONF-109]** `Url.toUri()` MUST strip control and whitespace code points from the fragment for RFC validity.
- **[CONF-110]** An empty fragment (`#`) MUST be distinct from an absent fragment (`null`).

#### M. Reference resolution (RFC 3986 §5.4)

- **[CONF-111]** The full §5.4.1 normal table MUST resolve against base `http://a/b/c/d;p?q`: e.g. `g` → `http://a/b/c/g`, `./g` → `http://a/b/c/g`, `g/` → `http://a/b/c/g/`, `/g` → `http://a/g`, `//g` → `http://g`, `?y` → `http://a/b/c/d;p?y`, `#s` → `http://a/b/c/d;p?q#s`, `;x` → `http://a/b/c/;x`.
- **[CONF-112]** The §5.4.2 abnormal table MUST resolve correctly: overshoot `../../../g` → `http://a/g`; `g.`, `.g`, `g..`, `..g` kept literally per the table.
- **[CONF-113]** A same-scheme relative `http:g` MUST resolve as if `g`; in the `Url` profile a foreign-scheme `g:h` reference MUST resolve to its own absolute URL, and where the algorithm cannot produce a valid result it MUST yield `Err`.
- **[CONF-114]** An empty relative reference `""` MUST resolve to the base with its fragment removed.
- **[CONF-115]** Each relative-reference shape MUST resolve per the algorithm: `?query`, `#fragment`, `path`, `/path`, and `//host`.

#### N. Round-trip / idempotency / equality

- **[CONF-116]** `parse(s).toString()` MUST be stable (a second parse-serialize is a fixed point); `newBuilder().build()` MUST reproduce the original value; `resolve("")` MUST be idempotent up to fragment removal per [CONF-114].
- **[CONF-117]** Components consisting entirely of percent signs MUST round-trip: `http://%25:%25@host/%25?%25#%25` MUST serialize unchanged.
- **[CONF-118]** `equals` and `hashCode` MUST be computed over the profile's canonical form (eager-canonical for `Url`, structural canonical-unnormalized for `Uri`), MUST perform no DNS or network access, and MUST make the value safe as a `Map`/`Set` key.
- **[CONF-119]** `Url.toUri()` MUST be near-lossless (succeeding for all WHATWG-valid URLs representable as RFC 3986 URIs), while `Uri.toUrl()` MAY yield `Err` when the `Uri` is not a valid WHATWG URL.
- **[CONF-120]** `redact()` MUST remove userinfo, query, and fragment from the serialized form while leaving scheme, host, port, and path intact.

#### O. Scheme-specific / opaque

- **[CONF-121]** `mailto:John.Doe@example.com` MUST parse with the whole address as an opaque path and no authority.
- **[CONF-122]** Opaque/non-authority schemes MUST preserve their path verbatim: `news:comp.lang.kotlin`, `tel:+1-816-555-1212`, `urn:oasis:names:specification:docbook:dtd:xml:4.1.2` (colons retained in path), `data:text/plain,hello` (remainder verbatim), `about:blank`.
- **[CONF-123]** A non-special scheme MUST receive no special-scheme processing: no IDNA, no IPv4 parsing, no host lowercasing; its host (if any) is an opaque host and its path may be opaque.

### 13.4 Known failures and opt-in policy

- **[CONF-124]** Any intentional deviation from a requirement in this specification MUST be enumerated in a machine-readable `KNOWN_FAILURES` register checked into the repository, keyed by the `[CONF-n]` (or other section) identifier, with a written rationale and a tracking reference. An undeclared corpus failure MUST fail the build.
- **[CONF-125]** A requirement stated with **MUST** MUST NOT appear in `KNOWN_FAILURES` for a claimed conformance class; only **SHOULD**/**RECOMMENDED** requirements MAY be waived there, and a waiver of any **MUST** invalidates the conformance claim for that class.
- **[CONF-126]** Opt-in features MUST default to off and MUST be enabled only by explicit configuration. RFC 6874 IPv6 zone identifiers are OPTIONAL and off by default: with the opt-in disabled, `[fe80::1%eth0]` and `[::1%2544]` MUST yield `Err` ([CONF-52]); with it enabled, the zone id MUST be parsed, stored, and serialized per RFC 6874. Enabling an opt-in MUST NOT change the result of any non-opt-in corpus case.
- **[CONF-127]** Enabling or disabling any opt-in or normalization toggle MUST NOT alter behaviour gated by a different toggle; each toggle's effect MUST be independently testable, and the test matrix MUST exercise both states of every opt-in.

### 13.5 Specification versioning and stability

- **[CONF-128]** This specification MUST carry a version identifier, and every `[CONF-n]` identifier MUST be stable across versions: an identifier, once assigned, MUST NOT be reused for a different requirement. A retired requirement MUST be marked withdrawn rather than deleted or renumbered.
- **[CONF-129]** A normative change that adds or tightens a **MUST**/**MUST NOT** requirement, or that changes observable parse/serialize output for previously-conformant input, MUST be released as a new major specification version; a strictly additive, opt-in, or clarifying change MAY be a minor version.
- **[CONF-130]** Each release of the implementation MUST record the specification version and the pinned corpus revisions ([CONF-15]) it was validated against, so that a conformance claim is reproducible against a fixed specification + corpus snapshot.
- **[CONF-131]** Where this specification and an upstream source *other than* **[RFC3986]** (WHATWG URL, RFC 3987, UTS-46, RFC 5952) diverge, the numbered requirements in this specification are authoritative for conformance; an upstream erratum that motivates a change MUST be incorporated through the versioning process in [CONF-129] rather than applied implicitly. Where this specification diverges from **[RFC3986]**, however, **[RFC3986]** governs per the precedence rules of §1.3 ([INTRO-12]-[INTRO-15]): the divergence is authoritative only if it is a `Url`-profile behaviour explicitly registered in **Appendix B — Deviations from RFC 3986**, and any unregistered divergence from [RFC3986] is a specification defect that MUST be resolved in favour of [RFC3986].

> Note: corpora and example tables referenced here are WPT `urltestdata.json` / `setters_tests.json` / `percent-encoding.json` / `toascii.json` / `urlsearchparams`, Unicode `IdnaTestV2.json`, okhttp `HttpUrlTest`/`HttpUrlJvmTest` and `UrlComponentEncodingTester`, and the RFC 3986 §5.4.1/§5.4.2 example sets. The citation is non-normative; the requirements above stand on their own.

---

## Appendix A — Requirements Index

This appendix lists every numbered, testable requirement tag **[ABBR-N]** defined in this specification, with its home section and a short summary. Tags are sorted by abbreviation, then by number. The summary is indicative; the cited section is normative.

| Requirement | Section | Summary |
|---|---|---|
| **[CONF-1]** | §13 | A URI-conformant implementation MUST satisfy every requirement in this specification whose conformance … |
| **[CONF-2]** | §13 | A URL-conformant implementation MUST satisfy every requirement whose conformance class includes the … |
| **[CONF-3]** | §13 | A fully-conformant implementation MUST satisfy [CONF-1] and [CONF-2] simultaneously over a single … |
| **[CONF-4]** | §13 | Requirements stated with MUST/MUST NOT/REQUIRED/SHALL are mandatory for the applicable conformance class … |
| **[CONF-5]** | §13 | Profile behaviour MUST NOT cross-contaminate: parsing under ParseProfile.URI MUST NOT apply any … |
| **[CONF-6]** | §13 | Under ParseProfile.URL, the implementation MUST pass every WPT urltestdata.json case: for each … |
| **[CONF-7]** | §13 | The implementation MUST pass every WPT setters_tests.json case: applying the named setter … |
| **[CONF-8]** | §13 | The implementation MUST pass every WPT percent-encoding.json case: each input code point … |
| **[CONF-9]** | §13 | The implementation MUST pass every WPT toascii.json case: the reg-name host pipeline … |
| **[CONF-10]** | §13 | The host/IDNA module MUST pass the Unicode IdnaTestV2.json corpus for ToASCII and … |
| **[CONF-11]** | §13 | Under ParseProfile.URL, QueryParameters MUST pass the WPT urlsearchparams vectors covering construction, get/getAll/has … |
| **[CONF-12]** | §13 | The implementation MUST pass the okhttp HttpUrlTest/HttpUrlJvmTest tables under ParseProfile.URL. |
| **[CONF-13]** | §13 | The implementation MUST pass the okhttp UrlComponentEncodingTester matrix: for code points 0x00..0x7F … |
| **[CONF-14]** | §13 | Both profiles MUST pass the RFC 3986 §5.4.1 (normal) and §5.4.2 (abnormal) … |
| **[CONF-15]** | §13 | Corpora MUST be pinned by revision and the pinned revision recorded; a … |
| **[CONF-16]** | §13 | In the Url profile, ASCII tab (U+0009), LF (U+000A), and CR (U+000D) … |
| **[CONF-17]** | §13 | Leading and trailing C0-controls-or-space (code points 0x00..0x20) MUST be trimmed from the … |
| **[CONF-18]** | §13 | Unicode whitespace that is not C0/space and not tab/LF/CR (e.g. |
| **[CONF-19]** | §13 | Empty or all-trimmed input MUST be a defined outcome: standalone parse of … |
| **[CONF-20]** | §13 | resolve(" ") and resolve(" . |
| **[CONF-21]** | §13 | A fixed maximum input length MUST be enforced (see §12), and the … |
| **[CONF-22]** | §13 | A scheme MUST begin with an ASCII letter followed by zero or … |
| **[CONF-23]** | §13 | A recognized scheme MUST be lowercased in the stored and serialized form … |
| **[CONF-24]** | §13 | Input with no : before any /, ?, or #, or with … |
| **[CONF-25]** | §13 | A : occurring inside the query, path, or fragment MUST NOT be … |
| **[CONF-26]** | §13 | The three scheme states MUST be distinct and independently serializable: scheme present … |
| **[CONF-27]** | §13 | In the Url profile, an unsupported but syntactically valid scheme (any scheme … |
| **[CONF-28]** | §13 | In the Url profile, for a special scheme the count and direction … |
| **[CONF-29]** | §13 | Slash count MUST remain significant in same-scheme relative resolution: against a same-scheme … |
| **[CONF-30]** | §13 | An empty authority ("") MUST be distinct from an absent authority (null) … |
| **[CONF-31]** | §13 | A reference beginning //host2 MUST be parsed as a protocol-relative (network-path) reference … |
| **[CONF-32]** | §13 | Userinfo MUST be split into user and password on the first  … |
| **[CONF-33]** | §13 | Empty user together with empty password MUST collapse, leaving no userinfo: http://@host/ … |
| **[CONF-34]** | §13 | An empty user with a non-empty password MUST be preserved: http://:password@host/ MUST … |
| **[CONF-35]** | §13 | An empty password MUST be distinct from an absent password: http://user:@host/ (user … |
| **[CONF-36]** | §13 | Userinfo MUST round-trip through percent-decoding for accessor values while preserving the encoded … |
| **[CONF-37]** | §13 | A dotted-decimal IPv4 host MUST parse to Host.Ipv4: 192.0.2.16. |
| **[CONF-38]** | §13 | IPv4 octet overflow MUST be rejected: 192.168.0.257 → Err. |
| **[CONF-39]** | §13 | A single trailing dot on an IPv4 host MUST be stripped before parsing. |
| **[CONF-40]** | §13 | In the Url profile, WHATWG IPv4 shorthand and non-decimal forms MUST be … |
| **[CONF-41]** | §13 | A parsed IPv4 host MUST re-serialize in canonical dotted-decimal form regardless of … |
| **[CONF-42]** | §13 | An IPv6 host MUST be bracketed in input and serialization, but the … |
| **[CONF-43]** | §13 | Bracketed IPv6 contents MUST be percent-decoded before parsing: [%3A%3A%31] → ::1, and … |
| **[CONF-44]** | §13 | :: zero compression MUST be parsed and the host re-serialized per RFC … |
| **[CONF-45]** | §13 | Leading/trailing zero groups MUST compress: [::0001] and [0000::0001] → ::1; [0001::0000] → 1::. |
| **[CONF-46]** | §13 | An embedded IPv4 suffix MUST be parsed into the low 32 bits … |
| **[CONF-47]** | §13 | Embedded-IPv4 octets MUST follow strict decimal rules: reject 256, hex (ff, 0x10) … |
| **[CONF-48]** | §13 | An IPv4-mapped or IPv4-embedded IPv6 address MUST re-serialize in the canonical eight-piece pure-hexadecimal form of [HOST-16] … |
| **[CONF-49]** | §13 | Invalid IPv6 forms MUST be rejected: too many digits per group ([::00001]) … |
| **[CONF-50]** | §13 | A : inside [...] MUST NOT be treated as the port delimiter. |
| **[CONF-51]** | §13 | A builder MUST accept an IPv6 host supplied with or without surrounding … |
| **[CONF-52]** | §13 | An IPv6 zone identifier MUST be rejected by default: [::1%2544] → Err … |
| **[CONF-53]** | §13 | A reg-name host MUST be ASCII-lowercased, but percent-encoded triplets within it MUST … |
| **[CONF-54]** | §13 | IDNA mapping plus Punycode MUST be applied per the bundled UTS-46 table … |
| **[CONF-55]** | §13 | Code points classified "ignored" by UTS-46 MUST be dropped: soft hyphen U+00AD … |
| **[CONF-56]** | §13 | Single-code-point and length-expanding UTS-46 mappings MUST be applied: ℡ (U+2121) → tel … |
| **[CONF-57]** | §13 | NFC normalization MUST precede mapping: precomposed café and decomposed (NFD) café MUST … |
| **[CONF-58]** | §13 | A label length bound of 63 and a total host bound of … |
| **[CONF-59]** | §13 | Empty labels MUST be rejected: a..b, .a, .., ..., and a bare … |
| **[CONF-60]** | §13 | The forbidden-host code-point set (space, #, %, /, :, ?, @, [ … |
| **[CONF-61]** | §13 | The forbidden code-point scan MUST be re-run after IDNA processing, so that … |
| **[CONF-62]** | §13 | For a non-special or opaque host, only C0 controls and the forbidden-host … |
| **[CONF-63]** | §13 | Empty-host rules MUST follow the scheme: a special non-file scheme MUST reject … |
| **[CONF-64]** | §13 | In the Uri profile, an IP-future host ([v1.fe80::a+en1]) MUST be parsed as … |
| **[CONF-65]** | §13 | Hosts containing characters that are valid in Url but invalid for a … |
| **[CONF-66]** | §13 | A port MUST be decimal in 1..65535; f:999999 and f:00000000000000 MUST be … |
| **[CONF-67]** | §13 | In the Url profile, port 0 MUST be accepted. |
| **[CONF-68]** | §13 | An empty port MUST be elided, and a port equal to the … |
| **[CONF-69]** | §13 | An explicit non-default port MUST be preserved across a scheme change: setting … |
| **[CONF-70]** | §13 | A port with a leading sign, or a newline, MUST be rejected … |
| **[CONF-71]** | §13 | The stored port: Int? (explicit port, null when absent) MUST be distinct … |
| **[CONF-72]** | §13 | RFC 3986 §5.4 dot-segment removal MUST be applied during resolution: ../../../g → … |
| **[CONF-73]** | §13 | Non-dot segments that merely resemble dot segments MUST be kept literally: g … |
| **[CONF-74]** | §13 | %2E / %2e MUST be treated as . |
| **[CONF-75]** | §13 | setPathSegment MUST reject . and .. as segment values; addPathSegment("..") MUST pop … |
| **[CONF-76]** | §13 | addEncodedPathSegment(".\n") MUST strip the ignored character and contribute nothing, whereas addPathSegment(".\n") MUST … |
| **[CONF-77]** | §13 | Empty segments MUST be significant: addPathSegment("") adds a trailing slash; consecutive slashes … |
| **[CONF-78]** | §13 | A %2F inside a single path segment MUST remain a literal (encoded) … |
| **[CONF-79]** | §13 | Mixed encoded and decoded path operations MUST compose with correct double-encoding: starting … |
| **[CONF-80]** | §13 | In the Url profile for special schemes, backslashes in the path MUST … |
| **[CONF-81]** | §13 | In the Url file scheme, Windows drive letters MUST be handled: C … |
| **[CONF-82]** | §13 | file host variants MUST be handled: file://localhost/p → empty host; an empty … |
| **[CONF-83]** | §13 | A path with an authority MUST start with /; a path without … |
| **[CONF-84]** | §13 | A path-noscheme relative reference MUST forbid a : in its first segment … |
| **[CONF-85]** | §13 | A trailing slash MUST carry directory semantics (a trailing empty segment), surfaced … |
| **[CONF-86]** | §13 | For an opaque (cannot-be-a-base) path, a single trailing space MUST be percent-encoded … |
| **[CONF-87]** | §13 | Basic percent-decoding MUST be correct: %00 → NUL, %E2%98%83 → ☃, %F0%9F%8D%A9 … |
| **[CONF-88]** | §13 | Malformed percent sequences MUST be left literal on parse: a%f/b → segments … |
| **[CONF-89]** | §13 | Percent sequences that decode to malformed UTF-8 MUST decode to the replacement … |
| **[CONF-90]** | §13 | Percent-triplet hex digits MUST serialize uppercase, but a raw triplet MUST survive … |
| **[CONF-91]** | §13 | Non-ASCII code points requiring encoding in a component MUST be UTF-8 percent-encoded … |
| **[CONF-92]** | §13 | Url.toUri() MUST re-escape invalid percent sequences for RFC validity: /%xx → /%25xx … |
| **[CONF-93]** | §13 | The per-component encode set MUST match the §5 matrix for every code … |
| **[CONF-94]** | §13 | Encoding MUST be idempotent under the already-encoded flag: re-encoding input that is … |
| **[CONF-95]** | §13 | Query presence models MUST be distinguished: absent query (null) → 0 pairs … |
| **[CONF-96]** | §13 | QueryParameters MUST be ordered and duplicate-preserving: ?foo[]=1&foo[]=2&foo[]=3 retains all three pairs in order. |
| **[CONF-97]** | §13 | A query MUST be allowed to contain ? and MUST terminate at … |
| **[CONF-98]** | §13 | ?#fragment MUST yield an empty (not null) query plus the fragment. |
| **[CONF-99]** | §13 | Form-dialect lookup MUST treat + as space and be insensitive to %xx … |
| **[CONF-100]** | §13 | addQueryParameter MUST encode +, =, &, and space (a+=& b → a%2B%3D%26%20b) … |
| **[CONF-101]** | §13 | A query value containing special characters ( !$(),/:;?@[]\^{ }~ ) MUST be … |
| **[CONF-102]** | §13 | Form serialization MUST map space → +, + → %2B, & → … |
| **[CONF-103]** | §13 | sort() MUST be stable and ordered by Unicode code point (surrogate-aware), falling … |
| **[CONF-104]** | §13 | A query parse pair-count limit MUST be enforced as a DoS bound … |
| **[CONF-105]** | §13 | A = inside an empty-key pair MUST be left literal so ?===3=== … |
| **[CONF-106]** | §13 | Most code points, including non-ASCII, MUST pass through the fragment with identity … |
| **[CONF-107]** | §13 | The fragment encode set MUST encode space, ", <, >, and . |
| **[CONF-108]** | §13 | A truncated percent sequence in a fragment MUST decode to the replacement … |
| **[CONF-109]** | §13 | Url.toUri() MUST strip control and whitespace code points from the fragment for RFC validity. |
| **[CONF-110]** | §13 | An empty fragment (#) MUST be distinct from an absent fragment (null). |
| **[CONF-111]** | §13 | The full §5.4.1 normal table MUST resolve against base http://a/b/c/d;p?q: e.g. g … |
| **[CONF-112]** | §13 | The §5.4.2 abnormal table MUST resolve correctly: overshoot ../../../g → http://a/g; g … |
| **[CONF-113]** | §13 | A same-scheme relative http:g MUST resolve as if g; in the Url … |
| **[CONF-114]** | §13 | An empty relative reference "" MUST resolve to the base with its fragment removed. |
| **[CONF-115]** | §13 | Each relative-reference shape MUST resolve per the algorithm: ?query, #fragment, path, /path, and //host. |
| **[CONF-116]** | §13 | parse(s).toString() MUST be stable (a second parse-serialize is a fixed point); newBuilder().build() … |
| **[CONF-117]** | §13 | Components consisting entirely of percent signs MUST round-trip: http://%25:%25@host/%25?%25#%25 MUST serialize unchanged. |
| **[CONF-118]** | §13 | equals and hashCode MUST be computed over the profile's canonical form (eager-canonical … |
| **[CONF-119]** | §13 | Url.toUri() MUST be near-lossless (succeeding for all WHATWG-valid URLs representable as RFC … |
| **[CONF-120]** | §13 | redact() MUST remove userinfo, query, and fragment from the serialized form while … |
| **[CONF-121]** | §13 | mailto:John.Doe@example.com MUST parse with the whole address as an opaque path and no authority. |
| **[CONF-122]** | §13 | Opaque/non-authority schemes MUST preserve their path verbatim: news:comp.lang.kotlin, tel:+1-816-555-1212, urn:oasis:names:specification:docbook:dtd:xml:4.1.2 (colons retained … |
| **[CONF-123]** | §13 | A non-special scheme MUST receive no special-scheme processing: no IDNA, no IPv4 … |
| **[CONF-124]** | §13 | Any intentional deviation from a requirement in this specification MUST be enumerated … |
| **[CONF-125]** | §13 | A requirement stated with MUST MUST NOT appear in KNOWN_FAILURES for a … |
| **[CONF-126]** | §13 | Opt-in features MUST default to off and MUST be enabled only by explicit configuration. |
| **[CONF-127]** | §13 | Enabling or disabling any opt-in or normalization toggle MUST NOT alter behaviour … |
| **[CONF-128]** | §13 | This specification MUST carry a version identifier, and every [CONF-n] identifier MUST … |
| **[CONF-129]** | §13 | A normative change that adds or tightens a MUST/MUST NOT requirement, or … |
| **[CONF-130]** | §13 | Each release of the implementation MUST record the specification version and the … |
| **[CONF-131]** | §13 | Where this specification and an upstream source (WHATWG URL, RFC 3986/3987, UTS-46 … |
| **[ERR-1]** | §12 | Every parsing entry point that can fail recoverably (Uri.parse(String): ParseResult<Uri>, Url.parse(String, base) … |
| **[ERR-2]** | §12 | A successful parse MUST be reported as exactly one ParseResult.Ok whose value … |
| **[ERR-3]** | §12 | When a parse succeeds, any non-fatal validation errors observed during parsing MUST … |
| **[ERR-4]** | §12 | ParseResult.Ok and ParseResult.Err MUST be the only two cases. |
| **[ERR-5]** | §12 | The convenience entry points MUST be defined in terms of ParseResult as … |
| **[ERR-6]** | §12 | Programmer errors — calls that violate a method's documented precondition — MAY … |
| **[ERR-7]** | §12 | Exceptions MUST NOT be used as normal control flow inside the parser. |
| **[ERR-8]** | §12 | Every UriParseError variant that denotes a position in the input MUST carry … |
| **[ERR-9]** | §12 | InvalidScheme MUST be produced when a scheme component is present but ill-formed … |
| **[ERR-10]** | §12 | InvalidAuthority MUST be produced for an authority that cannot be decomposed (e.g. |
| **[ERR-11]** | §12 | InvalidHost MUST carry the host substring as seen (post-strip, pre-IDNA) and a … |
| **[ERR-12]** | §12 | ForbiddenHostCodePoint MUST be produced when a forbidden host code point (or, for … |
| **[ERR-13]** | §12 | EmptyHost MUST be produced when a host is empty in a context … |
| **[ERR-14]** | §12 | InvalidPort MUST be produced when a port is present and is not … |
| **[ERR-15]** | §12 | InvalidPercentEncoding MUST be produced only where a malformed percent sequence (% not … |
| **[ERR-16]** | §12 | InputTooLong MUST be produced when the input length exceeds the configured maximum … |
| **[ERR-17]** | §12 | LimitExceeded MUST be produced when a configured resource bound other than total … |
| **[ERR-18]** | §12 | The UriParseError hierarchy MUST be sealed and exhaustively matchable without an else. |
| **[ERR-19]** | §12 | In the Url profile, each of the following repairs MUST be recorded … |
| **[ERR-20]** | §12 | A ValidationError MUST NOT alter the produced value relative to an otherwise … |
| **[ERR-21]** | §12 | In the Uri profile (default, preserve-by-RFC), the conditions in [ERR-19] are not … |
| **[ERR-22]** | §12 | The baseline strictness of each profile is fixed: |
| **[ERR-23]** | §12 | An optional strict: Boolean flag (default false) MAY be supplied to either … |
| **[ERR-24]** | §12 | With strict = false, the advisory checks in [ERR-23] MUST NOT be … |
| **[ERR-25]** | §12 | Setting strict = true MUST NOT *relax* any check that is fatal … |
| **[ERR-26]** | §12 | An implementation MUST behave exactly as this table prescribes for each condition and profile. |
| **[ERR-27]** | §12 | Row (d): in the Url profile, "ends in a number" host detection … |
| **[ERR-28]** | §12 | Row (i): the toUri() bridge MUST re-escape an unrepairable literal % as … |
| **[ERR-29]** | §12 | Each limit MUST be enforced and MUST have the documented default value above. |
| **[ERR-30]** | §12 | [InputLength] The original input length MUST be checked before substantive parsing; if … |
| **[ERR-31]** | §12 | [ExpandedLength] Because percent-decoding and IDNA ToUnicode/ToASCII can lengthen a string, the total … |
| **[ERR-32]** | §12 | [QueryPairs] When materializing QueryParameters, the number of name/value pairs MUST be capped … |
| **[ERR-33]** | §12 | [PathSegments / ResolutionDepth] Path-segment splitting and dot-segment removal MUST be bounded: segment … |
| **[ERR-34]** | §12 | [HostLabelLength / HostTotalLength] Label length (≤ 63) and total host length (≤ … |
| **[ERR-35]** | §12 | [PortMax] A port value MUST be rejected when it exceeds 65535 (PortMax) … |
| **[ERR-36]** | §12 | Limit defaults MUST be documented in the public API and MUST be … |
| **[ERR-37]** | §12 | Any exception thrown by an underlying routine invoked during parsing — including … |
| **[ERR-38]** | §12 | The translation MUST preserve diagnostic context: the produced UriParseError MUST carry the … |
| **[ERR-39]** | §12 | The boundary catch MUST be narrow: it MUST be placed around the … |
| **[ERR-40]** | §12 | The translation boundary MUST be deterministic and side-effect-free: translating a foreign exception … |
| **[GRAM-1]** | §4 | An implementation of the Uri profile MUST treat the productions in this … |
| **[GRAM-2]** | §4 | The Url profile MUST NOT use these productions as acceptance gates. |
| **[GRAM-3]** | §4 | In the Uri profile, the IPv4address production above is exact: each dec-octet … |
| **[GRAM-4]** | §4 | The segment-nz-nc production MUST be applied to the first segment of a … |
| **[GRAM-5]** | §4 | ALPHA and DIGIT are exactly the ASCII ranges above; no Unicode "letter" … |
| **[GRAM-6]** | §4 | The core rule HEXDIG as written admits only uppercase A–F. |
| **[GRAM-7]** | §4 | On serialization of a pct-encoded triplet, an implementation MUST emit the two … |
| **[GRAM-8]** | §4 | An implementation MUST define each class below as a constant-time membership test … |
| **[GRAM-9]** | §4 | A code point is a C0 control or space if and only … |
| **[GRAM-10]** | §4 | A code point is an ASCII tab or newline if and only … |
| **[GRAM-11]** | §4 | A code point is a forbidden host code point if and only … |
| **[GRAM-12]** | §4 | A code point is a forbidden domain code point if and only … |
| **[GRAM-13]** | §4 | In the Url profile, after IDNA ToASCII has produced an ASCII domain … |
| **[GRAM-14]** | §4 | The URL code points are: the ASCII alphanumerics (ALPHA and DIGIT as … |
| **[GRAM-15]** | §4 | A noncharacter, for the purpose of [GRAM-14], is any of U+FDD0–U+FDEF inclusive … |
| **[GRAM-16]** | §4 | The URL code points class is advisory, not a gate: in the … |
| **[GRAM-17]** | §4 | An implementation MUST NOT define the contents of these four sets locally … |
| **[GRAM-18]** | §4 | Profile governance is absolute for acceptance. |
| **[GRAM-19]** | §4 | Membership in unreserved MUST be treated identically by both profiles: a code … |
| **[GRAM-20]** | §4 | In the Uri profile, a reg-name host is governed solely by the … |
| **[GRAM-21]** | §4 | The backslash U+005C (\) is a forbidden host code point in the … |
| **[GRAM-22]** | §4 | The definitions of ALPHA and DIGIT in §4.1.7 ([GRAM-5]) are shared verbatim … |
| **[GRAM-23]** | §4 | Percent-encoding hexadecimal is case-insensitive on input in both profiles ([GRAM-6]) and is … |
| **[GRAM-24]** | §4 | ASCII tab and newline (§4.2.2) and leading/trailing C0-control-or-space (§4.2.1) are removed or … |
| **[HOST-1]** | §7 | The host substring SHALL be delimited from the port and the rest … |
| **[HOST-2]** | §7 | When scanning for the delimiter, an unescaped [ opens a bracketed range … |
| **[HOST-3]** | §7 | Host dispatch SHALL select the parsing branch by the following ordered tests … |
| **[HOST-4]** | §7 | In the Url profile, a host beginning with [ MUST be parsed … |
| **[HOST-5]** | §7 | In the Uri profile, a host beginning with [ is an IP-literal … |
| **[HOST-6]** | §7 | The IPv6 parser SHALL maintain a current piece index (initially 0) and … |
| **[HOST-7]** | §7 | If the contents are empty, parsing MUST fail. |
| **[HOST-8]** | §7 | If the contents begin with :, the next code point MUST also … |
| **[HOST-9]** | §7 | A : encountered where a piece is expected denotes zero-run compression. |
| **[HOST-10]** | §7 | A piece is a run of 1 to 4 ASCII hex digits … |
| **[HOST-11]** | §7 | Leading zeros within a hex piece are permitted on input (e.g. |
| **[HOST-12]** | §7 | Embedded IPv4 trailer. If a . is encountered after one or more … |
| **[HOST-13]** | §7 | Each embedded-IPv4 octet MUST satisfy all of the following, else parsing MUST … |
| **[HOST-14]** | §7 | After successful piece/embedded-IPv4 scanning: if the compression marker is set, the pieces … |
| **[HOST-15]** | §7 | An Ipv6 host MUST be serialized to its RFC 5952 canonical textual … |
| **[HOST-16]** | §7 | When the compression replaces a run that begins at piece index 0 … |
| **[HOST-17]** | §7 | The zone-id opt-in is scoped to the Uri profile; the Url profile always rejects a zone identifier … |
| **[HOST-18]** | §7 | Implementations MUST provide an explicit opt-in flag enabling RFC 6874 zone identifiers for the Uri profile. |
| **[HOST-19]** | §7 | The ends-in-a-number test SHALL operate on the host string with a single … |
| **[HOST-20]** | §7 | When the ends-in-a-number test is positive, the host MUST be parsed as … |
| **[HOST-21]** | §7 | The IPv4 number parser SHALL: |
| **[HOST-22]** | §7 | Width-aware overflow. Let n be the number of parts (1 ≤ n … |
| **[HOST-23]** | §7 | Non-decimal or zero-padded part representations (hex, octal, multi-octet packing) are accepted in … |
| **[HOST-24]** | §7 | In the Uri profile there is no IPv4 shorthand, hex, or octal form. |
| **[HOST-25]** | §7 | An Ipv4 host MUST be serialized as dotted-decimal: the four octets of … |
| **[HOST-26]** | §7 | The domain-to-ASCII pipeline SHALL, in order: |
| **[HOST-27]** | §7 | Fast path. An input that, after ASCII-lowercasing, contains no forbidden-domain code point … |
| **[HOST-28]** | §7 | UTS-46 processing options for the Url profile SHALL be: Transitional_Processing = false … |
| **[HOST-29]** | §7 | After the ASCII domain is produced, the parser MUST re-run the ends-in-a-number … |
| **[HOST-30]** | §7 | Mandatory forbidden-domain re-scan. After ToASCII completes, the parser MUST scan the resulting … |
| **[HOST-31]** | §7 | DNS length limits. Each label MUST be 1 to 63 octets and … |
| **[HOST-32]** | §7 | In the Uri profile, registered names are not processed through IDNA and … |
| **[HOST-33]** | §7 | The pipeline SHALL first scan the input for forbidden host code points … |
| **[HOST-34]** | §7 | The pipeline SHALL then UTF-8 percent-encode the input using the C0 control … |
| **[HOST-35]** | §7 | Profile-dependent classification of the result: |
| **[HOST-36]** | §7 | A code point is a forbidden host code point if and only … |
| **[HOST-37]** | §7 | A code point is a forbidden domain code point if and only … |
| **[HOST-38]** | §7 | In the Url profile, for a special scheme other than file, an … |
| **[HOST-39]** | §7 | In the Url profile, for the file scheme, an empty host is … |
| **[HOST-40]** | §7 | In the Url profile, for a non-special scheme, an empty host is … |
| **[HOST-41]** | §7 | In the Uri profile, an empty authority and an empty host are … |
| **[HOST-42]** | §7 | In the Uri profile, an IP-literal whose bracket contents begin with v … |
| **[HOST-43]** | §7 | In the Url profile, IPvFuture is not recognized: a bracketed literal that … |
| **[HOST-44]** | §7 | An IpFuture host MUST be serialized within an authority by wrapping its … |
| **[HOST-45]** | §7 | Every successful host parse MUST map to exactly one Host variant per … |
| **[HOST-46]** | §7 | When serializing a Host back into an authority string, brackets MUST be … |
| **[HOST-47]** | §7 | When parsing an opaque host in the Url profile, the parser MUST record (non-fatally) invalid-URL-unit validation errors for a non-URL-code-point/non-% code point and for a % not followed by two hex digits. |
| **[HOST-48]** | §7 | In the Url profile, for already-ASCII domain input the UTS-46 ToASCII step runs for validation errors only: a validity failure is non-fatal and the ASCII domain is the input lowercased. |
| **[INTRO-1]** | §1 | A conforming implementation MUST implement the parsing algorithm of §8, the host-parsing … |
| **[INTRO-2]** | §1 | A conforming implementation MUST expose, at layer 2, the public types and … |
| **[INTRO-3]** | §1 | This specification governs textual identifier syntax, structure, and serialization only. |
| **[INTRO-4]** | §1 | A ParseProfile value MUST be one of exactly two members: URI and URL. |
| **[INTRO-5]** | §1 | The conversion Url.toUri() MUST be total (it MUST succeed for every well-formed … |
| **[INTRO-6]** | §1 | An implementation conforms to this specification if and only if it satisfies … |
| **[INTRO-7]** | §1 | An implementation that deviates from any SHOULD or SHOULD NOT requirement MUST … |
| **[INTRO-8]** | §1 | A claim of conformance MUST identify the conformance class claimed (per the … |
| **[INTRO-9]** | §1 | An implementation claiming the fully-conformant class MUST satisfy [INTRO-1] (single shared engine) … |
| **[INTRO-10]** | §1 | Internationalized host processing MUST be implemented per [UTS46] (ToASCII and ToUnicode) together … |
| **[INTRO-11]** | §1 | ABNF grammar appearing in this specification MUST be read per [RFC5234]. |
| **[INTRO-16]** | §1 | The Url profile SHOULD receive implementation and conformance-testing priority; this usage priority does not alter the §1.3.1 precedence of authorities (RFC 3986 supreme; WHATWG behaviours authoritative only as registered Appendix B deviations). |
| **[MODEL-1]** | §3 | A parsed value MUST be modelled as an immutable record of exactly … |
| **[MODEL-2]** | §3 | The model is the same record shape for both profiles. |
| **[MODEL-3]** | §3 | Every component value held in the record MUST be in the *stored … |
| **[MODEL-4]** | §3 | An implementation MUST NOT use an empty string as a sentinel for … |
| **[MODEL-5]** | §3 | The following states MUST be mutually distinguishable in the model and MUST … |
| **[MODEL-6]** | §3 | In the Url (EAGER) profile, canonicalization MAY collapse certain present-empty states during … |
| **[MODEL-7]** | §3 | scheme MUST be modelled as String?. |
| **[MODEL-8]** | §3 | In the Uri profile, scheme MAY be null; a null scheme denotes … |
| **[MODEL-9]** | §3 | The Uri public type MUST expose scheme as String?. |
| **[MODEL-10]** | §3 | Userinfo MUST be modelled as two independent components, user: String? and password … |
| **[MODEL-11]** | §3 | The four reachable userinfo states MUST be distinguishable: |
| **[MODEL-12]** | §3 | A combined userInfo: String? projection MAY be exposed. |
| **[MODEL-13]** | §3 | A non-null password with a null user is not a representable state … |
| **[MODEL-14]** | §3 | The host component MUST be modelled as Host?, where Host is a … |
| **[MODEL-15]** | §3 | host == null denotes no authority component (there was no //). |
| **[MODEL-16]** | §3 | RegName.ascii MUST store the host as an already-canonical ASCII registered name: lowercased … |
| **[MODEL-17]** | §3 | Ipv4.value MUST store the address as a single 32-bit quantity (held in … |
| **[MODEL-18]** | §3 | Ipv6.pieces MUST store the eight 16-bit groups of the address as fixed-length … |
| **[MODEL-19]** | §3 | Ipv6.zoneId MUST be modelled as String?, default null (no zone id). |
| **[MODEL-20]** | §3 | IpFuture MUST store the vN.… literal content (without brackets) and is reachable … |
| **[MODEL-21]** | §3 | Opaque.encoded holds a non-special host that is neither an IP literal nor … |
| **[MODEL-22]** | §3 | Public APIs MUST surface the host kind via exhaustive when over the … |
| **[MODEL-23]** | §3 | port MUST be modelled as Int?. |
| **[MODEL-24]** | §3 | A companion derived accessor effectivePort: Int MUST be provided. |
| **[MODEL-25]** | §3 | In the Url profile, when the input port equals the default port … |
| **[MODEL-26]** | §3 | path MUST always be present (never null). |
| **[MODEL-27]** | §3 | The path model MUST distinguish: |
| **[MODEL-28]** | §3 | An opaque path (a scheme-specific path with no authority, e.g. mailto:user@example.com, urn:isbn:…) … |
| **[MODEL-29]** | §3 | In the Url profile, a special-scheme value with an empty input path … |
| **[MODEL-30]** | §3 | query MUST be modelled as a raw String? holding the encoded query … |
| **[MODEL-31]** | §3 | A structured QueryParameters view MUST be obtainable from a value (e.g. |
| **[MODEL-32]** | §3 | The Uri public type primarily exposes the raw query; the QueryParameters view … |
| **[MODEL-33]** | §3 | fragment MUST be modelled as String? holding the encoded fragment content without … |
| **[MODEL-34]** | §3 | authority, userInfo, origin, the encoded component getters (encodedPath, etc.), and the decoded … |
| **[MODEL-35]** | §3 | Uri and Url MUST be immutable value types. |
| **[MODEL-36]** | §3 | Each type MUST be constructed via a private primary constructor plus a … |
| **[MODEL-37]** | §3 | Each type MUST provide a newBuilder(): Builder instance method that returns a … |
| **[MODEL-38]** | §3 | Builder.build() MUST validate the assembled components and MUST produce either a valid … |
| **[MODEL-39]** | §3 | Builders SHOULD provide paired decoded/encoded setters for components that have both forms (e.g. |
| **[MODEL-40]** | §3 | Uri and Url MUST implement value equality and hashCode consistently with one … |
| **[MODEL-41]** | §3 | Url.toUri(): Uri MUST be provided and is near-lossless: every Url maps to … |
| **[MODEL-42]** | §3 | Uri.toUrl(): ParseResult<Url> MUST be provided and may fail, returning ParseResult.Err (§12) when … |
| **[MODEL-43]** | §3 | The pair of conversions is not required to be a round-trip identity … |
| **[MODEL-44]** | §3 | The public model MUST NOT use java.util.Optional (or OptionalInt/OptionalLong) for any component or accessor. |
| **[MODEL-45]** | §3 | The public model MUST NOT wrap component String values in a @JvmInline … |
| **[MODEL-46]** | §3 | Nullable public accessors that distinguish absence (per §3.2) MUST be annotated such … |
| **[MODEL-47]** | §3 | In the Url profile, a value cannot have a username/password/port when its host is null/empty or its scheme is file; the Builder MUST NOT produce such components. |
| **[MODEL-48]** | §3 | A value includes credentials when its user or password is non-empty; the §11 userinfo prefix is emitted for a Url only when it includes credentials. |
| **[MODEL-49]** | §3 | In the Url profile the reachable Host variant is constrained by scheme class per the WHATWG scheme/host combination table (non-special schemes never carry a domain or IPv4 host). |
| **[NORM-1]** | §11 | In the Url profile, the operations marked "always" in the table below … |
| **[NORM-2]** | §11 | In the Uri profile, an implementation MUST NOT apply any operation in … |
| **[NORM-3]** | §11 | The Uri normalize() operations MUST be composable and order-independent in their observable … |
| **[NORM-4]** | §11 | Scheme case Always lowercased Always lowercased The scheme is case-insensitive (RFC 3986 … |
| **[NORM-5]** | §11 | Host case / IDNA Always: the full host pipeline (§7) runs, ASCII … |
| **[NORM-6]** | §11 | Percent-triplet case Always uppercased on output (the two hex digits of every … |
| **[NORM-7]** | §11 | Percent triplets in host MUST NOT be lowercased Triplets are uppercased like … |
| **[NORM-8]** | §11 | Decode unreserved-but-encoded octets (%41→A) Not performed: the Url profile does NOT decode … |
| **[NORM-9]** | §11 | Dot-segment removal Always: the remove_dot_segments algorithm (§9) is applied to the encoded … |
| **[NORM-10]** | §11 | Default-port elision Always at serialize time: when effectivePort equals the scheme default … |
| **[NORM-11]** | §11 | Empty special path → / Always: in the Url profile, if the … |
| **[NORM-12]** | §11 | Backslash → slash Always for special schemes: U+005C (\) occurring where a … |
| **[NORM-13]** | §11 | + as space Form-layer only: +↔space conversion belongs exclusively to application/x-www-form-urlencoded decoding/encoding … |
| **[NORM-14]** | §11 | Normalization MUST NOT alter the meaning-bearing distinctions of the data model: it … |
| **[NORM-15]** | §11 | An implementation MUST serialize a value by the following recomposition, in this … |
| **[NORM-16]** | §11 | The authority MUST be serialized as [ userinfo "@" ] host [ … |
| **[NORM-17]** | §11 | An implementation MUST NOT emit // unless the value has an authority … |
| **[NORM-18]** | §11 | Leading-/. guard. When the value has no authority (Host is null), scheme … |
| **[NORM-19]** | §11 | Equality MUST be a pure function of the in-memory value. |
| **[NORM-20]** | §11 | A Url MUST define equals as equality of its canonical href: two … |
| **[NORM-21]** | §11 | A Uri MUST define structural equals/hashCode over its canonical-but-unnormalized serialization: the §11.2 … |
| **[NORM-22]** | §11 | A Uri MUST additionally provide a normalization-aware comparison normalizedEquals(other) and a normalized() … |
| **[NORM-23]** | §11 | Both Url and Uri MUST be safe to use as keys in … |
| **[NORM-24]** | §11 | Parse∘serialize stability. For any value v of either type, parse_P(serialize(v)) MUST succeed … |
| **[NORM-25]** | §11 | Serialization idempotency. serialize MUST be idempotent in the sense that re-serializing an … |
| **[NORM-26]** | §11 | Normalization idempotency. For a Uri, applying the same normalize() selection twice MUST … |
| **[NORM-27]** | §11 | Builder round-trip. For any value v, v.newBuilder().build() MUST produce a value equal … |
| **[NORM-28]** | §11 | Empty-reference resolution stability. For any value v, resolving the empty relative reference … |
| **[NORM-29]** | §11 | Url.toUri() MUST be total (it always succeeds) and near-lossless: it produces a … |
| **[NORM-30]** | §11 | In the Url profile, userinfo serialization MUST follow the WHATWG serializer: credentials emitted only when the URL includes credentials; the password colon emitted only when the password is non-empty. |
| **[NORM-31]** | §11 | The serializer SHOULD accept an optional excludeFragment boolean (default false) that skips the fragment step, mirroring the WHATWG exclude-fragment parameter. |
| **[NORM-30]** | §11 | Uri.toUrl() MUST be fallible and MUST return ParseResult<Url> (Ok/Err with UriParseError), never … |
| **[NORM-31]** | §11 | Bridge consistency. For any Url value u, u.toUri().toUrl() MUST return Ok(u') with … |
| **[NORM-32]** | §11.6 | In the Url profile, origin returns the ASCII serialization of the WHATWG origin: a tuple scheme://host[:port] for a special scheme other than file (port only when non-null, no userinfo); for blob, the origin of the URL parsed from the path when its inner scheme is http/https/file, else opaque; file and every non-special scheme are opaque; an opaque origin serializes as "null". A derived projection ([MODEL-34]), not stored, not guaranteed to round-trip. |
| **[PARSE-1]** | §8 | The parser MUST enforce a fixed maximum input length (the configured limit … |
| **[PARSE-2]** | §8 | After the state loop completes, if normalization (percent-encoding, IDNA, default-port elision, dot-segment … |
| **[PARSE-3]** | §8 | In the Url profile, before any other processing, the parser MUST remove … |
| **[PARSE-4]** | §8 | In the Uri profile, the parser MUST NOT remove tab, LF, or … |
| **[PARSE-5]** | §8 | In the Url profile, after tab/newline removal, the parser MUST remove all … |
| **[PARSE-6]** | §8 | In the Uri profile, the parser MUST NOT trim leading or trailing … |
| **[PARSE-7]** | §8 | After the trim steps, the parser MUST locate the FIRST U+0023 (#) … |
| **[PARSE-8]** | §8 | The presence of a # makes the fragment component defined even when … |
| **[PARSE-9]** | §8 | The parser MUST maintain a single index pos, initialized to 0, that … |
| **[PARSE-10]** | §8 | The loop condition MUST be pos <= len. |
| **[PARSE-11]** | §8 | The WHATWG "decrease pointer by one" instruction MUST be emulated by re-entering … |
| **[PARSE-12]** | §8 | In the Uri profile the predicate *is special* MUST always evaluate to … |
| **[PARSE-13]** | §8 | If c is an ASCII alpha (A–Z, a–z): set state to SCHEME … |
| **[PARSE-14]** | §8 | While c is an ASCII alphanumeric, U+002B (+), U+002D (-), or U+002E (.): advance. |
| **[PARSE-15]** | §8 | If c is U+003A (:): the slice [0, pos) is the scheme. |
| **[PARSE-16]** | §8 | Otherwise (c is not : and not a valid scheme code point … |
| **[PARSE-17]** | §8 | If there is no base URL, OR the base URL has an … |
| **[PARSE-18]** | §8 | If the base URL has an opaque path and the entire remaining … |
| **[PARSE-19]** | §8 | In the Uri profile, the parser MUST NOT perform in-state base merging here. |
| **[PARSE-20]** | §8 | If c is U+002F (/) and the next code point is U+002F … |
| **[PARSE-21]** | §8 | If c is U+002F (/): set state to AUTHORITY and advance. |
| **[PARSE-22]** | §8 | If c is U+002F (/): set state to RELATIVE SLASH and advance. |
| **[PARSE-23]** | §8 | If the scheme is special and c is U+002F (/) or U+005C … |
| **[PARSE-24]** | §8 | If c is U+002F (/) and the next code point is U+002F … |
| **[PARSE-25]** | §8 | While c is U+002F (/) or U+005C (\): advance, recording one ValidationError … |
| **[PARSE-26]** | §8 | The parser MUST scan forward to the *authority delimiter*: the first code … |
| **[PARSE-27]** | §8 | If the delimiter code point is U+0040 (@): apply the userinfo accumulation … |
| **[PARSE-28]** | §8 | Otherwise (delimiter is /, ?, special-scheme \, or EOF): if an @ … |
| **[PARSE-29]** | §8 | The parser MUST scan for the *host delimiter*, the first code point … |
| **[PARSE-30]** | §8 | If the located delimiter is U+003A (:) found outside brackets: the slice … |
| **[PARSE-31]** | §8 | Otherwise (delimiter is /, ?, special-scheme \, or EOF): if the host … |
| **[PARSE-32]** | §8 | The parser MUST consume the maximal run of ASCII digits beginning at … |
| **[PARSE-33]** | §8 | The accumulated digits MUST be interpreted as a base-10 integer. |
| **[PARSE-34]** | §8 | In the Url profile, if the parsed port equals the default port … |
| **[PARSE-35]** | §8 | If c is U+002F (/) or U+005C (\): (recording *invalid-reverse-solidus* if \) … |
| **[PARSE-36]** | §8 | If c is U+002F (/) or U+005C (\): (recording *invalid-reverse-solidus* if \) … |
| **[PARSE-37]** | §8 | Scan to the first of /, \, ?, or EOF; the slice … |
| **[PARSE-38]** | §8 | If the scheme is special (Url): set state to PATH. |
| **[PARSE-39]** | §8 | The parser MUST scan to the next U+003F (?) or EOF. |
| **[PARSE-40]** | §8 | Each path segment MUST be percent-encoded with the path percent-encode set of … |
| **[PARSE-41]** | §8 | The result is marked as having an opaque path. |
| **[PARSE-42]** | §8 | The query is the remaining input up to EOF (the fragment was … |
| **[PARSE-43]** | §8 | The fragment component captured by [PARSE-7] MUST, in the Url profile, be … |
| **[PARSE-44]** | §8 | Within the authority, the boundary between userinfo and host is the LAST … |
| **[PARSE-45]** | §8 | The first U+003A (:) within the userinfo splits it into username (before … |
| **[PARSE-46]** | §8 | The parser MUST track two flags while scanning authority segments: *at-sign-seen* and *password-token-seen*. |
| **[PARSE-47]** | §8 | The percent-encode set applied to username and password content is the *userinfo … |
| **[PARSE-48]** | §8 | If @ was seen but the host candidate following the userinfo is … |
| **[PARSE-49]** | §8 | Backslash as solidus For special schemes, U+005C (\) MUST be treated as … |
| **[PARSE-50]** | §8 | Authority introduction Special schemes reach the authority via SPECIAL AUTHORITY SLASHES / … |
| **[PARSE-51]** | §8 | Special-scheme magic Default-port elision (§6), the file sub-machine, IDNA/host canonicalization, and IPv4 … |
| **[PARSE-52]** | §8 | Canonicalization timing EAGER: percent-encoding per-component encode sets, host pipeline, dot-segment removal, and … |
| **[PARSE-53]** | §8 | Pre-processing Strip tab/LF/CR anywhere; trim leading/trailing C0-or-space ([PARSE-3]/[PARSE-5]). |
| **[PARSE-54]** | §8 | A conformant implementation MUST gate every quirk in the table above solely … |
| **[PARSE-55]** | §8 | In the Url profile, a file scheme whose remaining input does not begin with // MUST record a special-scheme-missing-following-solidus validation error before transitioning to FILE. |
| **[PARSE-56]** | §8 | In the Url profile, each @ encountered in the AUTHORITY state MUST record an invalid-credentials validation error (CredentialsInAuthority). |
| **[PARSE-57]** | §8 | In the Url profile FILE file-base branch, a remaining input beginning with a Windows drive letter (the path-clearing branch) MUST record a file-invalid-Windows-drive-letter validation error. |
| **[PARSE-58]** | §8 | In the Url profile FILE HOST state, a buffer that is a Windows drive letter MUST record a file-invalid-Windows-drive-letter-host validation error before transitioning to PATH. |
| **[PARSE-59]** | §8 | In the Url profile, the PATH/OPAQUE PATH/QUERY/FRAGMENT states MUST record invalid-URL-unit validation errors for non-URL-code-points and for % not followed by two hex digits; the produced value is unchanged. |
| **[PATH-1]** | §9 | A list path SHALL be modelled as an ordered, index-addressable list of segments. |
| **[PATH-2]** | §9 | Empty segments are significant and MUST be preserved. |
| **[PATH-3]** | §9 | A trailing empty segment denotes a directory path. |
| **[PATH-4]** | §9 | Each segment exposes two views: an *encoded* view (the segment as it … |
| **[PATH-5]** | §9 | A %2F or %2f triplet inside a segment denotes a literal / … |
| **[PATH-6]** | §9 | The encoded and decoded views compose without double-encoding ambiguity. |
| **[PATH-7]** | §9 | removeDotSegments(input) SHALL be computed by the following algorithm. |
| **[PATH-8]** | §9 | Rule C MUST clamp on overshoot: removing the last segment from an … |
| **[PATH-9]** | §9 | During dot-segment removal performed as part of reference resolution (§9.7), the triplets … |
| **[PATH-10]** | §9 | The treatment in [PATH-9] is confined to resolution/normalization. |
| **[PATH-11]** | §9 | setPathSegment(i, value) SHALL reject a *decoded* value equal to . |
| **[PATH-12]** | §9 | In the Url profile, when the scheme is special (§6, SCH), every … |
| **[PATH-13]** | §9 | Backslash conversion under [PATH-12] is a parse-time rewrite: the resulting segments contain … |
| **[PATH-14]** | §9 | A *Windows drive letter* is two code points: an ASCII alpha followed … |
| **[PATH-15]** | §9 | When the scheme is file, the path is empty, and a segment … |
| **[PATH-16]** | §9 | Dot-segment removal (shorten_path) MUST NOT remove a sole drive-letter segment. |
| **[PATH-17]** | §9 | In the Url profile, a URL with a scheme but no authority … |
| **[PATH-18]** | §9 | An opaque path SHALL be percent-encoded using the C0-control percent-encode set (§5 … |
| **[PATH-19]** | §9 | Trailing U+0020 SPACE code points in an opaque path SHALL be stripped … |
| **[PATH-20]** | §9 | When a URI has an authority (the authority component is present, even … |
| **[PATH-21]** | §9 | When a URI has no authority, the path MUST NOT begin with … |
| **[PATH-22]** | §9 | A relative-reference path with no scheme and no authority (a *path-noscheme*) MUST … |
| **[PATH-23]** | §9 | The base URI *B* MUST have a scheme. |
| **[PATH-24]** | §9 | transformReferences(B, R) SHALL compute *T* as follows (RFC 3986 §5.2.2). "defined(X)" means … |
| **[PATH-25]** | §9 | merge(B, R) (RFC 3986 §5.2.3) SHALL be: if *B* has an authority … |
| **[PATH-26]** | §9 | The target *T* SHALL be recomposed (RFC 3986 §5.3) into a string … |
| **[PATH-27]** | §9 | Resolving the empty reference "" (no scheme, no authority, empty path, no … |
| **[PATH-28]** | §9 | In the Url profile, a reference whose scheme is present and equals … |
| **[PATH-29]** | §9 | The WHATWG same-scheme authority subtleties MUST be honored in the Url profile … |
| **[PATH-30]** | §9 | The following RFC 3986 §5.4 vectors, resolved against base http://a/b/c/d;p?q, are REQUIRED … |
| **[PCT-1]** | §5 | For every percent-encode set defined in §5.1 *except* the C0-control set, a … |
| **[PCT-2]** | §5 | Condition (d) is profile-dependent. In the Url profile every non-ASCII code point … |
| **[PCT-3]** | §5 | A code point not selected by [PCT-1] MUST be copied to the … |
| **[PCT-4]** | §5 | The handling of U+0025 (%) is not governed by a set's explicit list. |
| **[PCT-5]** | §5 | The C0-control percent-encode set has an empty explicit list. |
| **[PCT-6]** | §5 | The fragment percent-encode set explicit list MUST be exactly: |
| **[PCT-7]** | §5 | The query percent-encode set explicit list MUST be exactly: |
| **[PCT-8]** | §5 | The special-query percent-encode set explicit list MUST be exactly the query set … |
| **[PCT-9]** | §5 | The path percent-encode set explicit list MUST be exactly the query set … |
| **[PCT-10]** | §5 | The userinfo percent-encode set explicit list MUST be exactly the path set … |
| **[PCT-11]** | §5 | The userinfo set MUST be applied independently to the decoded username and … |
| **[PCT-12]** | §5 | The query-component percent-encode set (a single query name or a single query … |
| **[PCT-13]** | §5 | The application/x-www-form-urlencoded percent-encode set is defined by exclusion: a code point is … |
| **[PCT-14]** | §5 | Within the form set, and only within the form set: |
| **[PCT-15]** | §5 | The form set is a serializer concern of the form layer (cross-ref … |
| **[PCT-16]** | §5 | The host component MUST NOT be processed through any percent-encode set defined … |
| **[PCT-17]** | §5 | percentEncode(input, set, alreadyEncoded) takes a Unicode string input, a percent-encode set set … |
| **[PCT-18]** | §5 | A code point selected for encoding MUST first be encoded to octets … |
| **[PCT-19]** | §5 | Hexadecimal digits in emitted triplets MUST be uppercase (%XX, digits 0–9 and A–F). |
| **[PCT-20]** | §5 | The encoder MUST provide a zero-allocation fast path: it scans input for … |
| **[PCT-21]** | §5 | The encoder MUST be total over all input strings: every code point … |
| **[PCT-22]** | §5 | percentDecode(input) scans input left to right and produces an octet stream as follows. |
| **[PCT-23]** | §5 | A % that is not followed by two hexadecimal digits — including … |
| **[PCT-24]** | §5 | Hexadecimal-digit parsing in decode MUST be case-insensitive: %7a, %7A, %6d, and %6D … |
| **[PCT-25]** | §5 | Consecutive triplets MUST be gathered into a contiguous run of octets before … |
| **[PCT-26]** | §5 | When producing a String, an octet (or octet subsequence) that is not … |
| **[PCT-27]** | §5 | Lossy U+FFFD substitution applies only to the String view. |
| **[PCT-28]** | §5 | Plain percentDecode MUST NOT treat + as space. |
| **[PCT-29]** | §5 | Percent-encoded triplets are case-insensitive in meaning (PCT-24) but their normalized serialized form … |
| **[PCT-30]** | §5 | The parser MUST NOT rewrite triplet case while parsing. |
| **[PCT-31]** | §5 | In the Uri profile (PRESERVE by default), triplet case MUST survive verbatim … |
| **[PCT-32]** | §5 | In the Url profile (EAGER canonicalization), triplet case is normalized to uppercase … |
| **[PCT-33]** | §5 | Triplet case normalization MUST NOT decode-then-re-encode existing triplets. |
| **[PCT-34]** | §5 | When alreadyEncoded = false (the input is raw data), every literal % … |
| **[PCT-35]** | §5 | When alreadyEncoded = true (the input is already percent-encoded), a % that … |
| **[PCT-36]** | §5 | Re-encoding already-encoded text MUST NOT double-encode a valid triplet. |
| **[PCT-37]** | §5 | The encode operation MUST be idempotent over the already-encoded flag: for every … |
| **[PCT-38]** | §5 | The alreadyEncoded flag controls only the treatment of % (PCT-34/PCT-35). |
| **[PCT-39]** | §5 | Tab (U+0009), line feed (U+000A), form feed (U+000C), and carriage return (U+000D) … |
| **[PCT-40]** | §5 | The component percent-encode set (encodeURIComponent equivalent) MUST be the userinfo set plus $ & + , (and forces %); distinct from the okhttp query-component set. |
| **[QUERY-1]** | §10 | A Uri/Url MUST distinguish three states of the query component, and the … |
| **[QUERY-2]** | §10 | The raw query spans from the code point after the leading ? … |
| **[QUERY-3]** | §10 | Query serialization is profile-dependent: |
| **[QUERY-4]** | §10 | When a query is present (non-null), serialization MUST emit the ? delimiter … |
| **[QUERY-5]** | §10 | QueryParameters MUST be an ordered, duplicate-preserving, case-sensitive list of (name, value?) pairs … |
| **[QUERY-6]** | §10 | QueryParameters MUST be derived from the raw query string S (the component … |
| **[QUERY-7]** | §10 | Within the QueryParameters derivation, the + code point MUST be treated as … |
| **[QUERY-8]** | §10 | The split algorithm fixes the following value sentinels, all of which MUST … |
| **[QUERY-9]** | §10 | size(): Int MUST return the total pair count (counting duplicates). |
| **[QUERY-10]** | §10 | nameAt(index): String MUST return the decoded name of the pair at index … |
| **[QUERY-11]** | §10 | get(name): String? MUST return the decoded value of the first pair whose … |
| **[QUERY-12]** | §10 | getAll(name): List<String?> MUST return the decoded values of all pairs whose decoded … |
| **[QUERY-13]** | §10 | names(): Set<String> MUST return the distinct decoded names in first-appearance (insertion) order. |
| **[QUERY-14]** | §10 | QueryParameters MUST be a snapshot value: it is an immutable copy that … |
| **[QUERY-15]** | §10 | add(name: String, value: String?): Builder MUST append a new pair (name, value) … |
| **[QUERY-16]** | §10 | set(name: String, value: String?): Builder MUST perform replace-first / remove-rest / keep-position … |
| **[QUERY-17]** | §10 | removeAll(name: String): Builder MUST remove every pair whose name equals name, preserving … |
| **[QUERY-18]** | §10 | sort(): Builder MUST perform a stable sort of the pair list by … |
| **[QUERY-19]** | §10 | Building a Uri/Url from a QueryParameters MUST produce a new value; QueryParameters … |
| **[QUERY-20]** | §10 | The form serializer MUST encode a sequence of (name, value) pairs to … |
| **[QUERY-21]** | §10 | The form parser MUST decode an application/x-www-form-urlencoded string as follows: split on … |
| **[QUERY-22]** | §10 | The form codec MUST use UTF-8 for all name/value text. |
| **[QUERY-23]** | §10 | Plus-as-space MUST be confined to the form dialect ([QUERY-20]/[QUERY-21]). |
| **[QUERY-24]** | §10 | Deriving QueryParameters (§10.2) and parsing form input (§10.4) MUST enforce a maximum parsed-pair count. |
| **[SCH-1]** | §6 | An implementation MUST recognize exactly the six schemes in Table 6-1 as special. |
| **[SCH-2]** | §6 | The default port of a special scheme MUST be exactly the value … |
| **[SCH-3]** | §6 | Scheme comparison against the registry MUST be performed on the normalized (lowercased … |
| **[SCH-4]** | §6 | The set of special schemes is closed. |
| **[SCH-5]** | §6 | Given a normalized scheme, the determination of whether it is special, and … |
| **[SCH-6]** | §6 | The "is special" determination MUST run in time independent of the number … |
| **[SCH-7]** | §6 | The first code point of a scheme MUST be an ASCII alpha. |
| **[SCH-8]** | §6 | Each code point of a scheme after the first MUST be an … |
| **[SCH-9]** | §6 | Scheme code points are never percent-decoded and never percent-encoded. |
| **[SCH-10]** | §6 | A scheme MUST be non-empty. |
| **[SCH-11]** | §6 | During parsing the scheme is delimited by the first : that follows … |
| **[SCH-12]** | §6 | In the Uri profile, a URI MAY be a relative reference with … |
| **[SCH-13]** | §6 | In the Url profile, a Url MUST have a scheme. |
| **[SCH-14]** | §6 | A scheme that satisfies Table 6-2 but is not in Table 6-1 … |
| **[SCH-15]** | §6 | An implementation MUST normalize the scheme to lowercase by mapping each ASCII … |
| **[SCH-16]** | §6 | Scheme normalization MUST be ASCII-only and MUST NOT apply any locale-sensitive case … |
| **[SCH-17]** | §6 | The lowercased scheme is the value stored in the data model and … |
| **[SCH-18]** | §6 | effectivePort MUST be computed as: the explicitly parsed port if one is … |
| **[SCH-19]** | §6 | In the Url profile, when an explicitly present port equals the default … |
| **[SCH-20]** | §6 | In the Uri profile, default-port elision MUST NOT occur by default; an … |
| **[SCH-21]** | §6 | A scheme whose value is absent ([SCH-12], Uri relative reference) has no … |
| **[SCH-22]** | §6 | For a non-special scheme, the host (if an authority is present) MUST … |
| **[SCH-23]** | §6 | For a non-special scheme, a URI/URL MAY have an opaque path (a … |
| **[SCH-24]** | §6 | For a non-special scheme, the authority-introduction and backslash rules of special schemes … |
| **[SCH-25]** | §6 | For a non-special scheme, default-port elision and empty-path-to-/ normalization MUST NOT be … |
| **[SCH-26]** | §6 | The Uri profile is scheme-agnostic: it applies no special-scheme behaviour to any … |
| **[TERM-1]** | §2 | Where this specification names a code-point class (for example "ASCII alpha" or … |
| **[TERM-2]** | §2 | Where this specification distinguishes profiles, an implementation MUST apply the behaviour prescribed … |
| **[TERM-3]** | §2 | An implementation MUST treat a fatal failure and a non-fatal validation error … |
| **[TERM-4]** | §2 | In the Url profile, when parsing succeeds despite one or more non-fatal … |
| **[TERM-5]** | §2 | In strict mode (§12), a condition classified in this specification as a … |
| **[TERM-6]** | §2 | In this specification's grammar, HEXDIG MUST be matched case-insensitively (both A–F and … |
| **[TERM-7]** | §2 | An implementation of an algorithm specified in this document MUST produce, for … |

---

## Appendix B — Deviations from RFC 3986

This appendix is the **complete and exhaustive** registry of the sanctioned departures of the `Url` profile (`ParseProfile.URL`) from **[RFC3986]**. Each entry adopts **[WHATWG-URL]** behaviour for web-URL compatibility and is authoritative only to the extent and in the form recorded here, and only under `ParseProfile.URL`. The `Uri` profile (`ParseProfile.URI`) has **no** deviations: it MUST conform to **[RFC3986]** without exception. Per the precedence rules of §1.3 ([INTRO-12]–[INTRO-15]), **any** contradiction between this specification and **[RFC3986]** that is **not** listed in this appendix is a specification defect, not a sanctioned deviation, and MUST be resolved in favour of **[RFC3986]**.

Each deviation is given a stable **[DEV-n]** identifier. The columns are: the RFC 3986 clause, what RFC 3986 requires, the `Url`-profile (WHATWG) behaviour, the spec requirement tags that embody the deviation, and the justification.

### [DEV-1] Backslash treated as solidus in special-scheme URLs

| Field | Value |
|---|---|
| **RFC 3986 clause** | §3.2 / §3.3 / §2.1 |
| **What RFC 3986 requires** | `\` (U+005C) is not a member of `pchar`, `reg-name`, or any path/authority delimiter; only `/` separates path segments and introduces the authority (preceded by `//`). A backslash within a component is ordinary data that must be percent-encoded (`%5C`). |
| **`Url`-profile behaviour (WHATWG)** | For special schemes, `\` is treated as `/` wherever `/` is a delimiter (slash run after `scheme:`, authority terminator, path-segment separator, FILE/FILE SLASH/FILE HOST), and `\` is a forbidden host code point; each occurrence records an `invalid-reverse-solidus` validation error. |
| **Spec requirement tags** | [GRAM-21], [GRAM-11], [PATH-12], [PATH-13], [NORM-12], [PARSE-49], [PARSE-12], [HOST-1], [PARSE-22], [PARSE-23], [PARSE-35], [PARSE-36], [PARSE-38], [PARSE-39] |
| **Justification** | WHATWG URL Standard / web compatibility: browsers normalize backslashes to forward slashes in special-scheme URLs so that `http:\\host\path` resolves. The `Uri` profile never rewrites `\`. |

### [DEV-2] Slash-run collapsing and lenient authority introduction for special schemes

| Field | Value |
|---|---|
| **RFC 3986 clause** | §3.2 / §5.2.2 |
| **What RFC 3986 requires** | The authority is preceded by exactly a double slash (`//`); a third slash begins the path. The number and kind of slashes is fixed by the ABNF; `http:host/p` and `http:/host/p` do not carry an authority. |
| **`Url`-profile behaviour (WHATWG)** | Special schemes collapse ANY run of `/` and `\` after `scheme:` (0, 1, 2, or more all reach the authority via SPECIAL AUTHORITY SLASHES / IGNORE SLASHES), recording `special-scheme-missing-following-solidus` errors. |
| **Spec requirement tags** | [PARSE-50], [PARSE-24], [PARSE-25], [PARSE-20], [PATH-28], [PATH-29] |
| **Justification** | WHATWG URL Standard / web compatibility: arbitrary leading slash runs after a special scheme collapse so that `http:/example.com` and `http:////example.com` resolve to the same authority. The `Uri` profile requires exactly `//` with no collapsing. |

### [DEV-3] Port capped at 0..65535 (16-bit)

| Field | Value |
|---|---|
| **RFC 3986 clause** | §3.2.3 |
| **What RFC 3986 requires** | `port = *DIGIT` — any sequence of decimal digits with no upper numeric bound; e.g. `:99999` and `:000000000000` satisfy the ABNF as written. This is a purely syntactic judgement about the grammar, not a claim that kuri retains the literal digits (kuri parses the run to an `Int`; see Justification). |
| **`Url`-profile behaviour (WHATWG)** | A port whose numeric value exceeds 65535 is a fatal `port-out-of-range` parse failure, matching the WHATWG 16-bit (TCP/UDP) port model. |
| **Spec requirement tags** | [MODEL-23], [PARSE-33], [PARSE-34], [ERR-35] |
| **Justification** | Web compatibility: the WHATWG basic URL parser fails on port > 65535. The `Uri` profile accepts any `*DIGIT` port whose value fits `Int` and stores it as its decimal `Int` value (so `:000000000000` parses to port `0`); a value above `Int.MAX_VALUE` is rejected as `InvalidPort`. |

### [DEV-4] Eager default-port elision

| Field | Value |
|---|---|
| **RFC 3986 clause** | §6.2.3 |
| **What RFC 3986 requires** | Eliding a port equal to the scheme default is an OPTIONAL scheme-based normalization (a SHOULD for normalizers), not a parse-time requirement; a stated default port is otherwise preserved. |
| **`Url`-profile behaviour (WHATWG)** | When the input port equals the scheme default, canonicalization eagerly elides it at parse/build time so the produced `Url` has `port == null`. |
| **Spec requirement tags** | [MODEL-25], [NORM-10], [PARSE-34] |
| **Justification** | Web compatibility: every WHATWG URL has a single canonical serialization with default ports removed. The `Uri` profile applies no default-port elision. |

### [DEV-5] IPv4 non-decimal / shorthand / overflow parsing

| Field | Value |
|---|---|
| **RFC 3986 clause** | §3.2.2 |
| **What RFC 3986 requires** | `IPv4address = dec-octet "." dec-octet "." dec-octet "." dec-octet` — exactly four decimal octets 0–255, no leading zeros; only the dotted-decimal form is allowed by the grammar (other numeric forms are `reg-name`). |
| **`Url`-profile behaviour (WHATWG)** | Special schemes apply an ends-in-a-number test then parse 1–4 parts with per-part radix (hex `0x`, octal leading `0`, decimal), width-aware overflow packing, accept shorthand/hex/octal/zero-padded forms as non-fatal validation errors, and re-serialize canonically to dotted-decimal. |
| **Spec requirement tags** | [HOST-19], [HOST-20], [HOST-21], [HOST-22], [HOST-23] |
| **Justification** | Web compatibility: browsers historically accept legacy IPv4 numeric host forms (hex, octal, fewer-than-four parts, 32-bit integers). The `Uri` profile requires exactly four dotted-decimal octets. |

### [DEV-6] IDNA / UTS-46 + Punycode host mapping and host lowercasing

| Field | Value |
|---|---|
| **RFC 3986 clause** | §3.2.2 |
| **What RFC 3986 requires** | `reg-name = *( unreserved / pct-encoded / sub-delims )`; non-ASCII must be UTF-8 percent-encoded and the registered name is otherwise preserved (case-insensitive; producers SHOULD lowercase). RFC mandates no IDNA/Punycode/NFC transform at parse time; IDN is out of RFC 3986 scope. |
| **`Url`-profile behaviour (WHATWG)** | Special-scheme non-IP hosts run the full UTS-46 ToASCII pipeline (UTF-8 percent-decode, map/ignore/disallow, NFC, Punycode `xn--` encode/decode, RFC 5891 label validation), are ASCII-lowercased and length-checked; the stored RegName is the lowercase ASCII domain. (Web-compatibility soft-fail: for already-ASCII domain input the ToASCII step is run for validation errors only — a pure UTS-46 validity failure is recorded as a `ValidationError` and is non-fatal, and the resulting ASCII domain is the input lowercased rather than the ToASCII output, per [HOST-48].) |
| **Spec requirement tags** | [HOST-26], [HOST-27], [HOST-28], [HOST-29], [HOST-30], [HOST-31], [HOST-48], [GRAM-20], [INTRO-10] |
| **Justification** | Web compatibility: WHATWG URL requires UTS-46 domain-to-ASCII and host lowercasing. The `Uri` profile applies no IDNA step to a host. |

### [DEV-7] Forbidden-host / forbidden-domain code-point tables replace the reg-name grammar

| Field | Value |
|---|---|
| **RFC 3986 clause** | §3.2.2 |
| **What RFC 3986 requires** | Registered names are validated by the positive production `reg-name = *( unreserved / pct-encoded / sub-delims )`, which permits all `sub-delims`. |
| **`Url`-profile behaviour (WHATWG)** | Url hosts are validated by negative code-point tables: the forbidden-host table for opaque (non-special) hosts and the strictly larger forbidden-domain table for IDNA domains, which differ from the RFC reg-name character set. |
| **Spec requirement tags** | [HOST-36], [HOST-37], [GRAM-11], [GRAM-12], [GRAM-13] |
| **Justification** | Web compatibility: WHATWG URL defines host validity via forbidden-host-code-point / forbidden-domain-code-point sets rather than the RFC reg-name ABNF. The `Uri` profile enforces the reg-name production (per the [HOST-33] `Uri` rule). |

### [DEV-8] Tab/LF/CR removal and leading/trailing C0-control-or-space trimming

| Field | Value |
|---|---|
| **RFC 3986 clause** | §2.1 / §3 |
| **What RFC 3986 requires** | RFC 3986 defines no whitespace/control stripping pass; embedded tab/newline/space and control characters are not members of the component productions and are syntax errors, never silently elided from input. |
| **`Url`-profile behaviour (WHATWG)** | Pre-processing removes every U+0009/U+000A/U+000D from anywhere in the input and trims all leading/trailing C0-control-or-space (U+0000–U+0020), each recorded as a non-fatal validation error. |
| **Spec requirement tags** | [PARSE-3], [PARSE-5], [PARSE-53], [GRAM-9], [GRAM-10], [GRAM-24] |
| **Justification** | Web compatibility: the WHATWG basic URL parser performs these removals to sanitize copy-pasted URLs. The `Uri` profile performs no such stripping. |

### [DEV-9] Special-scheme empty-host rejection and file localhost-to-empty rewriting

| Field | Value |
|---|---|
| **RFC 3986 clause** | §3.2.2 |
| **What RFC 3986 requires** | The generic syntax permits an empty host; §3.2.2 notes `file` treats no-authority, empty host, and `localhost` as equivalent without mandating that `localhost` be rewritten to the empty host. Empty-host validity beyond that is scheme-specific. |
| **`Url`-profile behaviour (WHATWG)** | For a special scheme other than `file` an empty host is a fatal host-missing error; for `file` an empty host is allowed and a host parsing to `localhost` MUST be replaced by the empty host. |
| **Spec requirement tags** | [HOST-38], [HOST-39] |
| **Justification** | Web compatibility: WHATWG URL forbids empty authorities for special schemes and normalizes `file://localhost/` to `file:///`. The `Uri` profile permits an empty host and applies no localhost rewriting. |

### [DEV-10] IPvFuture not supported in the `Url` profile

| Field | Value |
|---|---|
| **RFC 3986 clause** | §3.2.2 |
| **What RFC 3986 requires** | `IP-literal = "[" ( IPv6address / IPvFuture ) "]"`, with `IPvFuture = "v" 1*HEXDIG "." 1*( unreserved / sub-delims / ":" )`; a bracketed `[vX.…]` literal is valid. |
| **`Url`-profile behaviour (WHATWG)** | A bracketed literal that is not a valid IPv6 address (including any `[vX.…]` IPvFuture form) MUST fail; there is no IP-future branch and an `IpFuture` host never arises. |
| **Spec requirement tags** | [HOST-4], [HOST-43] |
| **Justification** | Web compatibility: the WHATWG host parser recognizes only IPv6 inside brackets and rejects IPvFuture. The `Uri` profile supports IPvFuture per [HOST-42]. |

### [DEV-11] Mandatory scheme (scheme-less input is not a relative reference)

| Field | Value |
|---|---|
| **RFC 3986 clause** | §4.1 |
| **What RFC 3986 requires** | `URI-reference = URI / relative-ref`; a `relative-ref` (`relative-part [ "?" query ] [ "#" fragment ]`) is a valid URI reference with NO scheme component. |
| **`Url`-profile behaviour (WHATWG)** | A `Url` MUST have a non-null scheme; with no base URL a scheme-less input fails with a missing-scheme error, and with a base URL the scheme is inherited via resolution. |
| **Spec requirement tags** | [SCH-13], [MODEL-8] |
| **Justification** | Web compatibility: a WHATWG absolute URL record always has a scheme. The `Uri` profile preserves RFC relative-ref semantics (per [SCH-12]). |

### [DEV-12] `%2e`/`%2E` recognised as dot-segments during reference resolution

| Field | Value |
|---|---|
| **RFC 3986 clause** | §5.2.4 (via §6.2.2.3) |
| **What RFC 3986 requires** | `remove_dot_segments` recognizes only the literal complete path segments `.` and `..` and performs no percent-decoding; a `%2e`/`%2E` segment is an ordinary data segment that is preserved (decoding unreserved octets is a separate, optional normalization). |
| **`Url`-profile behaviour (WHATWG)** | During resolution/dot-removal the triplets `%2e` and `%2E` (in any combination such as `%2E%2E`, `.%2e`, `%2e.`) are recognized as `.` / `..` and collapse the path. |
| **Spec requirement tags** | [PATH-9], [NORM-9] |
| **Justification** | Web compatibility: the WHATWG single-dot/double-dot path-segment definitions include the percent-encoded forms, defeating percent-encoded traversal smuggling. The `Uri` profile recognizes only literal `.`/`..`. |

### [DEV-13] Eager (always-on) canonicalization, empty-special-path → `/`, and parse-time dot-segment removal

| Field | Value |
|---|---|
| **RFC 3986 clause** | §6.1 / §6.2 / §5.2 |
| **What RFC 3986 requires** | Normalization is an OPTIONAL comparison ladder designed to minimize false negatives while strictly avoiding false positives; it is not required before comparison. A plain parse of an absolute URI does not itself remove dot-segments, lowercase the host, or synthesize a path; an empty path is preserved. |
| **`Url`-profile behaviour (WHATWG)** | Every §11.1 normalization is applied eagerly at parse/build time so a `Url` has exactly one canonical form ("no unnormalized Url"): host always lowercased, dot-segments removed during parsing (`shorten_path`, e.g. `http://a/b/../c` → `http://a/c`), and an empty special-scheme path synthesized as `/`. This eager canonicalization does **not** include re-casing of percent-triplets already present in the input: the WHATWG parser preserves an existing `%XY` triplet's hex case verbatim and only triplets the implementation itself emits are uppercase ([PCT-32]/[NORM-6]). |
| **Spec requirement tags** | [NORM-1], [NORM-10], [NORM-11], [NORM-12], [PATH-7], [PATH-16] |
| **Justification** | Web compatibility: every WHATWG URL has a single defined serialization. The `Uri` profile preserves input and offers normalization only opt-in. |

### [DEV-14] `file` Windows drive-letter handling

| Field | Value |
|---|---|
| **RFC 3986 clause** | §3.3 |
| **What RFC 3986 requires** | RFC 3986 defines no special path handling for any scheme; `|` is `sub-delims` data, `:` is an ordinary `pchar`, and `remove_dot_segments` (§5.2.4) removes the last output segment on `/..` regardless of its content. No segment is protected from removal. |
| **`Url`-profile behaviour (WHATWG)** | For the `file` scheme, a Windows drive letter's second code point is normalized from `|` to `:` (`C|` → `C:`), and `remove_dot_segments`/`shorten_path` is a no-op when asked to remove a sole normalized drive-letter segment (`file:///C:/..` retains `/C:/`). |
| **Spec requirement tags** | [PATH-14], [PATH-15], [PATH-16] |
| **Justification** | Web compatibility: the WHATWG file-state and `shorten_path` rules preserve drive letters to match OS file-URL semantics. The `Uri` profile applies no drive-letter handling. |

### [DEV-15] Opaque (cannot-be-a-base) path rewriting

| Field | Value |
|---|---|
| **RFC 3986 clause** | §3.3 |
| **What RFC 3986 requires** | A scheme-present, authority-absent, non-`/`-leading path is `path-rootless`: still a `/`-separated sequence of segments subject to dot-segment removal during resolution; `pchar` governs its encoding and no trailing-space stripping is defined. |
| **`Url`-profile behaviour (WHATWG)** | Such URLs are cannot-be-a-base with an opaque single-string path: no segment structure, no segment mutators, no dot-segment removal, no backslash rewriting; encoded with the C0-control percent-encode set; a trailing U+0020 is stripped when neither query nor fragment is present. |
| **Spec requirement tags** | [PATH-17], [PATH-18], [PATH-19] |
| **Justification** | Web compatibility: WHATWG models `mailto:`/`tel:`/`urn:`/`data:`-style URLs as cannot-be-a-base with opaque paths. The `Uri` profile treats them as `path-rootless`. |

### [DEV-16] WHATWG per-component percent-encode sets

| Field | Value |
|---|---|
| **RFC 3986 clause** | §2.2 / §2.3 / §2.4 / §3.2.1 / §3.3 / §3.4 / §3.5 |
| **What RFC 3986 requires** | Per-component grammars (`pchar = unreserved / pct-encoded / sub-delims / ":" / "@"`; `query` / `fragment = pchar / "/" / "?"`; `userinfo = unreserved / pct-encoded / sub-delims / ":"`). Producers should percent-encode only data that conflicts with a delimiter, and §2.3 says percent-encoded unreserved octets should not be created. |
| **`Url`-profile behaviour (WHATWG)** | The fixed WHATWG encode sets (fragment/query/special-query/path/userinfo) encode a different code-point set than the RFC component grammars — e.g. the path set leaves `[` `]` `|` `\` unencoded while encoding `^` `{` `}` `` ` ``; the form-component set even encodes the unreserved `~`. |
| **Spec requirement tags** | [PCT-6], [PCT-7], [PCT-8], [PCT-9], [PCT-10], [PCT-12], [NORM-12] |
| **Justification** | Web compatibility: matches the WHATWG percent-encode sets (and deployed ada/okhttp/ktor encoders) rather than the RFC component grammars. The `Uri` profile encodes per the RFC component grammars. |

### [DEV-17] `cannot-have-a-username/password/port` constraint

| Field | Value |
|---|---|
| **RFC 3986 clause** | §3.2 / §3.2.1 / §3.2.3 |
| **What RFC 3986 requires** | The `authority` grammar is scheme-agnostic: `authority = [ userinfo "@" ] host [ ":" port ]`. RFC 3986 places no scheme-keyed restriction on the presence of userinfo or port; a userinfo subcomponent and a port are permitted with any host (including an empty host) under any scheme. |
| **`Url`-profile behaviour (WHATWG)** | A value *cannot have a username, password, or port* when its host is null or the empty string, or its scheme is `file`. Such components MUST NOT be set or serialized for these values, making `file://user@host/` and `file://host:80/` non-representable as a `Url`. |
| **Spec requirement tags** | [MODEL-47], [NORM-30] |
| **Justification** | Web compatibility: matches the WHATWG URL setters and the file/file-host parser states, which parse no credentials or port. The `Uri` profile follows RFC 3986 and imposes no such restriction. |

### [DEV-18] Scheme-keyed host-kind combination constraint

| Field | Value |
|---|---|
| **RFC 3986 clause** | §3.2.2 |
| **What RFC 3986 requires** | The `host` production (`IP-literal / IPv4address / reg-name`) is scheme-agnostic: RFC 3986 permits a registered name, an IPv4 address, or an IP-literal under any scheme, with no scheme-keyed restriction on which host form may appear. |
| **`Url`-profile behaviour (WHATWG)** | The reachable host kind is constrained by scheme class: a special scheme other than `file` MUST have a domain/IPv4/IPv6 host; `file` MUST have a domain/IPv4/IPv6/empty host; a non-special scheme MUST have an opaque/IPv6/empty/null host and never a domain or IPv4 host (e.g. `1.2.3.4` under a non-special scheme is stored as an opaque host, not IPv4). |
| **Spec requirement tags** | [MODEL-49], [SCH-22] |
| **Justification** | Web compatibility: matches the WHATWG scheme/host combination table and the host-parser dispatch (opaque-host parsing for non-special schemes, IDNA/IPv4 only for special schemes). The `Uri` profile imposes no scheme-keyed host-kind restriction. |
