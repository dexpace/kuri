/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.percent

import kotlin.jvm.JvmStatic

/**
 * Percent-encoding and percent-decoding of a single URI/URL component (RFC 3986 §2.1, SPEC §5).
 *
 * The public face of the internal [PercentCodec] engine: [encode] renders arbitrary text as
 * uppercase `%XX` triplets under the encode set chosen by a [Component], and [decode] turns triplets
 * back into text. Both are UTF-8 and *total* — neither ever throws, on any input — so they are safe
 * to apply to untrusted strings without a surrounding `try`/`catch`. [encode] returns its argument
 * unchanged when nothing needs escaping; [decode] leaves a malformed `%` sequence (one not followed
 * by two hex digits) verbatim rather than failing.
 *
 * Each function operates on one component in isolation and does **not** parse or recompose a whole
 * reference. To take apart or build a full URI, use [org.dexpace.kuri.Uri] (RFC 3986 generic URIs)
 * or [org.dexpace.kuri.Url] (WHATWG web URLs), which apply the correct set to each component for you.
 *
 * @see org.dexpace.kuri.Uri
 * @see org.dexpace.kuri.Url
 */
public object Percent {
    /**
     * The component context that selects which characters [encode] escapes (SPEC §5.1.3).
     *
     * Each case names a WHATWG percent-encode set; a stricter set escapes a superset of what a looser
     * one does. Choose the case matching where the encoded text will be placed, so that the reserved
     * delimiters of that component are escaped while its permitted characters pass through. Every case
     * additionally escapes all C0 controls, DEL, and non-ASCII code points (the universal rule of
     * [PCT-1]); the descriptions below list only the printable-ASCII members each case adds.
     */
    public enum class Component {
        /**
         * A path segment — the WHATWG *path* set. Adds space, `"`, `#`, `<`, `>`, `` ` ``, `?`, `{`,
         * `}`, and `^`. It does **not** escape `/`, so a value that must not introduce a new segment
         * (a slash inside one segment) should be encoded with [COMPONENT] instead.
         */
        PATH_SEGMENT,

        /**
         * A query string — the WHATWG *query* set. Adds space, `"`, `#`, `<`, and `>`. It leaves `&`
         * and `=` literal (they delimit form fields), so encode a single field value with [COMPONENT].
         */
        QUERY,

        /**
         * A fragment — the WHATWG *fragment* set. Adds space, `"`, `<`, `>`, and `` ` ``. Unlike
         * [QUERY] it leaves `#` literal, since a fragment is already the final component.
         */
        FRAGMENT,

        /**
         * A userinfo (`user:password`) component — the WHATWG *userinfo* set. Adds `/`, `:`, `;`, `=`,
         * `@`, `[`, `\`, `]`, and `|` on top of the path set's members.
         */
        USER_INFO,

        /**
         * The strictest, general-purpose set, equivalent to JavaScript's `encodeURIComponent`. Adds
         * `$`, `%`, `&`, `+`, and `,` on top of [USER_INFO], escaping every reserved delimiter so the
         * result is safe to place in any single component. Prefer this when in doubt.
         */
        COMPONENT,
    }

    /**
     * Percent-encodes [text] for the given [component], emitting uppercase `%XX` triplets (SPEC §5.2).
     *
     * Every character the component's encode set reserves — plus all C0 controls, DEL, and non-ASCII
     * code points — is UTF-8 encoded and rendered as one or more triplets; everything else passes
     * through unchanged, and when nothing needs escaping the original [text] is returned as-is. A lone
     * (unpaired) surrogate encodes to the octets of U+FFFD. Total: it never throws, for any input.
     *
     * @param text the raw text to encode, considered in isolation from any surrounding URI structure.
     * @param component the target context, which selects the encode set (see [Component]).
     * @return [text] with the reserved characters of [component] escaped as uppercase `%XX` triplets.
     */
    @JvmStatic
    public fun encode(
        text: String,
        component: Component,
    ): String {
        val encoded = PercentCodec.encode(text, encodeSetFor(component))
        // Encoding only ever replaces a code point with a longer triplet run or passes it through, so
        // it can never shorten the input; a shorter result would mean an engine bug, not bad input.
        check(encoded.length >= text.length) { "percent-encoding must never shorten the input" }
        return encoded
    }

    /**
     * Percent-decodes every `%XX` triplet in [text] back to text, leniently (SPEC §5.3).
     *
     * Consecutive triplets are gathered and decoded as one UTF-8 sequence, with invalid UTF-8 mapped
     * to U+FFFD; any `%` not followed by two hex digits is left verbatim, so no input is ever rejected.
     * A literal `+` is preserved as-is — this is generic component decoding, not form decoding, so for
     * an `application/x-www-form-urlencoded` body use [org.dexpace.kuri.query.QueryParameters]
     * instead. Total: it never throws, for any input.
     *
     * @param text text that may contain percent-encoded triplets.
     * @return the decoded text; a malformed escape is preserved literally.
     */
    @JvmStatic
    public fun decode(text: String): String {
        val decoded = PercentCodec.decode(text, plusAsSpace = false)
        // Decoding collapses each triplet run to its octets and passes literals through, so the result
        // can never grow; a longer result would mean an engine bug, not bad input.
        check(decoded.length <= text.length) { "percent-decoding must never lengthen the input" }
        return decoded
    }

    /** Maps a public [Component] to the internal [PercentEncodeSet] realizing it (SPEC §5.1.3). */
    private fun encodeSetFor(component: Component): PercentEncodeSet =
        when (component) {
            Component.PATH_SEGMENT -> PercentEncodeSets.PATH
            Component.QUERY -> PercentEncodeSets.QUERY
            Component.FRAGMENT -> PercentEncodeSets.FRAGMENT
            Component.USER_INFO -> PercentEncodeSets.USERINFO
            Component.COMPONENT -> PercentEncodeSets.COMPONENT
        }
}
