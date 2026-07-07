/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.bind;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Pins Java {@code record} binding through the {@link KuriBind} facade. The test lives in Java because
 * a record's supertype {@code java.lang.Record} (a JDK 16 type) is invisible to the module's Kotlin
 * test compilation, which is pinned to the shipped Java 8 API surface.
 *
 * <p>{@link RecordItem}'s components are declared {@code zeta} then {@code alpha}, so binding in
 * canonical record-component order produces {@code /z/a}; a name-sorted order would produce
 * {@code /a/z}.
 */
public final class RecordBindingJavaTest {
    @Test
    public void recordBindsInCanonicalComponentOrder() {
        org.dexpace.kuri.Url url = KuriBind.toUrl(new RecordItem("https", "h", "z", "a"));
        assertEquals("https://h/z/a", url.toString());
        assertEquals("https", url.scheme());
    }
}
