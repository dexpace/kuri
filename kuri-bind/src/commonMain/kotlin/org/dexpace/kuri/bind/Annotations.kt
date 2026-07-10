/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.bind

/**
 * Marks a root class to bind as a `Url`, or a member for a full sub-object merge.
 *
 * On a member, `@Url` and `@Uri` are interchangeable: both merge the member's components into the
 * parent, and the bound profile is fixed by the root marker, never by the member's.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.VALUE_PARAMETER,
)
public annotation class Url

/**
 * Marks a root class to bind as a `Uri`, or a member for a full sub-object merge.
 *
 * On a member, `@Uri` and `@Url` are interchangeable: both merge the member's components into the
 * parent, and the bound profile is fixed by the root marker, never by the member's.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.VALUE_PARAMETER,
)
public annotation class Uri

/**
 * A Go-style path skeleton on the root: `{name}` single segment, `{name...}` catch-all tail.
 *
 * A leading `/` is decorative for an authority-less URI: with no authority a segment path roots only
 * under one, so `@PathTemplate("/a/{id}")` renders as `a/7` (not `/a/7`); once an authority is present
 * the path re-roots regardless.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
public annotation class PathTemplate(
    val value: String,
)

/** Single-valued scheme. */
@Retention(AnnotationRetention.RUNTIME)
@Target(
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.VALUE_PARAMETER,
)
public annotation class Scheme

/** Whole decoded userinfo token (`user:password`); feeds the single userinfo slot. */
@Retention(AnnotationRetention.RUNTIME)
@Target(
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.VALUE_PARAMETER,
)
public annotation class UserInfo

/** Decoded userinfo username; feeds the userinfo slot. */
@Retention(AnnotationRetention.RUNTIME)
@Target(
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.VALUE_PARAMETER,
)
public annotation class Username

/** Decoded userinfo password; feeds the userinfo slot. */
@Retention(AnnotationRetention.RUNTIME)
@Target(
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.VALUE_PARAMETER,
)
public annotation class Password

/** Single-valued host text, or a `Host` value. */
@Retention(AnnotationRetention.RUNTIME)
@Target(
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.VALUE_PARAMETER,
)
public annotation class Host

/** Single-valued port. */
@Retention(AnnotationRetention.RUNTIME)
@Target(
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.VALUE_PARAMETER,
)
public annotation class Port

/** Fills the same-named template hole ([value]); without a template, appends segment(s) positionally. */
@Retention(AnnotationRetention.RUNTIME)
@Target(
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.VALUE_PARAMETER,
)
public annotation class Path(
    val value: String = "",
)

/**
 * One query parameter (scalar/collection) or scalar-scoped recursion (complex); [value] is the name.
 *
 * On an iterable member, [delimiter] switches the parameter from repeated params (`roles=admin&roles=user`,
 * the default) to a single joined value (`roles=admin,user`): the non-null elements' scalar text is
 * joined with [delimiter], in order. A null element is skipped; an empty or all-null collection emits
 * no parameter at all, matching the unjoined fan-out's empty-collection behavior. On a scalar member
 * [delimiter] is ignored — a join has no meaning for one value.
 *
 * The NUL sentinel (`'\u0000'`) marks [delimiter] as unset, since NUL cannot appear in a URL and so
 * can never be a real join delimiter. [delimiter] never applies to `@QueryMap` — that stays fan-out only.
 *
 * A join is lossy if an element's text itself contains [delimiter]: the joined value is then not
 * recoverable via `QueryParameters.split`, which re-splits on every occurrence of the delimiter after
 * decoding. Choose a delimiter that is absent from the elements' text, the same caveat `split` carries.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.VALUE_PARAMETER,
)
public annotation class Query(
    val value: String = "",
    val delimiter: Char = '\u0000',
)

/** A `Map` member: one query parameter per entry. */
@Retention(AnnotationRetention.RUNTIME)
@Target(
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.VALUE_PARAMETER,
)
public annotation class QueryMap

/** Single-valued fragment (decoded). */
@Retention(AnnotationRetention.RUNTIME)
@Target(
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.VALUE_PARAMETER,
)
public annotation class Fragment
