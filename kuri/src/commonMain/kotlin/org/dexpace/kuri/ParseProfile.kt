/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri

/**
 * Selects which parsing posture the shared engine applies (SPEC §1.2, [INTRO-4]).
 *
 * `kuri` runs one parsing engine, one host module, and one percent-encoding module,
 * parameterized by this profile, so that `Uri` and `Url` are produced by the same code
 * paths configured differently rather than by two independent parsers ([INTRO-1]). The
 * two members are exhaustive and mutually exclusive; a single parse never mixes
 * behaviours from both ([INTRO-4]).
 *
 * - [URI] models the RFC 3986/3987 generic-URI syntax: scheme-agnostic, no special
 *   schemes or default ports, no backslash rewriting, and **preserve-by-default** with
 *   normalization only on explicit opt-in.
 * - [URL] models the WHATWG URL Living Standard: special-scheme aware, applies **eager
 *   canonicalization** on every parse and build, runs the full WHATWG host pipeline, and
 *   elides default ports.
 *
 * Modelled as two distinct types at layer 2 (`Uri`/`Url`) rather than a runtime mode flag
 * so the type system communicates which contract a value holds.
 */
internal enum class ParseProfile {
    /** RFC 3986/3987 generic-URI profile: preserve-by-default, scheme-agnostic. */
    URI,

    /** WHATWG URL profile: eager canonicalization, special schemes, full host pipeline. */
    URL,
    ;

    /**
     * True when this profile selects WHATWG semantics ([URL]).
     *
     * Convenience for the many profile-gated branches that ask "is this the WHATWG
     * profile?" rather than comparing against an enum constant at each call site.
     */
    internal val isWhatwg: Boolean
        get() = this == URL
}
