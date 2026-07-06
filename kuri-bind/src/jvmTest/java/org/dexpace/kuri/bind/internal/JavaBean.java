/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.bind.internal;

import org.dexpace.kuri.bind.Host;
import org.dexpace.kuri.bind.Scheme;

/**
 * A plain Java bean used to verify that {@code KotlinReflectMemberScanner} discovers annotated
 * getter functions ({@code get*}/{@code is*}) for types that do not expose properties via
 * Kotlin's {@code memberProperties}. The annotations live on the getters, not on the fields.
 */
public final class JavaBean {
    private final String scheme;
    private final String host;

    public JavaBean(String scheme, String host) {
        this.scheme = scheme;
        this.host = host;
    }

    @Scheme
    public String getScheme() {
        return scheme;
    }

    @Host
    public String getHost() {
        return host;
    }
}
