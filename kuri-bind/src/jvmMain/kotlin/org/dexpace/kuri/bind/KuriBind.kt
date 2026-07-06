/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.bind

import org.dexpace.kuri.Uri
import org.dexpace.kuri.Url
import org.dexpace.kuri.bind.internal.KotlinReflectMemberScanner
import org.dexpace.kuri.bind.internal.PlanCache
import org.dexpace.kuri.bind.internal.PlanCompiler
import org.dexpace.kuri.bind.internal.ReflectiveUriBinder
import org.dexpace.kuri.bind.internal.ReflectiveUrlBinder
import kotlin.reflect.full.hasAnnotation
import org.dexpace.kuri.bind.Uri as UriMarker
import org.dexpace.kuri.bind.Url as UrlMarker

/**
 * The public entry point for binding an annotated object onto a kuri builder.
 *
 * Annotate the root class with [Url] or [Uri], mark its members with the component annotations
 * ([Scheme], [Host], [Path], [Query], ...), then call one of these methods. URL entry points require an
 * `@Url` root and URI entry points require a `@Uri` root; a missing or mismatched marker raises
 * [KuriBindException]. The `...OrNull` variants translate any binding or build failure into `null`.
 *
 * A single compiled-plan cache backs every call and is shared across both profiles, so a root type is
 * scanned and validated exactly once regardless of how often — or through which profile — it is bound.
 * All methods are stateless and safe to call concurrently.
 */
public object KuriBind {
    // One profile-agnostic plan cache backs both binders: a plan describes a `Url` and a `Uri` bind
    // alike, so sharing it means each root type is compiled exactly once (see PlanCache).
    private val cache = PlanCache(PlanCompiler(KotlinReflectMemberScanner()))
    private val urlBinder = ReflectiveUrlBinder(cache)
    private val uriBinder = ReflectiveUriBinder(cache)

    /**
     * Binds [target] onto [base], returning the same builder so calls can chain.
     *
     * [base] wins for single-valued components (first-writer-wins); [target] appends path segments and
     * query parameters on top of whatever [base] already holds.
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
        val bound = urlBinder.bind(base, target, options)
        check(bound === base) { "url binder must return the same base builder it was given" }
        return bound
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
        val bound = uriBinder.bind(base, target, options)
        check(bound === base) { "uri binder must return the same base builder it was given" }
        return bound
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
     * Like [toUrlBuilder], but returns `null` instead of throwing on any binding failure.
     *
     * @param target the root object.
     * @param options strictness and depth bounds; defaults to [BindOptions.DEFAULT].
     * @return a new builder, or `null` when binding fails for any reason.
     */
    @JvmStatic
    @JvmOverloads
    public fun toUrlBuilderOrNull(
        target: Any,
        options: BindOptions = BindOptions.DEFAULT,
    ): Url.Builder? = runCatching { toUrlBuilder(target, options) }.getOrNull()

    /**
     * Like [toUrl], but returns `null` instead of throwing on any binding or build failure.
     *
     * @param target the root object.
     * @param options strictness and depth bounds; defaults to [BindOptions.DEFAULT].
     * @return the built URL, or `null` when binding or building fails for any reason.
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
     * Like [toUriBuilder], but returns `null` instead of throwing on any binding failure.
     *
     * @param target the root object.
     * @param options strictness and depth bounds; defaults to [BindOptions.DEFAULT].
     * @return a new builder, or `null` when binding fails for any reason.
     */
    @JvmStatic
    @JvmOverloads
    public fun toUriBuilderOrNull(
        target: Any,
        options: BindOptions = BindOptions.DEFAULT,
    ): Uri.Builder? = runCatching { toUriBuilder(target, options) }.getOrNull()

    /**
     * Like [toUri], but returns `null` instead of throwing on any binding or build failure.
     *
     * @param target the root object.
     * @param options strictness and depth bounds; defaults to [BindOptions.DEFAULT].
     * @return the built URI, or `null` when binding or building fails for any reason.
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
