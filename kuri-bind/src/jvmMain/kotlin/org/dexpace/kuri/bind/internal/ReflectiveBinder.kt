/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.bind.internal

import org.dexpace.kuri.Uri
import org.dexpace.kuri.Url
import org.dexpace.kuri.bind.BindOptions
import org.dexpace.kuri.bind.KuriBindException
import org.dexpace.kuri.bind.UriBinder
import org.dexpace.kuri.bind.UrlBinder
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.reflect.KClass

/**
 * Walks a compiled [TypePlan] against a live instance, accumulating decoded contributions in a
 * [ComponentSink] under identity-based cycle detection and a depth bound.
 *
 * Recursion is guarded two ways: [BindOptions.maxDepth] caps how deep the object graph is walked, and
 * an identity set of the objects currently on the walk stack rejects a self-referential graph — both
 * overflow paths raise [KuriBindException]. Leaf, template, and userinfo handling are delegated to the
 * stateless [LeafBinder]/[UserInfoBinder] and the [TemplateBinder] collaborator, keeping this class to
 * the graph traversal itself.
 */
internal class BindingExecutor(
    private val cache: PlanCache,
) {
    private val templateBinder = TemplateBinder(cache)

    /** Executes [target]'s plan into a fresh [ComponentSink], honoring [options]. */
    fun execute(
        target: Any,
        options: BindOptions,
    ): ComponentSink {
        val sink = ComponentSink(options.strict)
        val active: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        walk(target, WalkContext(sink, options, active, depth = 0))
        return sink
    }

    private fun walk(
        target: Any,
        ctx: WalkContext,
    ) {
        require(ctx.depth >= 0) { "walk depth must be non-negative: ${ctx.depth}" }
        checkDepth(ctx.depth, ctx.options.maxDepth)
        if (!ctx.active.add(target)) throw KuriBindException("cycle detected at ${target::class.simpleName}")
        try {
            val plan = cache.planFor(target::class)
            plan.template?.let { templateBinder.emit(it, plan, target, ctx.sink) }
            UserInfoBinder.apply(plan, target, ctx.sink)
            for (step in plan.steps) applyStep(step, target, ctx)
        } finally {
            ctx.active.remove(target)
        }
    }

    private fun applyStep(
        step: BindStep,
        target: Any,
        ctx: WalkContext,
    ) {
        when (step) {
            is BindStep.Hole -> Unit // Holes are consumed by TemplateBinder, not applied as steps.
            is BindStep.Leaf -> applyLeafStep(step, target, ctx.sink)
            is BindStep.Recurse -> applyRecurseStep(step, target, ctx)
        }
    }

    private fun applyLeafStep(
        step: BindStep.Leaf,
        target: Any,
        sink: ComponentSink,
    ) {
        if (step.op.isUserInfo()) return // Userinfo leaves are paired once per object by UserInfoBinder.
        val value = step.member.read(target) ?: return
        LeafBinder.apply(step, value, sink)
    }

    private fun applyRecurseStep(
        step: BindStep.Recurse,
        target: Any,
        ctx: WalkContext,
    ) {
        val value = step.member.read(target) ?: return
        applyRecurse(step, value, ctx)
    }

    private fun applyRecurse(
        step: BindStep.Recurse,
        value: Any,
        ctx: WalkContext,
    ) {
        when (step.scope) {
            Scope.MERGE -> walk(value, ctx.deeper())
            Scope.QUERY -> recurseScoped(value, ctx, LeafOp.QUERY)
            Scope.PATH -> recurseScoped(value, ctx, LeafOp.PATH)
            Scope.QUERY_MAP -> LeafBinder.applyQueryMap(value, ctx.sink, step.member.name)
        }
    }

    // Scalar-scoped recursion: a complex `@Query`/`@Path` member contributes only its own matching
    // leaves (its query params, or its path segments), one graph level deep. A collection member
    // repeats that for each element. The one-level descent is still charged against [maxDepth].
    private fun recurseScoped(
        value: Any,
        ctx: WalkContext,
        op: LeafOp,
    ) {
        checkDepth(ctx.depth + 1, ctx.options.maxDepth)
        forEachScoped(value) { nested -> applyScopedLeaves(nested, ctx.sink, op) }
    }

    private fun applyScopedLeaves(
        nested: Any,
        sink: ComponentSink,
        op: LeafOp,
    ) {
        require(op == LeafOp.QUERY || op == LeafOp.PATH) { "scoped op must be QUERY or PATH: $op" }
        val plan = cache.planFor(nested::class)
        val leaves = plan.steps.filterIsInstance<BindStep.Leaf>().filter { it.op == op }
        for (leaf in leaves) applyScopedLeaf(leaf, nested, sink)
    }

    private fun applyScopedLeaf(
        leaf: BindStep.Leaf,
        nested: Any,
        sink: ComponentSink,
    ) {
        val value = leaf.member.read(nested) ?: return
        LeafBinder.apply(leaf, value, sink)
    }
}

/**
 * The invariant state threaded through one [BindingExecutor.execute] walk — the accumulating [sink],
 * the [options], and the identity [active] set of objects on the walk stack — plus the current [depth].
 *
 * Bundling these keeps each traversal function within the parameter budget; [deeper] descends one graph
 * level while sharing the same [sink] and [active] set, so cycle detection and accumulation span the
 * whole walk.
 */
private class WalkContext(
    val sink: ComponentSink,
    val options: BindOptions,
    val active: MutableSet<Any>,
    val depth: Int,
) {
    fun deeper(): WalkContext = WalkContext(sink, options, active, depth + 1)
}

/** Binds an annotated object onto a `Url.Builder` by reflection. */
internal class ReflectiveUrlBinder(
    cache: PlanCache,
) : UrlBinder {
    private val executor = BindingExecutor(cache)

    override fun bind(
        base: Url.Builder,
        target: Any,
        options: BindOptions,
    ): Url.Builder {
        executor.execute(target, options).projectInto(UrlBuilderSink(base))
        return base
    }
}

/** Binds an annotated object onto a `Uri.Builder` by reflection. */
internal class ReflectiveUriBinder(
    cache: PlanCache,
) : UriBinder {
    private val executor = BindingExecutor(cache)

    override fun bind(
        base: Uri.Builder,
        target: Any,
        options: BindOptions,
    ): Uri.Builder {
        executor.execute(target, options).projectInto(UriBuilderSink(base))
        return base
    }
}

/**
 * Resolves a root `@PathTemplate` against its holes and emits the filled path in template order.
 *
 * Holes are gathered from the root object plus every `@Url`/`@Uri` MERGE member reachable from it
 * (bounded by a per-type `seen` set), matching the provider set the compiler validated. The template
 * is then flattened to whole decoded segments — literals split on `/`, a `{name}` hole is one segment,
 * a `{name...}` catch-all expands a collection or a slash-string — and each is pushed as one segment,
 * so an interior `/` separator never injects an empty segment.
 */
private class TemplateBinder(
    private val cache: PlanCache,
) {
    fun emit(
        template: PathTemplate,
        plan: TypePlan,
        target: Any,
        sink: ComponentSink,
    ) {
        val providers = holeValues(plan, target, HashMap(), HashSet())
        val segments = templateSegments(template.tokens, providers)
        for (segment in segments) sink.addPathSegment(segment)
    }

    private fun holeValues(
        plan: TypePlan,
        target: Any,
        into: MutableMap<String, Any?>,
        seen: MutableSet<KClass<*>>,
    ): Map<String, Any?> {
        if (!seen.add(plan.type)) return into
        for (step in plan.steps) collectHoleValue(step, target, into, seen)
        return into
    }

    private fun collectHoleValue(
        step: BindStep,
        target: Any,
        into: MutableMap<String, Any?>,
        seen: MutableSet<KClass<*>>,
    ) {
        when (step) {
            is BindStep.Hole -> into[step.name] = step.member.read(target)
            is BindStep.Recurse -> mergeHoleValues(step, target, into, seen)
            is BindStep.Leaf -> Unit
        }
    }

    private fun mergeHoleValues(
        step: BindStep.Recurse,
        target: Any,
        into: MutableMap<String, Any?>,
        seen: MutableSet<KClass<*>>,
    ) {
        if (step.scope != Scope.MERGE) return
        val nested = step.member.read(target) ?: return
        holeValues(cache.planFor(nested::class), nested, into, seen)
    }
}

/** Applies a single [BindStep.Leaf] to the sink; stateless, so the same routines serve scoped leaves. */
private object LeafBinder {
    fun apply(
        step: BindStep.Leaf,
        value: Any,
        sink: ComponentSink,
    ) {
        val path = step.member.name
        when (step.op) {
            LeafOp.SCHEME -> sink.setScheme(scalarText(value), path)
            LeafOp.HOST -> sink.setHost(hostValueOf(value), path)
            LeafOp.PORT -> sink.setPort(convertPort(value, path), path)
            LeafOp.FRAGMENT -> sink.setFragment(scalarText(value), path)
            LeafOp.PATH -> applyPath(value, sink)
            LeafOp.QUERY -> applyQuery(step.queryName ?: path, value, sink)
            LeafOp.QUERY_MAP -> applyQueryMap(value, sink, path)
            // Userinfo leaves are paired into one contribution by UserInfoBinder, never applied here.
            LeafOp.USERINFO, LeafOp.USERNAME, LeafOp.PASSWORD -> Unit
        }
    }

    fun applyQueryMap(
        value: Any,
        sink: ComponentSink,
        path: String,
    ) {
        val map = asMapOrNull(value) ?: throw KuriBindException("@QueryMap requires a Map", path)
        for ((key, entryValue) in map) applyQueryMapEntry(key, entryValue, sink)
    }

    private fun applyPath(
        value: Any,
        sink: ComponentSink,
    ) {
        val iterable = asIterableOrNull(value)
        if (iterable != null) {
            iterable.filterNotNull().forEach { sink.addPathSegment(scalarText(it)) }
        } else {
            sink.addPathSegment(scalarText(value))
        }
    }

    private fun applyQuery(
        name: String,
        value: Any,
        sink: ComponentSink,
    ) {
        val iterable = asIterableOrNull(value)
        if (iterable != null) {
            iterable.forEach { sink.addQueryParameter(name, it?.let(::scalarText)) }
        } else {
            sink.addQueryParameter(name, scalarText(value))
        }
    }

    private fun applyQueryMapEntry(
        key: Any?,
        value: Any?,
        sink: ComponentSink,
    ) {
        val name = key?.let(::scalarText) ?: return
        val iterable = value?.let(::asIterableOrNull)
        if (iterable != null) {
            iterable.forEach { sink.addQueryParameter(name, it?.let(::scalarText)) }
        } else {
            sink.addQueryParameter(name, value?.let(::scalarText))
        }
    }
}

/**
 * Pairs an object's userinfo leaves into a single [ComponentSink.setUserInfo] contribution.
 *
 * A `@UserInfo` token is split on its first `:` and takes precedence over separate `@Username`/
 * `@Password` members: `username = @UserInfo.user ?: @Username`, `password = @UserInfo.pass ?:
 * @Password`. Emitting the pair once (rather than two `setUserInfo` calls) keeps sibling
 * `@Username` and `@Password` on one object from clobbering each other under first-writer-wins.
 */
private object UserInfoBinder {
    fun apply(
        plan: TypePlan,
        target: Any,
        sink: ComponentSink,
    ) {
        val gathered = gather(plan, target) ?: return
        sink.setUserInfo(gathered.username, gathered.password, gathered.path)
    }

    private fun gather(
        plan: TypePlan,
        target: Any,
    ): GatheredUserInfo? {
        val values = readValues(plan, target)
        if (values.isEmpty()) return null
        val username = resolveUsername(values) ?: ""
        val password = resolvePassword(values)
        return GatheredUserInfo(
            username,
            password,
            values
                .first()
                .first.member.name,
        )
    }

    private fun readValues(
        plan: TypePlan,
        target: Any,
    ): List<Pair<BindStep.Leaf, String>> =
        plan.steps
            .filterIsInstance<BindStep.Leaf>()
            .filter { it.op.isUserInfo() }
            .mapNotNull { leaf -> leaf.member.read(target)?.let { leaf to scalarText(it) } }

    private fun resolveUsername(values: List<Pair<BindStep.Leaf, String>>): String? =
        rawFor(values, LeafOp.USERINFO)?.let { splitUserInfo(it).username } ?: rawFor(values, LeafOp.USERNAME)

    private fun resolvePassword(values: List<Pair<BindStep.Leaf, String>>): String? =
        rawFor(values, LeafOp.USERINFO)?.let { splitUserInfo(it).password } ?: rawFor(values, LeafOp.PASSWORD)

    private fun rawFor(
        values: List<Pair<BindStep.Leaf, String>>,
        op: LeafOp,
    ): String? = values.firstOrNull { it.first.op == op }?.second

    private data class GatheredUserInfo(
        val username: String,
        val password: String?,
        val path: String,
    )
}

/** Throws [KuriBindException] when the current walk [depth] exceeds [maxDepth]. */
private fun checkDepth(
    depth: Int,
    maxDepth: Int,
) {
    require(maxDepth >= 1) { "maxDepth must be positive: $maxDepth" }
    if (depth > maxDepth) throw KuriBindException("bind depth exceeded $maxDepth")
}

/** Applies [action] to each non-null element of a collection [value], or to [value] itself. */
private fun forEachScoped(
    value: Any,
    action: (Any) -> Unit,
) {
    val iterable = asIterableOrNull(value)
    if (iterable != null) iterable.filterNotNull().forEach(action) else action(value)
}

/** Whether this op feeds the single userinfo slot and is therefore paired by [UserInfoBinder]. */
private fun LeafOp.isUserInfo(): Boolean = this == LeafOp.USERINFO || this == LeafOp.USERNAME || this == LeafOp.PASSWORD

/** Flattens template [tokens] against resolved hole [providers] into whole decoded path segments. */
private fun templateSegments(
    tokens: List<PathToken>,
    providers: Map<String, Any?>,
): List<String> {
    val segments = ArrayList<String>()
    for (token in tokens) appendTokenSegments(token, providers, segments)
    return segments
}

private fun appendTokenSegments(
    token: PathToken,
    providers: Map<String, Any?>,
    into: MutableList<String>,
) {
    when (token) {
        is PathToken.Literal -> into.addAll(splitPathSegments(token.raw))
        is PathToken.Hole -> appendHoleSegments(token, providers, into)
    }
}

private fun appendHoleSegments(
    hole: PathToken.Hole,
    providers: Map<String, Any?>,
    into: MutableList<String>,
) {
    val value = providers[hole.name] ?: throw KuriBindException("template hole '{${hole.name}}' resolved to null")
    if (hole.catchAll) appendCatchAllSegments(value, into) else into.add(scalarText(value))
}

private fun appendCatchAllSegments(
    value: Any,
    into: MutableList<String>,
) {
    val iterable = asIterableOrNull(value)
    if (iterable != null) {
        into.addAll(iterable.filterNotNull().map { scalarText(it) })
    } else {
        into.addAll(splitPathSegments(scalarText(value)))
    }
}

/** Splits a raw slash-path into its non-empty segments, dropping the root and separator empties. */
private fun splitPathSegments(raw: String): List<String> = raw.split('/').filter { it.isNotEmpty() }
