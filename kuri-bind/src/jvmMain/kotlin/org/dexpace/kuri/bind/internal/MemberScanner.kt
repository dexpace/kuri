/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.bind.internal

import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.full.valueParameters
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.javaMethod

/**
 * A discovered member ready for binding: its logical name, all resolved binding annotations,
 * the erased declared type, and a reflective value reader.
 *
 * Annotations are collected from every applicable use-site so that `@Host`, `@get:Host`,
 * `@field:Host`, and a constructor-parameter `@Host val` all surface through a single list.
 */
internal class ScannedMember(
    val name: String,
    val annotations: List<Annotation>,
    val declaredType: KClass<*>,
    val read: (Any) -> Any?,
)

/** Discovers a type's members in a deterministic order, collecting binding annotations. */
internal interface MemberScanner {
    fun scan(type: KClass<*>): List<ScannedMember>
}

/**
 * kotlin-reflect member discovery covering both Kotlin and Java bean types.
 *
 * **Ordering:** primary-constructor parameter order when a `primaryConstructor` exists; otherwise
 * `memberProperties` iteration order. Getter-derived members that have no corresponding property
 * are appended after the property-derived members.
 *
 * **Annotation sites:** for each property — the property itself, its backing `javaField`, its
 * getter, and the matching primary-constructor parameter — deduped by `annotationClass`. This
 * means `@Host`, `@get:Host`, `@field:Host`, and a constructor-param `@Host val` all surface.
 *
 * **Java bean fallback:** annotated `get*`/`is*` zero-argument functions from `memberFunctions`
 * are also turned into `ScannedMember` entries. Each is merged with the property-derived list,
 * deduped by logical name (property-derived preferred when both carry annotations; getter-derived
 * preferred when only it carries annotations). This is what makes plain Java POJOs work.
 *
 * Inherited members are automatically included because `memberProperties` and `memberFunctions`
 * already traverse the full class hierarchy.
 */
internal class KotlinReflectMemberScanner : MemberScanner {
    override fun scan(type: KClass<*>): List<ScannedMember> {
        val propertyMembers = buildPropertyMembers(type)
        val getterMembers = buildGetterMembers(type)
        return merge(propertyMembers, getterMembers)
    }

    private fun buildPropertyMembers(type: KClass<*>): LinkedHashMap<String, ScannedMember> {
        val properties = type.memberProperties.associateBy { it.name }
        val ordered = orderProperties(type, properties)
        val result = LinkedHashMap<String, ScannedMember>(ordered.size)
        for (prop in ordered) {
            @Suppress("UNCHECKED_CAST")
            val p = prop as KProperty1<Any, *>
            p.isAccessible = true
            result[p.name] =
                ScannedMember(
                    name = p.name,
                    annotations = annotationSites(type, p),
                    declaredType = (p.returnType.classifier as? KClass<*>) ?: Any::class,
                    read = { instance -> p.get(instance) },
                )
        }
        return result
    }

    /**
     * Scans for annotated `get*`/`is*` zero-argument member functions.
     * Only includes functions that carry at least one annotation — bare Object/Any methods
     * (getClass, hashCode…) are skipped by the empty-annotation guard.
     */
    private fun buildGetterMembers(type: KClass<*>): List<ScannedMember> =
        type.memberFunctions.mapNotNull { func ->
            val logicalName = deriveGetterName(func) ?: return@mapNotNull null
            val annotations = func.annotations.distinctBy { it.annotationClass }
            if (annotations.isEmpty()) return@mapNotNull null
            func.isAccessible = true
            ScannedMember(
                name = logicalName,
                annotations = annotations,
                declaredType = (func.returnType.classifier as? KClass<*>) ?: Any::class,
                read = { instance -> func.call(instance) },
            )
        }

    /**
     * Derives the logical property name from a getter function name, or returns null if the
     * function does not follow the `getX`/`isX` zero-parameter convention.
     */
    private fun deriveGetterName(func: KFunction<*>): String? {
        if (func.valueParameters.isNotEmpty()) return null
        val name = func.name
        return when {
            name.startsWith("get") && name.length > GET_PREFIX_LEN ->
                name.drop(GET_PREFIX_LEN).replaceFirstChar { it.lowercaseChar() }
            name.startsWith("is") && name.length > IS_PREFIX_LEN ->
                name.drop(IS_PREFIX_LEN).replaceFirstChar { it.lowercaseChar() }
            else -> null
        }
    }

    /**
     * Merges property-derived and getter-derived members, deduplicating by logical name.
     * The property-derived member is kept by default; the getter-derived member wins only
     * when it carries annotations and the property-derived one does not.
     */
    private fun merge(
        propertyMembers: LinkedHashMap<String, ScannedMember>,
        getterMembers: List<ScannedMember>,
    ): List<ScannedMember> {
        val merged = LinkedHashMap<String, ScannedMember>(propertyMembers)
        for (getter in getterMembers) {
            val existing = merged[getter.name]
            when {
                existing == null -> merged[getter.name] = getter
                existing.annotations.isEmpty() && getter.annotations.isNotEmpty() ->
                    merged[getter.name] = getter
                // else: keep property-derived (already in map)
            }
        }
        return merged.values.toList()
    }

    /**
     * Orders properties by primary-constructor parameter position, appending any properties that
     * do not appear in the constructor (e.g., body-declared properties) in iteration order.
     */
    private fun orderProperties(
        type: KClass<*>,
        properties: Map<String, KProperty1<out Any, *>>,
    ): List<KProperty1<out Any, *>> {
        val ctor = type.primaryConstructor ?: return properties.values.toList()
        val ctorParamNames = ctor.parameters.mapNotNull { it.name }
        val byCtor = ctorParamNames.mapNotNull { properties[it] }
        val ctorNameSet = ctorParamNames.toSet()
        val rest = properties.values.filter { it.name !in ctorNameSet }

        return byCtor + rest
    }

    /**
     * Collects annotations from all applicable use-sites for a property, deduped by annotation
     * class. Sites: the property itself, its backing Java field, its getter, the matching
     * primary-constructor parameter of the scanned type, and the matching primary-constructor
     * parameter of the declaring class (handles inherited properties annotated in a superclass
     * constructor, e.g. `@Scheme val x` in `Base` surfacing when scanning `Derived : Base`).
     */
    private fun annotationSites(
        type: KClass<*>,
        prop: KProperty1<Any, *>,
    ): List<Annotation> {
        val sites = ArrayList<Annotation>()
        sites.addAll(prop.annotations)
        prop.javaField?.annotations?.forEach { sites.add(it) }
        sites.addAll(prop.getter.annotations)
        // Check constructor-parameter annotations from the scanned type and, separately, from the
        // declaring class — the two differ for inherited properties (e.g. `@Scheme val x` in Base
        // puts the annotation on Base's constructor parameter, not Derived's).
        val declaringClass: KClass<*>? =
            prop.javaField?.declaringClass?.kotlin
                ?: prop.getter.javaMethod
                    ?.declaringClass
                    ?.kotlin
        addCtorParamAnnotations(type, prop.name, sites)
        if (declaringClass != null && declaringClass != type) {
            addCtorParamAnnotations(declaringClass, prop.name, sites)
        }
        return sites.distinctBy { it.annotationClass }
    }

    /** Appends primary-constructor parameter annotations for [propName] from [klass] into [sink]. */
    private fun addCtorParamAnnotations(
        klass: KClass<*>,
        propName: String,
        sink: MutableList<Annotation>,
    ) {
        klass.primaryConstructor
            ?.parameters
            ?.firstOrNull { it.name == propName }
            ?.annotations
            ?.let { sink.addAll(it) }
    }

    private companion object {
        // Lengths of the standard getter prefixes, extracted to avoid magic-number lint warnings.
        const val GET_PREFIX_LEN = 3
        const val IS_PREFIX_LEN = 2
    }
}
