/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.bind;

/**
 * A getter-only Java bean (no fields, no constructor parameters) whose members surface only through
 * {@code memberFunctions}. Its several {@code @Path} getters feed the same multi-valued path slot, so
 * it pins the scanner's determinism guarantee: getter-derived members bind in a stable, name-sorted
 * order (alpha, mango, zebra) rather than in unspecified reflection order.
 */
@Url
public final class OrderedBean {
    @Scheme
    public String getScheme() {
        return "https";
    }

    @Host
    public String getHost() {
        return "h";
    }

    @Path
    public String getZebra() {
        return "z";
    }

    @Path
    public String getAlpha() {
        return "a";
    }

    @Path
    public String getMango() {
        return "m";
    }
}
