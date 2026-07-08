/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.parser

/**
 * The WHATWG "state override" entry points used by the `Url` setter algorithms (WHATWG URL §5
 * setters; each maps to the basic URL parser state the setter re-enters). Unlike a full parse,
 * an override run seeds the work area from an existing URL and processes only its one component.
 */
internal enum class StateOverride {
    /** `protocol` setter — scheme-start entry; commits scheme + default-port elision only. */
    PROTOCOL,

    /** `host` setter — host entry; parses host and (on `:`) port. */
    HOST,

    /** `hostname` setter — host entry that stops at `:` (a port is never parsed). */
    HOSTNAME,

    /** `port` setter — port entry over the digit run. */
    PORT,

    /** `pathname` setter — path-start entry after the path list is cleared. */
    PATHNAME,

    /** `search` setter — query entry over the (leading-`?`-stripped) value. */
    QUERY,
}
