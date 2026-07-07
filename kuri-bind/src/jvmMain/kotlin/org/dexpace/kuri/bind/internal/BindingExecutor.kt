/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.bind.internal

import org.dexpace.kuri.bind.BindOptions
import org.dexpace.kuri.bind.KuriBindException
import java.util.Collections
import java.util.IdentityHashMap

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
    private val compiler: PlanCompiler,
) {
    /** Executes [target]'s plan into a fresh [ComponentSink], honoring [options]. */
    fun execute(
        target: Any,
        options: BindOptions,
    ): ComponentSink {
        val sink = ComponentSink(options.strict)
        val active: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        // A root `@PathTemplate` owns the whole path, so positional `@Path` contributions from the
        // merge scope are suppressed for the entire walk; the flag rides along in [WalkContext]. The
        // root plan is fetched once here and threaded into [walk] (each merge step re-fetches its own
        // child plan), so the root type is not looked up twice.
        val rootPlan = compiler.planFor(target::class)
        val ctx = WalkContext(sink, options, active, depth = 0, pathTemplated = rootPlan.template != null)
        walk(target, rootPlan, ctx)
        return sink
    }

    private fun walk(
        target: Any,
        plan: TypePlan,
        ctx: WalkContext,
    ) {
        require(ctx.depth >= 0) { "walk depth must be non-negative: ${ctx.depth}" }
        check(plan.type == target::class) { "threaded plan ${plan.type} does not match target ${target::class}" }
        checkDepth(ctx.depth, ctx.options.maxDepth)
        if (!ctx.active.add(target)) throw KuriBindException("cycle detected at ${target::class.simpleName}")
        try {
            plan.template?.let { template -> TemplateBinder.emit(template, plan.holeProviders, target, ctx.sink) }
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
            is BindStep.Leaf -> applyLeafStep(step, target, ctx)
            is BindStep.Recurse -> applyRecurseStep(step, target, ctx)
        }
    }

    private fun applyLeafStep(
        step: BindStep.Leaf,
        target: Any,
        ctx: WalkContext,
    ) {
        // Skip a leaf that this walk must not apply: userinfo leaves are paired once per object by
        // UserInfoBinder, and under a templated root the path is owned entirely by the template, so a
        // merge member's own `@Path` value (already emitted as a hole) must not double as a segment.
        if (step.op.isUserInfo() || (step.op == LeafOp.PATH && ctx.pathTemplated)) return
        val value = step.member.read(target) ?: return
        applyLeafValue(step, value, ctx)
    }

    // A @Query/@Path member typed as an interface/`Any`/erased generic compiles to a leaf (its declared
    // type carries no binding members), but if the RUNTIME value is actually a bindable object, recurse
    // it under the matching scope — the same value-class dispatch @Url/MERGE uses — instead of
    // stringifying it. Scalars, collections, and maps are unaffected and bind as before.
    private fun applyLeafValue(
        step: BindStep.Leaf,
        value: Any,
        ctx: WalkContext,
    ) {
        if ((step.op == LeafOp.QUERY || step.op == LeafOp.PATH) && runtimeValueIsBindable(value, compiler)) {
            applyRecurse(BindStep.Recurse(step.member, scopeFor(step.op)), value, ctx)
        } else {
            LeafBinder.apply(step, value, ctx.sink)
        }
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
            Scope.MERGE -> walk(value, compiler.planFor(value::class), ctx.deeper())
            Scope.QUERY -> recurseScoped(value, ctx, LeafOp.QUERY)
            // A templated root owns the path (see [applyLeafStep]); skip scoped path recursion too.
            Scope.PATH -> if (!ctx.pathTemplated) recurseScoped(value, ctx, LeafOp.PATH)
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
        val plan = compiler.planFor(nested::class)
        // A scoped `@Query` object contributes every query leaf it declares — both its `@Query`
        // params and its `@QueryMap` entries; a scoped `@Path` object contributes its path segments.
        val leaves =
            plan.steps
                .filterIsInstance<BindStep.Leaf>()
                .filter { it.op == op || (op == LeafOp.QUERY && it.op == LeafOp.QUERY_MAP) }
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
    val pathTemplated: Boolean,
) {
    fun deeper(): WalkContext = WalkContext(sink, options, active, depth + 1, pathTemplated)
}

/**
 * Resolves a root `@PathTemplate` against its holes and emits the filled path in template order.
 *
 * Each hole's value is read through the composed accessor the compiler recorded on
 * [TypePlan.holeProviders], so a value supplied by an `@Url`/`@Uri`-merged child resolves straight
 * from the root without consulting that child's own (non-template) plan. The template is then
 * flattened to whole decoded segments — literals split on `/`, a `{name}` hole is one segment, a
 * `{name...}` catch-all expands a collection or a slash-string — and each is pushed as one segment,
 * so an interior `/` separator never injects an empty segment.
 */
private object TemplateBinder {
    fun emit(
        template: PathTemplate,
        holeProviders: Map<String, HoleProvider>,
        target: Any,
        sink: ComponentSink,
    ) {
        val resolved =
            holeProviders.mapValues { (_, provider) -> ResolvedHole(provider.accessor(target), provider.catchAll) }
        for (segment in templateSegments(template.tokens, resolved)) sink.addPathSegment(segment)
    }
}

/** A template hole resolved for one bind: its [value] read from the root, plus whether it is catch-all. */
private class ResolvedHole(
    val value: Any?,
    val catchAll: Boolean,
)

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
            LeafOp.QUERY -> addQueryValues(step.queryName ?: path, value, sink)
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
        for ((key, entryValue) in map) applyQueryMapEntry(key, entryValue, sink, path)
    }

    private fun applyPath(
        value: Any,
        sink: ComponentSink,
    ) = forEachScoped(value) { sink.addPathSegment(scalarText(it)) }

    private fun applyQueryMapEntry(
        key: Any?,
        value: Any?,
        sink: ComponentSink,
        path: String,
    ) {
        val name = key?.let(::scalarText) ?: return
        if (name.isEmpty()) throw KuriBindException("query parameter name must not be empty", path)
        addQueryValues(name, value, sink)
    }

    // Contributes a `@Query` value under [name]: a collection fans out into one parameter per element
    // (a null element yields a valueless parameter), any other value becomes a single scalar parameter.
    // The direct `@Query` leaf and each `@QueryMap` entry share this; they differ only in how [name] is
    // derived. A non-null [value] takes the same paths (`value?.let(::x)` collapses to `x(value)`).
    private fun addQueryValues(
        name: String,
        value: Any?,
        sink: ComponentSink,
    ) {
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
 * A `@UserInfo` token is split on its first `:` and a non-empty `@UserInfo` token takes precedence
 * over separate `@Username`/`@Password` members (an empty token defers to them): `username =
 * @UserInfo.user ?: @Username`, `password = @UserInfo.pass ?: @Password`. Emitting the pair once
 * (rather than two `setUserInfo` calls) keeps sibling `@Username` and `@Password` on one object from
 * clobbering each other under first-writer-wins.
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
        // A non-empty whole-token @UserInfo wins over split @Username/@Password; an EMPTY token is
        // treated as absent so a sibling @Username/@Password still wins.
        val split = rawFor(values, LeafOp.USERINFO)?.takeIf { it.isNotEmpty() }?.let(::splitUserInfo)
        val username = split?.username ?: rawFor(values, LeafOp.USERNAME) ?: ""
        val password = split?.password ?: rawFor(values, LeafOp.PASSWORD)
        // No userinfo members, or an all-empty gather, is no contribution rather than an error. The
        // empty check subsumes the "no values" case, so values.first() below only runs when non-empty.
        if (username.isEmpty() && password == null) return null
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

/**
 * Whether [value]'s runtime type is a bindable object (carries component annotations) rather than a
 * scalar/collection/map. A cheap scalar allowlist avoids reflecting over `String`/`Int`/`BigDecimal`/
 * enums etc.; anything else is probed through the plan cache (empty plan → not bindable → a leaf).
 */
private fun runtimeValueIsBindable(
    value: Any,
    compiler: PlanCompiler,
): Boolean {
    val scalarLike = value is CharSequence || value is Number || value is Char || value is Boolean || value is Enum<*>
    if (scalarLike || asIterableOrNull(value) != null || asMapOrNull(value) != null) return false
    return compiler.planFor(value::class).steps.isNotEmpty()
}

/** The recursion scope matching a scalar-scoped leaf op. */
private fun scopeFor(op: LeafOp): Scope = if (op == LeafOp.QUERY) Scope.QUERY else Scope.PATH

/** Whether this op feeds the single userinfo slot and is therefore paired by [UserInfoBinder]. */
private fun LeafOp.isUserInfo(): Boolean = this == LeafOp.USERINFO || this == LeafOp.USERNAME || this == LeafOp.PASSWORD

/** Flattens template [tokens] against the [resolved] hole values into whole decoded path segments. */
private fun templateSegments(
    tokens: List<PathToken>,
    resolved: Map<String, ResolvedHole>,
): List<String> {
    val segments = ArrayList<String>()
    for (token in tokens) appendTokenSegments(token, resolved, segments)
    return segments
}

private fun appendTokenSegments(
    token: PathToken,
    resolved: Map<String, ResolvedHole>,
    into: MutableList<String>,
) {
    when (token) {
        is PathToken.Literal -> into.addAll(splitPathSegments(token.raw))
        is PathToken.Hole -> appendHoleSegments(token, resolved, into)
    }
}

private fun appendHoleSegments(
    token: PathToken.Hole,
    resolved: Map<String, ResolvedHole>,
    into: MutableList<String>,
) {
    val hole = checkNotNull(resolved[token.name]) { "template hole '{${token.name}}' has no resolved provider" }
    val value = hole.value ?: throw KuriBindException("template hole '{${token.name}}' resolved to null")
    if (hole.catchAll) {
        appendCatchAllSegments(value, into)
    } else {
        // A single-segment `{name}` hole must be scalar; a collection cannot become one segment.
        if (asIterableOrNull(value) != null) {
            throw KuriBindException("template hole '{${token.name}}' is single-segment but was given a collection")
        }
        into.add(scalarText(value))
    }
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
