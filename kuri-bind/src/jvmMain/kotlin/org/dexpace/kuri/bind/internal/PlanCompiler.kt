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
import org.dexpace.kuri.bind.Profile
import org.dexpace.kuri.bind.Query
import org.dexpace.kuri.bind.QueryMap
import org.dexpace.kuri.bind.Scheme
import org.dexpace.kuri.bind.UserInfo
import org.dexpace.kuri.bind.Username
import kotlin.reflect.KClass
import org.dexpace.kuri.bind.Uri as UriMarker
import org.dexpace.kuri.bind.Url as UrlMarker

/** Compiles a type into a validated [TypePlan]. Nesting/templates are layered in later tasks. */
internal class PlanCompiler(
    private val scanner: MemberScanner,
) {
    // profile becomes load-bearing once scoped recursion and path templates are layered on;
    // it stays in the signature now so callers and later stages compile against a stable shape.
    @Suppress("UnusedParameter")
    fun compile(
        type: KClass<*>,
        profile: Profile,
    ): TypePlan {
        require(type != Any::class) { "cannot bind Any" }
        val steps = scanner.scan(type).mapNotNull { member -> stepFor(member) }
        return TypePlan(type, steps, template = null)
    }

    private fun stepFor(member: ScannedMember): BindStep? {
        val marker = bindingAnnotation(member) ?: return null
        return when (marker) {
            is Scheme -> BindStep.Leaf(member, LeafOp.SCHEME, null)
            is UserInfo -> BindStep.Leaf(member, LeafOp.USERINFO, null)
            is Username -> BindStep.Leaf(member, LeafOp.USERNAME, null)
            is Password -> BindStep.Leaf(member, LeafOp.PASSWORD, null)
            is Host -> BindStep.Leaf(member, LeafOp.HOST, null)
            is Port -> BindStep.Leaf(member, LeafOp.PORT, null)
            is Fragment -> BindStep.Leaf(member, LeafOp.FRAGMENT, null)
            is Path -> BindStep.Leaf(member, LeafOp.PATH, null) // named/template handled in Task 11
            is Query -> BindStep.Leaf(member, LeafOp.QUERY, marker.value.ifEmpty { member.name })
            is QueryMap -> BindStep.Leaf(member, LeafOp.QUERY_MAP, null)
            is UrlMarker, is UriMarker -> BindStep.Recurse(member, Scope.MERGE, null) // realized in Task 10
            else -> null
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
}
