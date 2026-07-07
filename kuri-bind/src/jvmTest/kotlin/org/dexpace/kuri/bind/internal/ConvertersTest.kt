/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.bind.internal

import org.dexpace.kuri.bind.KuriBindException
import org.dexpace.kuri.host.Host
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

private enum class Color { RED }

class ConvertersTest {
    @Test
    fun `scalarText renders enums by name`() {
        assertEquals("RED", scalarText(Color.RED))
        assertEquals("7", scalarText(7))
    }

    @Test
    fun `convertPort parses ints and numeric strings and rejects garbage`() {
        assertEquals(8080, convertPort(8080, "p"))
        assertEquals(80, convertPort("80", "p"))
        assertFailsWith<KuriBindException> { convertPort("http", "p") }
    }

    @Test
    fun `convertPort accepts short, byte, and long integral values`() {
        assertEquals(80, convertPort(80.toShort(), "p"))
        assertEquals(80, convertPort(80.toByte(), "p"))
        assertEquals(80, convertPort(80L, "p"))
    }

    @Test
    fun `convertPort rejects a long outside the Int range`() {
        val tooBig = Int.MAX_VALUE.toLong() + 1
        val tooSmall = Int.MIN_VALUE.toLong() - 1
        assertFailsWith<KuriBindException> { convertPort(tooBig, "p") }
        assertFailsWith<KuriBindException> { convertPort(tooSmall, "p") }
    }

    @Test
    fun `convertPort rejects an unsupported type`() {
        assertFailsWith<KuriBindException> { convertPort(3.14, "p") }
        assertFailsWith<KuriBindException> { convertPort(Any(), "p") }
    }

    @Test
    fun `convertPort rejects a port outside the 0 to 65535 range`() {
        assertEquals(0, convertPort(0, "p"))
        assertEquals(65535, convertPort(65535, "p"))
        val tooBig = assertFailsWith<KuriBindException> { convertPort(70000, "p") }
        assertEquals("p", tooBig.path)
        assertFailsWith<KuriBindException> { convertPort(-1, "p") }
    }

    @Test
    fun `splitUserInfo splits on the first colon`() {
        assertEquals(UserInfoValue("u", "p:w"), splitUserInfo("u:p:w"))
        assertEquals(UserInfoValue("u", null), splitUserInfo("u"))
    }

    @Test
    fun `hostValueOf renders a Host via asText and other values as text`() {
        assertEquals("example.com", hostValueOf(Host.RegName("example.com")))
        assertEquals("example.com", hostValueOf("example.com"))
        assertEquals("7", hostValueOf(7))
    }

    @Test
    fun `iterable and map probes`() {
        assertEquals(listOf(1, 2), asIterableOrNull(listOf(1, 2))?.toList())
        assertEquals(listOf(1, 2), asIterableOrNull(arrayOf(1, 2))?.toList())
        assertNull(asIterableOrNull("scalar"))
        assertEquals(mapOf("a" to 1), asMapOrNull(mapOf("a" to 1)))
    }

    @Test
    fun `asIterableOrNull views primitive arrays`() {
        assertEquals(listOf(1, 2), asIterableOrNull(intArrayOf(1, 2))?.toList())
        assertEquals(listOf(1L, 2L), asIterableOrNull(longArrayOf(1L, 2L))?.toList())
        assertEquals(listOf<Byte>(1, 2), asIterableOrNull(byteArrayOf(1, 2))?.toList())
        assertEquals(listOf<Short>(1, 2), asIterableOrNull(shortArrayOf(1, 2))?.toList())
        assertEquals(listOf('a', 'b'), asIterableOrNull(charArrayOf('a', 'b'))?.toList())
        assertEquals(listOf(true, false), asIterableOrNull(booleanArrayOf(true, false))?.toList())
        assertEquals(listOf(1.5f, 2.5f), asIterableOrNull(floatArrayOf(1.5f, 2.5f))?.toList())
        assertEquals(listOf(1.5, 2.5), asIterableOrNull(doubleArrayOf(1.5, 2.5))?.toList())
    }

    @Test
    fun `asMapOrNull returns null for non-maps`() {
        assertNull(asMapOrNull("scalar"))
        assertNull(asMapOrNull(listOf(1, 2)))
    }
}
