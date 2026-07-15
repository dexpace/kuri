/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.template

/*
 * RFC 6570 §3.2 expansion, decomposed into small pure functions. A varspec renders to zero or more
 * "units" (each already encoded, without any leading separator); the expression then joins them with
 * the operator's `first` prefix and `separator`, so the first/sep bookkeeping lives in one place.
 */

/** Expands one expression, appending its contribution (possibly empty) to [out]. */
internal fun expandExpression(
    expression: Part.Expression,
    variables: Map<String, Any?>,
    out: StringBuilder,
) {
    val op = expression.operator
    val units = mutableListOf<String>()
    for (varspec in expression.varspecs) renderVarspec(op, varspec, variables[varspec.name], units)
    if (units.isEmpty()) return
    out.append(op.first).append(units.joinToString(op.separator))
}

/** Classifies [value] (undefined / scalar / list / map) and appends its rendered units. */
private fun renderVarspec(
    op: Operator,
    varspec: Varspec,
    value: Any?,
    units: MutableList<String>,
) {
    when (val normalized = normalizeValue(value)) {
        null -> return
        is Map<*, *> -> renderMap(op, varspec, normalized, units)
        is Iterable<*> -> renderList(op, varspec, normalized, units)
        else -> units.add(renderScalar(op, varspec, normalized.toString()))
    }
}

/** Normalizes object and primitive arrays to lists so they take the list path rather than `toString()`. */
private fun normalizeValue(value: Any?): Any? =
    when (value) {
        is Array<*> -> value.toList()
        is IntArray -> value.toList()
        is LongArray -> value.toList()
        is ShortArray -> value.toList()
        is ByteArray -> value.toList()
        is DoubleArray -> value.toList()
        is FloatArray -> value.toList()
        is BooleanArray -> value.toList()
        is CharArray -> value.toList()
        else -> value
    }

/** Renders a list value (empty list is undefined). */
private fun renderList(
    op: Operator,
    varspec: Varspec,
    value: Iterable<*>,
    units: MutableList<String>,
) {
    val members = value.mapNotNull { member -> member?.toString() }
    if (members.isEmpty()) return
    if (varspec.explode) {
        members.forEach { member -> units.add(renderMember(op, varspec.name, member)) }
    } else {
        val joined = members.joinToString(",") { member -> pctEncode(member, op.allowReserved) }
        units.add(if (op.named) named(op, varspec.name, joined.isEmpty(), joined) else joined)
    }
}

/** Renders an associative-array value (empty map is undefined). */
private fun renderMap(
    op: Operator,
    varspec: Varspec,
    value: Map<*, *>,
    units: MutableList<String>,
) {
    val pairs = value.entries.map { (k, v) -> k.toString() to (v?.toString() ?: "") }
    if (pairs.isEmpty()) return
    if (varspec.explode) {
        pairs.forEach { (k, v) -> units.add(renderMapPair(op, k, v)) }
    } else {
        val joined = pairs.flatMap { (k, v) -> listOf(k, v) }.joinToString(",") { pctEncode(it, op.allowReserved) }
        units.add(if (op.named) "${varspec.name}=$joined" else joined)
    }
}

/** Renders a scalar, applying a `:n` prefix (by code points) before encoding. */
private fun renderScalar(
    op: Operator,
    varspec: Varspec,
    raw: String,
): String {
    val value = if (varspec.prefix != null) raw.takeCodePoints(varspec.prefix) else raw
    val encoded = pctEncode(value, op.allowReserved)
    return if (op.named) named(op, varspec.name, value.isEmpty(), encoded) else encoded
}

/** Renders one exploded list member as a plain or `name=member` unit. */
private fun renderMember(
    op: Operator,
    name: String,
    member: String,
): String {
    val encoded = pctEncode(member, op.allowReserved)
    return if (op.named) named(op, name, member.isEmpty(), encoded) else encoded
}

/** Renders one exploded map entry as a `key=value` unit (`key` acts as the name). */
private fun renderMapPair(
    op: Operator,
    key: String,
    value: String,
): String {
    val keyEncoded = pctEncode(key, op.allowReserved)
    if (value.isEmpty()) return if (op.named) keyEncoded + op.ifEmpty else "$keyEncoded="
    return "$keyEncoded=${pctEncode(value, op.allowReserved)}"
}

/** Assembles a `name=value` unit, collapsing an empty value to `name` + the operator's `ifEmpty`. */
private fun named(
    op: Operator,
    name: String,
    empty: Boolean,
    encoded: String,
): String = if (empty) name + op.ifEmpty else "$name=$encoded"
