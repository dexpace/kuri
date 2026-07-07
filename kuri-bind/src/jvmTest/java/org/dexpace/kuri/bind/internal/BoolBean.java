/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.bind.internal;

import org.dexpace.kuri.bind.Host;
import org.dexpace.kuri.bind.Query;

/**
 * A plain Java bean whose boolean member is exposed through an {@code is}-prefixed getter, used to
 * verify that {@code KotlinReflectMemberScanner} derives the logical name from the {@code is} prefix
 * (so {@code isActive()} surfaces as a member named {@code active}) with its annotation attached.
 */
public final class BoolBean {
    @Host
    public String getHost() {
        return "h";
    }

    @Query("active")
    public boolean isActive() {
        return true;
    }
}
