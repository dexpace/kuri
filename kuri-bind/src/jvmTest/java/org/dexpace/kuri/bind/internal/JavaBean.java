/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.bind.internal;

import org.dexpace.kuri.bind.Host;
import org.dexpace.kuri.bind.Scheme;

/**
 * A plain Java bean used to verify that {@code KotlinReflectMemberScanner} merges getter-level
 * annotations onto the corresponding {@code memberProperties} entry. Kotlin-reflect exposes a
 * {@code scheme}/{@code host} property for this bean, but with an empty annotation list — the
 * {@code @Scheme}/{@code @Host} annotations live only on the getter functions — so discovery of them
 * exercises the getter-to-property annotation-merge branch rather than reading annotations straight
 * off the property.
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
