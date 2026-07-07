/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.bind

import org.dexpace.kuri.Uri
import org.dexpace.kuri.Url
import org.dexpace.kuri.bind.internal.BindingExecutor
import org.dexpace.kuri.bind.internal.KotlinReflectMemberScanner
import org.dexpace.kuri.bind.internal.PlanCompiler
import org.dexpace.kuri.bind.internal.UriBuilderSink
import org.dexpace.kuri.bind.internal.UrlBuilderSink
import kotlin.reflect.full.hasAnnotation
import org.dexpace.kuri.bind.Uri as UriMarker
import org.dexpace.kuri.bind.Url as UrlMarker

/**
 * The public entry point for binding an annotated object onto a kuri builder.
 *
 * Annotate the root class with [Url] or [Uri], mark its members with the component annotations
 * ([Scheme], [Host], [Path], [Query], ...), then call one of these methods. URL entry points require an
 * `@Url` root and URI entry points require a `@Uri` root; a missing or mismatched marker raises
 * [KuriBindException]. The `...OrNull` variants translate an [IllegalArgumentException]-family binding
 * or build failure into `null`, while a fault thrown by the target's own accessor still propagates.
 *
 * **Member order.** Source-order fidelity is guaranteed for Kotlin classes with a primary constructor
 * (members bind in constructor-parameter order) and for Java records (members bind in canonical
 * component order). Every other shape (plain Java beans, and body-declared or non-constructor
 * properties) has no reliable declaration order through reflection, so those members bind in a stable,
 * deterministic order sorted by name. Where positional order matters — repeated `@Path` segments
 * especially — use a Kotlin primary-constructor class (a `data class`), a Java record, or pin the
 * order explicitly with [PathTemplate].
 *
 * **Precedence within the object graph.** Single-valued components are first-writer-wins in walk
 * order, which follows declaration order: the template, then userinfo, then each member step in turn.
 * A parent's own leaf (say `@Host`) therefore wins over the same component merged from an `@Url`/`@Uri`
 * child only when the leaf is declared *before* that merge member; declare the component ahead of the
 * `@Url` member when the parent's value must win.
 *
 * A single compiled-plan cache backs every call and is shared across both profiles, so a root type is
 * scanned and validated exactly once regardless of how often — or through which profile — it is bound.
 * All methods are stateless and safe to call concurrently.
 */
public object KuriBind {
    // One shared executor backs every bind through either profile: its [PlanCompiler] caches a compiled
    // plan per root type, and a plan describes a `Url` and a `Uri` bind alike, so each root type is
    // compiled exactly once regardless of profile. The executor is stateless; each `bindInto` overload
    // projects its accumulated result through the sink for the profile it targets.
    private val executor = BindingExecutor(PlanCompiler(KotlinReflectMemberScanner()))

    /**
     * Binds [target] onto [base], returning the same builder so calls can chain.
     *
     * [target]'s single-valued components ([Scheme], [Host], [Port], userinfo, [Fragment]) **override**
     * whatever [base] already holds for that slot; a component [target] does not carry leaves [base]'s
     * value untouched. [target]'s path segments and query parameters are **appended** on top of [base].
     * [BindOptions.strict] governs conflicts **within** [target]'s own object graph (e.g. a merged
     * sub-object disagreeing with its parent) — it does not compare [target] against [base].
     *
     * @param base the builder to bind into; the returned instance is always this same object.
     * @param target the `@Url`-annotated root object to read component values from.
     * @param options strictness and depth bounds for the walk; defaults to [BindOptions.DEFAULT].
     * @return [base], now carrying [target]'s contributions.
     * @throws KuriBindException when [target]'s root class is not annotated `@Url`, or the object graph
     *   cannot be bound (bad annotation set, unconvertible value, strict conflict, depth/cycle overflow).
     */
    @JvmStatic
    @JvmOverloads
    public fun bindInto(
        base: Url.Builder,
        target: Any,
        options: BindOptions = BindOptions.DEFAULT,
    ): Url.Builder {
        requireRoot(target, Profile.URL)
        executor.execute(target, options).projectInto(UrlBuilderSink(base))
        return base
    }

    /**
     * Binds [target] onto [base] for the `Uri` profile, returning the same builder so calls can chain.
     *
     * @param base the builder to bind into; the returned instance is always this same object.
     * @param target the `@Uri`-annotated root object to read component values from.
     * @param options strictness and depth bounds for the walk; defaults to [BindOptions.DEFAULT].
     * @return [base], now carrying [target]'s contributions.
     * @throws KuriBindException when [target]'s root class is not annotated `@Uri`, or the object graph
     *   cannot be bound (bad annotation set, unconvertible value, strict conflict, depth/cycle overflow).
     */
    @JvmStatic
    @JvmOverloads
    public fun bindInto(
        base: Uri.Builder,
        target: Any,
        options: BindOptions = BindOptions.DEFAULT,
    ): Uri.Builder {
        requireRoot(target, Profile.URI)
        executor.execute(target, options).projectInto(UriBuilderSink(base))
        return base
    }

    /**
     * Binds [target] into a fresh, empty [Url.Builder].
     *
     * @param target the `@Url`-annotated root object.
     * @param options strictness and depth bounds; defaults to [BindOptions.DEFAULT].
     * @return a new builder carrying [target]'s contributions.
     * @throws KuriBindException when [target]'s root is not `@Url`, or the object graph cannot be bound.
     */
    @JvmStatic
    @JvmOverloads
    public fun toUrlBuilder(
        target: Any,
        options: BindOptions = BindOptions.DEFAULT,
    ): Url.Builder = bindInto(Url.Builder(), target, options)

    /**
     * Binds [target] into a fresh builder and builds the [Url].
     *
     * @param target the `@Url`-annotated root object.
     * @param options strictness and depth bounds; defaults to [BindOptions.DEFAULT].
     * @return the built URL.
     * @throws KuriBindException when [target]'s root is not `@Url`, the object graph cannot be bound, or
     *   the accumulated components do not form a valid URL.
     */
    @JvmStatic
    @JvmOverloads
    public fun toUrl(
        target: Any,
        options: BindOptions = BindOptions.DEFAULT,
    ): Url = toUrlBuilder(target, options).build()

    /**
     * Like [toUrlBuilder], but returns `null` when binding fails with an [IllegalArgumentException] —
     * the family every expected binding failure belongs to ([KuriBindException] plus the builders' own
     * validation). A fault thrown by [target]'s own accessor (for example a computed getter that raises
     * a non-argument exception) is not swallowed; it propagates to the caller.
     *
     * @param target the root object.
     * @param options strictness and depth bounds; defaults to [BindOptions.DEFAULT].
     * @return a new builder, or `null` when binding fails in the [IllegalArgumentException] family.
     */
    @JvmStatic
    @JvmOverloads
    public fun toUrlBuilderOrNull(
        target: Any,
        options: BindOptions = BindOptions.DEFAULT,
    ): Url.Builder? = nullOnInvalid { toUrlBuilder(target, options) }

    /**
     * Like [toUrl], but returns `null` when binding or building fails with an [IllegalArgumentException]
     * (the family of every expected binding/build failure). A fault thrown by [target]'s own accessor is
     * not swallowed; it propagates to the caller.
     *
     * @param target the root object.
     * @param options strictness and depth bounds; defaults to [BindOptions.DEFAULT].
     * @return the built URL, or `null` when binding or building fails in the [IllegalArgumentException]
     *   family.
     */
    @JvmStatic
    @JvmOverloads
    public fun toUrlOrNull(
        target: Any,
        options: BindOptions = BindOptions.DEFAULT,
    ): Url? = toUrlBuilderOrNull(target, options)?.buildOrNull()

    /**
     * Binds [target] into a fresh, empty [Uri.Builder].
     *
     * @param target the `@Uri`-annotated root object.
     * @param options strictness and depth bounds; defaults to [BindOptions.DEFAULT].
     * @return a new builder carrying [target]'s contributions.
     * @throws KuriBindException when [target]'s root is not `@Uri`, or the object graph cannot be bound.
     */
    @JvmStatic
    @JvmOverloads
    public fun toUriBuilder(
        target: Any,
        options: BindOptions = BindOptions.DEFAULT,
    ): Uri.Builder = bindInto(Uri.Builder(), target, options)

    /**
     * Binds [target] into a fresh builder and builds the [Uri].
     *
     * @param target the `@Uri`-annotated root object.
     * @param options strictness and depth bounds; defaults to [BindOptions.DEFAULT].
     * @return the built URI.
     * @throws KuriBindException when [target]'s root is not `@Uri`, the object graph cannot be bound, or
     *   the accumulated components do not form a valid URI.
     */
    @JvmStatic
    @JvmOverloads
    public fun toUri(
        target: Any,
        options: BindOptions = BindOptions.DEFAULT,
    ): Uri = toUriBuilder(target, options).build()

    /**
     * Like [toUriBuilder], but returns `null` when binding fails with an [IllegalArgumentException] —
     * the family every expected binding failure belongs to ([KuriBindException] plus the builders' own
     * validation). A fault thrown by [target]'s own accessor is not swallowed; it propagates to the
     * caller.
     *
     * @param target the root object.
     * @param options strictness and depth bounds; defaults to [BindOptions.DEFAULT].
     * @return a new builder, or `null` when binding fails in the [IllegalArgumentException] family.
     */
    @JvmStatic
    @JvmOverloads
    public fun toUriBuilderOrNull(
        target: Any,
        options: BindOptions = BindOptions.DEFAULT,
    ): Uri.Builder? = nullOnInvalid { toUriBuilder(target, options) }

    /**
     * Like [toUri], but returns `null` when binding or building fails with an [IllegalArgumentException]
     * (the family of every expected binding/build failure). A fault thrown by [target]'s own accessor is
     * not swallowed; it propagates to the caller.
     *
     * @param target the root object.
     * @param options strictness and depth bounds; defaults to [BindOptions.DEFAULT].
     * @return the built URI, or `null` when binding or building fails in the [IllegalArgumentException]
     *   family.
     */
    @JvmStatic
    @JvmOverloads
    public fun toUriOrNull(
        target: Any,
        options: BindOptions = BindOptions.DEFAULT,
    ): Uri? = toUriBuilderOrNull(target, options)?.buildOrNull()
}

/**
 * Asserts that [target]'s runtime class carries the root marker for [profile], failing fast otherwise.
 *
 * The `Url`/`Uri` classes and the `@Url`/`@Uri` markers share a simple name, so the markers are
 * matched via their aliased [UrlMarker]/[UriMarker] annotation types.
 *
 * @throws KuriBindException when the marker is absent, naming the class and the expected marker.
 */
private fun requireRoot(
    target: Any,
    profile: Profile,
) {
    val marked =
        when (profile) {
            Profile.URL -> target::class.hasAnnotation<UrlMarker>()
            Profile.URI -> target::class.hasAnnotation<UriMarker>()
        }
    if (!marked) {
        throw KuriBindException("root ${target::class.simpleName} is not annotated @${profile.name.lowercase()}")
    }
}

/**
 * Runs [block], returning `null` when it fails with an [IllegalArgumentException] and rethrowing
 * anything else.
 *
 * Every expected binding failure is an [IllegalArgumentException] — [KuriBindException] extends it,
 * and the builders' range checks throw it — so narrowing to that type keeps the `null`-on-failure
 * contract while letting an [Error] or an unexpected programmer fault propagate instead of being
 * silently swallowed.
 */
private inline fun <T> nullOnInvalid(block: () -> T): T? =
    runCatching(block).getOrElse { cause -> if (cause is IllegalArgumentException) null else throw cause }
