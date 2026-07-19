/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
package org.dexpace.kuri.bind.internal

import org.dexpace.kuri.bind.KuriBindException

/** A token of a parsed [PathTemplate]. */
internal sealed interface PathToken {
    data class Literal(
        val raw: String,
    ) : PathToken

    data class Hole(
        val name: String,
        val catchAll: Boolean,
    ) : PathToken
}

/** A parsed Go-style path template: literals interleaved with `{name}` / `{name...}` holes. */
internal class PathTemplate private constructor(
    val tokens: List<PathToken>,
    val holes: List<PathToken.Hole>,
) {
    companion object {
        /** Length of the catch-all suffix `...` appended inside a hole. */
        private const val CATCH_ALL_SUFFIX_LEN = 3

        fun parse(template: String): PathTemplate {
            if (template.isEmpty()) throw KuriBindException("path template must not be empty")
            val tokens = ArrayList<PathToken>()
            val holes = ArrayList<PathToken.Hole>()
            val literal = StringBuilder()
            var i = 0
            while (i < template.length) {
                val c = template[i]
                if (c == '{') {
                    if (literal.isNotEmpty()) {
                        tokens.add(PathToken.Literal(literal.toString()))
                        literal.setLength(0)
                    }
                    val close = template.indexOf('}', i)
                    requireBalancedOpen(close, template)
                    val hole = parseHole(template.substring(i + 1, close), template)
                    requireValidHoleInsertion(hole, holes, template)
                    tokens.add(hole)
                    holes.add(hole)
                    i = close + 1
                } else {
                    if (c == '}') throw KuriBindException("unbalanced '}' in template: $template")
                    literal.append(c)
                    i++
                }
            }
            if (literal.isNotEmpty()) tokens.add(PathToken.Literal(literal.toString()))
            requireCatchAllIsFinal(holes, tokens, template)
            requireHolesAtSegmentBoundaries(tokens, template)
            check(holes.all { it.name.isNotEmpty() }) { "hole names validated above" }
            return PathTemplate(tokens, holes)
        }

        private fun parseHole(
            body: String,
            template: String,
        ): PathToken.Hole {
            val catchAll = body.endsWith("...")
            val name = if (catchAll) body.dropLast(CATCH_ALL_SUFFIX_LEN) else body
            if (name.isEmpty()) throw KuriBindException("empty template hole name: $template")
            if (name.contains('{') || name.contains('}')) {
                throw KuriBindException("nested brace in template hole '$name': $template")
            }
            return PathToken.Hole(name, catchAll)
        }

        /** Throws [KuriBindException] when no closing `}` was found after an opening `{`. */
        private fun requireBalancedOpen(
            close: Int,
            template: String,
        ) {
            if (close < 0) throw KuriBindException("unbalanced '{' in template: $template")
        }

        /**
         * Validates that [hole] can be appended to [holes] without creating a duplicate name or
         * inserting content after a catch-all; throws [KuriBindException] on either violation.
         */
        private fun requireValidHoleInsertion(
            hole: PathToken.Hole,
            holes: List<PathToken.Hole>,
            template: String,
        ) {
            if (holes.any { it.name == hole.name }) {
                throw KuriBindException("duplicate template hole '${hole.name}': $template")
            }
            if (holes.isNotEmpty() && holes.last().catchAll) {
                val lastName = holes.last().name
                throw KuriBindException("catch-all '{$lastName...}' must be the final hole: $template")
            }
        }

        /**
         * A catch-all hole must be the final token; a trailing literal (or any token) after it is
         * rejected — complements [requireValidHoleInsertion], which only guards a following hole.
         */
        private fun requireCatchAllIsFinal(
            holes: List<PathToken.Hole>,
            tokens: List<PathToken>,
            template: String,
        ) {
            val last = holes.lastOrNull() ?: return
            if (last.catchAll && tokens.lastOrNull() != last) {
                throw KuriBindException("catch-all '{${last.name}...}' must be the final token: $template")
            }
        }

        /**
         * A hole must occupy a whole path segment, matching `net/http.ServeMux`: literal text sharing a
         * segment with a hole (`{id}.json`, `v{version}`) is rejected rather than silently re-segmented
         * when the path is composed (issue #82). A hole is exempt from this check only at a true template
         * boundary (start/end of string, i.e. no neighboring token at all) — any other neighbor, including
         * another hole with nothing between them (`{a}{b}`), is a violation unless it's a [PathToken.Literal]
         * carrying the `/` at the shared edge. The composer always emits one full segment per [PathToken.Hole]
         * regardless of adjacency, so two adjacent holes can never merge into the single shared segment their
         * spelling implies.
         */
        private fun requireHolesAtSegmentBoundaries(
            tokens: List<PathToken>,
            template: String,
        ) {
            for (i in tokens.indices) {
                val hole = tokens[i] as? PathToken.Hole ?: continue
                if (!isBoundedBefore(tokens, i) || !isBoundedAfter(tokens, i)) {
                    throw KuriBindException(
                        "template hole '{${hole.name}}' must occupy a whole '/'-delimited path segment: $template",
                    )
                }
            }
        }

        /** True when the hole at [index] has no preceding token, or a literal predecessor ending in `/`. */
        private fun isBoundedBefore(
            tokens: List<PathToken>,
            index: Int,
        ): Boolean {
            val before = tokens.getOrNull(index - 1) ?: return true
            return before is PathToken.Literal && before.raw.endsWith('/')
        }

        /** True when the hole at [index] has no following token, or a literal successor starting with `/`. */
        private fun isBoundedAfter(
            tokens: List<PathToken>,
            index: Int,
        ): Boolean {
            val after = tokens.getOrNull(index + 1) ?: return true
            return after is PathToken.Literal && after.raw.startsWith('/')
        }
    }
}
