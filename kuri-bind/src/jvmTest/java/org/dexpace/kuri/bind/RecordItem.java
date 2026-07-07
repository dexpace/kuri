/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.bind;

/**
 * A Java {@code record} fixture. Its components are declared {@code scheme}, {@code host},
 * {@code zeta}, {@code alpha} — deliberately not alphabetical — so a bind that follows canonical
 * record-component order produces the path {@code /z/a}, whereas the name-sorted fallback (used for
 * shapes with no reliable reflective order) would produce {@code /a/z}. This pins the scanner's
 * record-component ordering; see {@code KotlinReflectMemberScanner}.
 */
@Url
public record RecordItem(
        @Scheme String scheme,
        @Host String host,
        @Path String zeta,
        @Path String alpha) {
}
