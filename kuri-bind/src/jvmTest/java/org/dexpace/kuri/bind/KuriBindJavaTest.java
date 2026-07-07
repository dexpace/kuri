/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.bind;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Verifies the {@link KuriBind} facade from a Java call site: the {@code @JvmStatic} entry points are
 * plain static methods, and a plain Java bean whose {@code @Scheme}/{@code @Host} annotations sit on
 * its getters binds through {@code KotlinReflectMemberScanner}'s Java-bean discovery.
 *
 * <p>The {@code org.dexpace.kuri.Url} class is fully qualified because its simple name collides with
 * the same-package {@code @Url} marker annotation used on {@code Ping}.
 */
public final class KuriBindJavaTest {
    @Url
    public static final class Ping {
        @Scheme
        public String getScheme() {
            return "https";
        }

        @Host
        public String getHost() {
            return "example.com";
        }
    }

    @Test
    public void bindsAJavaBeanThroughTheStaticFacade() {
        org.dexpace.kuri.Url url = KuriBind.toUrl(new Ping());
        assertEquals("https", url.scheme());
        assertEquals("https://example.com/", url.toString());
    }
}
