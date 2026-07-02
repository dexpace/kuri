/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.kuri

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

/**
 * On-device (ART) smoke test.
 *
 * The full `commonTest` suite runs as Android host (JVM) unit tests and on ten other platforms;
 * it is not run here because its backticked names contain spaces, which are invalid DEX method
 * names below API 35. This dedicated instrumented test — with plain method names — confirms the
 * parsing engine loads and executes correctly on the Android runtime.
 */
@RunWith(AndroidJUnit4::class)
class AndroidInstrumentedSmokeTest {
    @Test
    fun acceptsAValidUriOnDevice() {
        assertTrue(Uri.canParse("https://example.com/path?q=1#frag"))
    }

    @Test
    fun acceptsAValidUrlOnDevice() {
        assertTrue(Url.canParse("https://example.com:8443/a/b?c=d"))
    }
}
