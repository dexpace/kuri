/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.bind.internal

import org.dexpace.kuri.bind.Host
import org.dexpace.kuri.bind.Path
import org.dexpace.kuri.bind.Query
import org.dexpace.kuri.bind.RecordItem
import org.dexpace.kuri.bind.Scheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

// A member whose declared type is a bare type parameter, not a concrete class: the scanner has no
// KClass to record, so it falls back to Any as the erased declared type.
private class GenericHolder<T>(
    @Query("x") val x: T,
)

// A `List<T>` member: the single type argument is a type parameter, not a concrete class, so the
// element-type cast yields null (unlike a `List<String>`, whose element resolves to a KClass).
private class GenericListHolder<T>(
    @Query("xs") val xs: List<T>,
)

// A getter-derived member whose return type is a bare type parameter: the getter path likewise has no
// KClass to record, so its erased declared type falls back to Any.
private class GetterTypeParam<T : Any>(
    private val held: T,
) {
    @Query("thing")
    fun getThing(): T = held
}

// A zero-argument function named exactly `get`: it clears the `get`-prefix test but fails the length
// guard, so it derives no logical name and contributes no member.
private class ExactlyGet {
    @Query("g")
    @Suppress("FunctionOnlyReturningConstant")
    fun get(): String = "x"
}

// An unannotated property shadowed by an annotated `is`-getter deriving the same logical name: the
// getter-derived member (carrying @Query) must override the empty property-derived one.
private class DualValue {
    val value: String = "prop"

    @Query("v")
    @Suppress("FunctionOnlyReturningConstant")
    fun isValue(): String = "getter"
}

// A computed property (custom getter, no backing field): its annotation lives only on the getter site,
// and with no backing `javaField` the declaring-class lookup falls through to the getter's method.
private class ComputedProp {
    @get:Query("c")
    val computed: String get() = "v"
}

// A body-declared property alongside a constructor property: the constructor member keeps its declared
// position and the body property is appended after it (name-sorted), with no constructor-parameter site.
private class BodyDeclared(
    @Host val host: String,
) {
    @Query("b")
    val extra: String = "e"
}

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

    @Test
    fun `derives a member name from a java is-prefixed boolean getter`() {
        val byName = scanner.scan(BoolBean::class).associateBy { it.name }
        // `isActive()` surfaces as `active` (the `is`-prefix branch), carrying its @Query annotation.
        assertEquals(true, byName.containsKey("active"))
        assertEquals(true, byName.getValue("active").annotations.any { it is Query })
    }

    @Test
    fun `falls back to Any for a type-parameter-typed member`() {
        val member = scanner.scan(GenericHolder::class).single { it.name == "x" }
        assertEquals(Any::class, member.declaredType)
    }

    @Test
    fun `records a null element type for a type-parameter list member`() {
        // `List<T>` has a single type argument whose classifier is a type parameter, not a KClass, so
        // the element-type cast returns null (the missed arm a `List<String>` never exercises).
        val member = scanner.scan(GenericListHolder::class).single { it.name == "xs" }
        assertEquals(null, member.elementType)
    }

    @Test
    fun `falls back to Any for a getter returning a type parameter`() {
        val member = scanner.scan(GetterTypeParam::class).single { it.name == "thing" }
        assertEquals(Any::class, member.declaredType)
    }

    @Test
    fun `ignores a getter-shaped function named exactly get`() {
        // `get` passes startsWith("get") but fails the length guard, so it derives no name and the type
        // exposes no members at all.
        assertTrue(scanner.scan(ExactlyGet::class).isEmpty())
    }

    @Test
    fun `prefers an annotated getter over an unannotated property of the same name`() {
        val member = scanner.scan(DualValue::class).single { it.name == "value" }
        assertTrue(member.annotations.any { it is Query })
        // Reading resolves through the getter-derived accessor (isValue), not the shadowed property.
        assertEquals("getter", member.read(DualValue()))
    }

    @Test
    fun `collects a getter-site annotation on a computed property`() {
        val member = scanner.scan(ComputedProp::class).single { it.name == "computed" }
        assertTrue(member.annotations.any { it is Query })
        assertEquals("v", member.read(ComputedProp()))
    }

    @Test
    fun `appends a body-declared property after constructor-ordered members`() {
        val members = scanner.scan(BodyDeclared::class)
        assertEquals(listOf("host", "extra"), members.map { it.name })
        assertTrue(members.single { it.name == "extra" }.annotations.any { it is Query })
    }

    @Test
    fun `scans a java record in canonical component order`() {
        // A Java record has no Kotlin primary constructor, so ordering comes from its reflective record
        // components (scheme, host, zeta, alpha) rather than the name-sorted bean fallback.
        val members = scanner.scan(RecordItem::class)
        assertEquals(listOf("scheme", "host", "zeta", "alpha"), members.map { it.name })
        assertTrue(members.single { it.name == "scheme" }.annotations.any { it is Scheme })
    }
}
