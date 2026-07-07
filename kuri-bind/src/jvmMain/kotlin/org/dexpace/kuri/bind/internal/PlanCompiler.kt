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
import kotlin.reflect.full.findAnnotation
import org.dexpace.kuri.bind.PathTemplate as PathTemplateAnn
import org.dexpace.kuri.bind.Uri as UriMarker
import org.dexpace.kuri.bind.Url as UrlMarker
import org.dexpace.kuri.host.Host as KuriHost

/** Compiles a type into a validated [TypePlan], resolving any root `@PathTemplate` against its holes. */
internal class PlanCompiler(
    private val scanner: MemberScanner,
) {
    // The plan is profile-agnostic: the same steps describe a `Url` and a `Uri` binding, since the
    // profile only narrows which components project later. So `compile` takes just the type.
    //
    // A root `@PathTemplate` switches path binding into template mode: each `@Path` names the hole it
    // fills (see `stepFor`), and `resolveHoleProviders` enforces a bijection between the template's
    // holes and the named `@Path` providers reachable through the merge scope, recording each hole's
    // root-relative accessor so a value supplied across a merge resolves at bind time.
    fun compile(type: KClass<*>): TypePlan {
        require(type != Any::class) { "cannot bind Any" }
        val template = type.findAnnotation<PathTemplateAnn>()?.let { PathTemplate.parse(it.value) }
        val steps = scanner.scan(type).mapNotNull { member -> stepFor(member, inTemplate = template != null) }
        val holeProviders = if (template != null) resolveHoleProviders(type, template, steps) else emptyMap()
        return TypePlan(type, steps, template, holeProviders)
    }

    private fun stepFor(
        member: ScannedMember,
        inTemplate: Boolean,
    ): BindStep? {
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
            is Path -> pathStep(member, marker, scalar, inTemplate)
            is Query -> queryStep(member, marker, scalar)
            is QueryMap -> queryMapStep(member)
            is UrlMarker, is UriMarker -> mergeStep(member)
            else -> null
        }
    }

    // Resolves each template hole to a composed accessor after validating the hole↔provider bijection.
    // The accessor reads from the ROOT down through the merge chain to the provider member, so a hole
    // supplied by an `@Url`/`@Uri`-merged child resolves at bind time without that child's own plan.
    private fun resolveHoleProviders(
        type: KClass<*>,
        template: PathTemplate,
        steps: List<BindStep>,
    ): Map<String, HoleProvider> {
        val entries = collectHoleProviders(type, steps, { it }, HashSet())
        val holeNames = template.holes.map { it.name }
        validateBijection(type, holeNames, entries.map { it.name })
        val catchAll = template.holes.associate { it.name to it.catchAll }
        return entries.associate { entry -> entry.name to HoleProvider(entry.accessor, catchAll.getValue(entry.name)) }
    }

    // Walks the merge scope with a cycle guard, composing a root-relative accessor for every hole:
    // providers declared directly on `type`, plus those from each `@Url`/`@Uri` MERGE member (its
    // declared type scanned in template mode so its named `@Path` members surface as holes). `prefix`
    // reads from the root to the current instance; `seen` bounds cyclic graphs.
    private fun collectHoleProviders(
        type: KClass<*>,
        steps: List<BindStep>,
        prefix: (Any) -> Any?,
        seen: MutableSet<KClass<*>>,
    ): List<HoleProviderEntry> {
        if (!seen.add(type)) return emptyList()
        val here =
            steps.filterIsInstance<BindStep.Hole>().map { hole ->
                HoleProviderEntry(hole.name) { root -> prefix(root)?.let { hole.member.read(it) } }
            }
        val merged =
            steps
                .filterIsInstance<BindStep.Recurse>()
                .filter { it.scope == Scope.MERGE }
                .flatMap { recurse ->
                    val nested = recurse.member.declaredType
                    val nestedSteps = scanner.scan(nested).mapNotNull { m -> stepFor(m, inTemplate = true) }
                    val nestedPrefix = { root: Any -> prefix(root)?.let { recurse.member.read(it) } }
                    collectHoleProviders(nested, nestedSteps, nestedPrefix, seen)
                }
        return here + merged
    }

    // In template mode a `@Path` must name the hole it fills and becomes a `Hole` provider; the
    // unnamed positional form is meaningless without a skeleton to slot into and is rejected. Without
    // a template, binding is positional: a leaf-like value is a path segment; a complex member with
    // its own annotated members recurses under the PATH scope so those members fill the path.
    private fun pathStep(
        member: ScannedMember,
        marker: Path,
        scalar: Boolean,
        inTemplate: Boolean,
    ): BindStep {
        if (inTemplate) {
            if (marker.value.isEmpty()) {
                throw KuriBindException("@Path in a @PathTemplate class must name a hole", member.name)
            }
            return BindStep.Hole(member, marker.value)
        }
        return if (isLeafLike(member.declaredType, scalar)) {
            BindStep.Leaf(member, LeafOp.PATH, null)
        } else {
            BindStep.Recurse(member, Scope.PATH)
        }
    }

    // A leaf-like value is one query parameter; a complex member with its own annotated members
    // recurses under the QUERY scope. A `Map` here is ambiguous with `@QueryMap` semantics, so it is
    // rejected.
    private fun queryStep(
        member: ScannedMember,
        marker: Query,
        scalar: Boolean,
    ): BindStep {
        if (isMapType(member.declaredType)) {
            throw KuriBindException("a Map under @Query is not allowed; use @QueryMap", member.name)
        }
        return if (isLeafLike(member.declaredType, scalar)) {
            BindStep.Leaf(member, LeafOp.QUERY, marker.value.ifEmpty { member.name })
        } else {
            BindStep.Recurse(member, Scope.QUERY)
        }
    }

    // A `@Query`/`@Path` member binds as a scalar leaf when it is a scalar or a collection, OR when it
    // is a complex type that carries no binding-annotated members of its own — a leaf-like type such
    // as `UUID`/`Instant`/`BigDecimal` then stringifies as one segment/param instead of recursing to
    // nothing. Only a genuine nested object (one that DOES have annotated members) recurses.
    private fun isLeafLike(
        type: KClass<*>,
        scalar: Boolean,
    ): Boolean = scalar || isCollectionType(type) || !hasBindingMembers(type)

    private fun hasBindingMembers(type: KClass<*>): Boolean =
        scanner.scan(type).any { member -> member.annotations.any { it.isBindingMarker() } }

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
}

// Enforces a bijection between the template's holes and the named-`@Path` providers reachable through
// the merge scope: every hole has exactly one provider, and every provider fills a hole. Pure (it
// touches no compiler state), so it lives top-level per the styleguide's stateless-helper convention.
private fun validateBijection(
    type: KClass<*>,
    holeNames: List<String>,
    providerNames: List<String>,
) {
    val where = type.simpleName
    for (name in holeNames) {
        val count = providerNames.count { it == name }
        if (count != 1) {
            val reason = if (count == 0) "no @Path provider" else "multiple @Path providers"
            throw KuriBindException("template hole '{$name}' has $reason", where)
        }
    }
    for (provider in providerNames) {
        if (provider !in holeNames) {
            throw KuriBindException("@Path(\"$provider\") fills no template hole", where)
        }
    }
}

// `@QueryMap` requires a `Map`; reject a non-Map at compile so it fails as fast as `@Query` on a `Map`
// (both are misconfiguration the plan should catch before any bind runs). Stateless, so top-level.
private fun queryMapStep(member: ScannedMember): BindStep {
    if (!isMapType(member.declaredType)) {
        throw KuriBindException("@QueryMap requires a Map", member.name)
    }
    return BindStep.Leaf(member, LeafOp.QUERY_MAP, null)
}

// `@Url`/`@Uri` merges a nested object's components into the parent; a scalar, collection, or map has
// no component-annotated members to merge, so reject it at compile rather than silently contributing
// nothing. Stateless, so top-level.
private fun mergeStep(member: ScannedMember): BindStep {
    val type = member.declaredType
    if (isScalarType(type) || isCollectionType(type) || isMapType(type)) {
        throw KuriBindException("@Url/@Uri requires a complex object, not ${type.simpleName}", member.name)
    }
    return BindStep.Recurse(member, Scope.MERGE)
}

private fun Annotation.isBindingMarker(): Boolean =
    when (this) {
        is Scheme, is UserInfo, is Username, is Password, is Host, is Port,
        is Path, is Query, is QueryMap, is Fragment, is UrlMarker, is UriMarker,
        -> true
        else -> false
    }

// A hole name paired with the root-relative accessor that reads its value; assembled during compile
// and folded into the [HoleProvider] map once the hole↔provider bijection is validated.
private class HoleProviderEntry(
    val name: String,
    val accessor: (Any) -> Any?,
)

// The erased-type shape predicates below are pure and touch no compiler state, so they live as
// file-private top-level utilities (the styleguide keeps stateless helpers off the class).

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
