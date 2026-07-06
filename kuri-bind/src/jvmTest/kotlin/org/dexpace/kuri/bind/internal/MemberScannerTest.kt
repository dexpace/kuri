/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.bind.internal

import org.dexpace.kuri.bind.Host
import org.dexpace.kuri.bind.Path
import org.dexpace.kuri.bind.Scheme
import kotlin.test.Test
import kotlin.test.assertEquals

// Base declares @Scheme on the constructor parameter; Derived inherits the property.
private open class Base(
    @Scheme val scheme: String,
)

// Derived exercises all three non-property annotation sites in a single class:
//   host → @get: (getter site), a → @field: (field site), scheme → inherited from Base ctor param.
private class Derived(
    scheme: String,
    @get:Host val host: String,
    @field:Path("a") val a: String,
) : Base(scheme)

class MemberScannerTest {
    private val scanner = KotlinReflectMemberScanner()

    @Test
    fun `scans constructor-ordered members incl inherited and all annotation sites`() {
        val members = scanner.scan(Derived::class)
        val byName = members.associateBy { it.name }
        // All three properties must surface with the correct annotation on each site.
        assertEquals(setOf("scheme", "host", "a"), byName.keys)
        assertEquals(true, byName.getValue("scheme").annotations.any { it is Scheme })
        assertEquals(true, byName.getValue("host").annotations.any { it is Host })
        assertEquals(true, byName.getValue("a").annotations.any { it is Path })
    }

    @Test
    fun `constructor parameter order is preserved`() {
        val names = scanner.scan(Derived::class).map { it.name }
        // Primary-constructor order: scheme (forwarded to Base), host, a.
        assertEquals(listOf("scheme", "host", "a"), names)
    }

    @Test
    fun `reads member values from an instance`() {
        val members = scanner.scan(Derived::class).associateBy { it.name }
        val d = Derived("https", "example.com", "seg")
        assertEquals("example.com", members.getValue("host").read(d))
        assertEquals("https", members.getValue("scheme").read(d))
    }

    @Test
    fun `scans java bean annotated getters`() {
        val members = scanner.scan(JavaBean::class)
        val annotationNames = members.flatMap { m -> m.annotations.map { it.annotationClass.simpleName!! } }
        assertEquals(true, annotationNames.contains("Scheme"))
        assertEquals(true, annotationNames.contains("Host"))
    }

    @Test
    fun `reads java bean values from an instance`() {
        val members = scanner.scan(JavaBean::class).associateBy { it.name }
        val bean = JavaBean("https", "example.com")
        assertEquals("https", members.getValue("scheme").read(bean))
        assertEquals("example.com", members.getValue("host").read(bean))
    }
}
