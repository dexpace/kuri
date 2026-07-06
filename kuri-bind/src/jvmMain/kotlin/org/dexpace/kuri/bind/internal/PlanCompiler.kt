/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.bind.internal

import org.dexpace.kuri.bind.Fragment
import org.dexpace.kuri.bind.Host
import org.dexpace.kuri.bind.KuriBindException
import org.dexpace.kuri.bind.Password
import org.dexpace.kuri.bind.Path
import org.dexpace.kuri.bind.Port
import org.dexpace.kuri.bind.Query
import org.dexpace.kuri.bind.QueryMap
import org.dexpace.kuri.bind.Scheme
import org.dexpace.kuri.bind.UserInfo
import org.dexpace.kuri.bind.Username
import kotlin.reflect.KClass
import org.dexpace.kuri.bind.Uri as UriMarker
import org.dexpace.kuri.bind.Url as UrlMarker
import org.dexpace.kuri.host.Host as KuriHost

/** Compiles a type into a validated [TypePlan]. Path templates are layered in later tasks. */
internal class PlanCompiler(
    private val scanner: MemberScanner,
) {
    // The plan is profile-agnostic: the same steps describe a `Url` and a `Uri` binding, since the
    // profile only narrows which components project later. So `compile` takes just the type.
    fun compile(type: KClass<*>): TypePlan {
        require(type != Any::class) { "cannot bind Any" }
        val steps = scanner.scan(type).mapNotNull { member -> stepFor(member) }
        return TypePlan(type, steps, template = null)
    }

    private fun stepFor(member: ScannedMember): BindStep? {
        val marker = bindingAnnotation(member) ?: return null
        val scalar = isScalarType(member.declaredType)
        return when (marker) {
            is Scheme -> BindStep.Leaf(member, LeafOp.SCHEME, null)
            is UserInfo -> BindStep.Leaf(member, LeafOp.USERINFO, null)
            is Username -> BindStep.Leaf(member, LeafOp.USERNAME, null)
            is Password -> BindStep.Leaf(member, LeafOp.PASSWORD, null)
            is Host -> BindStep.Leaf(member, LeafOp.HOST, null)
            is Port -> BindStep.Leaf(member, LeafOp.PORT, null)
            is Fragment -> BindStep.Leaf(member, LeafOp.FRAGMENT, null)
            is Path -> pathStep(member, scalar)
            is Query -> queryStep(member, marker, scalar)
            is QueryMap -> BindStep.Leaf(member, LeafOp.QUERY_MAP, null)
            is UrlMarker, is UriMarker -> BindStep.Recurse(member, Scope.MERGE, null)
            else -> null
        }
    }

    // A scalar or collection is a path segment (leaf); a complex member recurses under the PATH
    // scope so its own annotated members fill the path. Template naming is applied in Task 11.
    private fun pathStep(
        member: ScannedMember,
        scalar: Boolean,
    ): BindStep =
        if (scalar || isCollectionType(member.declaredType)) {
            BindStep.Leaf(member, LeafOp.PATH, null)
        } else {
            BindStep.Recurse(member, Scope.PATH, null)
        }

    // A scalar or collection is one query parameter (leaf); a complex member recurses under the
    // QUERY scope. A `Map` here is ambiguous with `@QueryMap` semantics, so it is rejected.
    private fun queryStep(
        member: ScannedMember,
        marker: Query,
        scalar: Boolean,
    ): BindStep {
        if (isMapType(member.declaredType)) {
            throw KuriBindException("a Map under @Query is not allowed; use @QueryMap", member.name)
        }
        return if (scalar || isCollectionType(member.declaredType)) {
            BindStep.Leaf(member, LeafOp.QUERY, marker.value.ifEmpty { member.name })
        } else {
            BindStep.Recurse(member, Scope.QUERY, marker.value.ifEmpty { null })
        }
    }

    private fun bindingAnnotation(member: ScannedMember): Annotation? {
        val markers = member.annotations.filter { it.isBindingMarker() }
        if (markers.size > 1) {
            throw KuriBindException(
                "member '${member.name}' has more than one binding annotation: " +
                    markers.joinToString { it.annotationClass.simpleName ?: "?" },
                member.name,
            )
        }
        return markers.singleOrNull()
    }

    private fun Annotation.isBindingMarker(): Boolean =
        when (this) {
            is Scheme, is UserInfo, is Username, is Password, is Host, is Port,
            is Path, is Query, is QueryMap, is Fragment, is UrlMarker, is UriMarker,
            -> true
            else -> false
        }

    // A member is a leaf when its declared type is a scalar: the primitives and their boxed forms,
    // `String`/`Char`, a `Host` value, or any enum. Anything else is a complex member and recurses.
    private fun isScalarType(type: KClass<*>): Boolean =
        when (type) {
            String::class, Char::class, Boolean::class,
            Byte::class, Short::class, Int::class, Long::class, Float::class, Double::class,
            KuriHost::class,
            -> true
            else -> type.java.isEnum
        }

    private fun isCollectionType(type: KClass<*>): Boolean =
        Iterable::class.java.isAssignableFrom(type.java) || type.java.isArray

    private fun isMapType(type: KClass<*>): Boolean = Map::class.java.isAssignableFrom(type.java)
}
