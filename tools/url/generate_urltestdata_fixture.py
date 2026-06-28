#!/usr/bin/env python3
# Copyright (c) 2026 dexpace and Omar Aljarrah
# SPDX-License-Identifier: MIT
r"""Generate kuri's WPT urltestdata conformance fixture for the `Url` profile.

Offline build tool. It reads the official WPT URL corpus shipped in the ada
reference checkout:

    .claude/references/ada/tests/wpt/urltestdata.json

and emits one multiplatform-safe Kotlin test data file:

    kuri/src/commonTest/.../parser/UrlTestData.kt        (every case)

The corpus is a JSON array mixing documentation strings (skipped) with test
objects. Only objects carrying an `input` key are in scope. Each case is either
a required failure (`"failure": true`) or a success carrying the parsed
component fields (`protocol`, `username`, `password`, `hostname`, `port`,
`pathname`, `search`, `hash`). `base` is the optional base URL (string or null)
the input is resolved against.

The fixture models each case as `UrlCase`; `UrlConformanceTest` runs them
through `UrlParser.parse` and ratchets a tracked known-failures baseline. The
known-failures set itself lives in the test (hand-maintained), not here -- this
tool only materializes the corpus. The list builders are chunked so no single
method exceeds the 64 KB JVM constant-pool limit.
"""

import json
import os
import sys

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
CORPUS = os.path.join(
    REPO_ROOT, ".claude", "references", "ada", "tests", "wpt", "urltestdata.json"
)
TEST_DIR = os.path.join(
    REPO_ROOT, "kuri", "src", "commonTest", "kotlin", "org", "dexpace",
    "kuri", "parser",
)
FIXTURE_KT = os.path.join(TEST_DIR, "UrlTestData.kt")

HEADER = (
    "/*\n"
    " * Copyright (c) 2026 dexpace and Omar Aljarrah\n"
    " * SPDX-License-Identifier: MIT\n"
    " */\n"
)
PACKAGE = "package org.dexpace.kuri.parser"
CHUNK = 60          # cases per generated builder (well under the 64 KB method limit)
MAX_COLS = 120

# Expected component fields, in UrlCase declaration order after input/base/failure.
EXPECTED_FIELDS = (
    "protocol", "username", "password", "hostname",
    "port", "pathname", "search", "hash",
)


def escape_tokens(value):
    """Escape [value] into ASCII-only Kotlin string tokens, none splittable across lines."""
    tokens = []
    for ch in value:
        cp = ord(ch)
        if cp > 0xFFFF:
            cp -= 0x10000
            tokens.append("\\u%04x\\u%04x" % (0xD800 + (cp >> 10), 0xDC00 + (cp & 0x3FF)))
        elif ch == '"':
            tokens.append('\\"')
        elif ch == "\\":
            tokens.append("\\\\")
        elif ch == "$":
            tokens.append("\\$")
        elif 0x20 <= cp <= 0x7E:
            tokens.append(ch)
        else:
            tokens.append("\\u%04x" % cp)
    return tokens


def kotlin_string(value):
    return '"' + "".join(escape_tokens(value)) + '"'


def field_lines(name, value, indent):
    """Emit `name = "value",` at [indent], wrapping over-long literals as `"a" +` / `"b",`."""
    pad = " " * indent
    prefix = "%s%s = " % (pad, name)
    one = prefix + kotlin_string(value) + ","
    if len(one) <= MAX_COLS:
        return [one]
    cont = " " * (indent + 4)  # ktlint indents a wrapped binary-expression operand by 4
    # The first line carries `name = ` and is wider than a continuation; budget for the
    # tighter of the two so neither the head nor any `+`-continued operand exceeds MAX_COLS.
    budget = MAX_COLS - max(len(prefix), indent + 4) - len(" +")
    segments, current = [], ""
    for token in escape_tokens(value):
        if current and len('"' + current + token + '"') > budget:
            segments.append(current)
            current = token
        else:
            current += token
    segments.append(current)
    lines = []
    for index, segment in enumerate(segments):
        head = prefix if index == 0 else cont
        tail = " +" if index < len(segments) - 1 else ","
        lines.append(head + '"' + segment + '"' + tail)
    return lines


def case_lines(case):
    """Emit one [UrlCase] as an indented, named-argument constructor call."""
    lines = ["            UrlCase("]
    lines += field_lines("input", case["input"], 16)
    base = case.get("base")
    if base is None:
        lines.append("                base = null,")
    else:
        lines += field_lines("base", base, 16)
    lines.append("                failure = %s," % ("true" if case.get("failure") else "false"))
    for field in EXPECTED_FIELDS:
        lines += field_lines(field, "" if case.get("failure") else case.get(field, ""), 16)
    lines.append("            ),")
    return lines


def chunked(seq, size):
    for start in range(0, len(seq), size):
        yield seq[start:start + size]


def load_cases():
    corpus = json.load(open(CORPUS, encoding="utf-8"))
    return [entry for entry in corpus if isinstance(entry, dict) and "input" in entry]


def emit_data_class():
    return [
        "/**",
        " * One WPT `urltestdata.json` case for the `Url` profile.",
        " *",
        " * A [failure] case asserts the parse must fail; otherwise the expected getter strings",
        " * (`protocol` = scheme + `:`, `hostname` = serialized host, `port` = string with `\"\"`",
        " * for none, `search`/`hash` including their leading `?`/`#`) are compared against the",
        " * parsed [org.dexpace.kuri.parser.ParsedComponents]. [base] is the optional base URL the",
        " * input resolves against (`null` for an absolute parse).",
        " */",
        "internal data class UrlCase(",
        "    val input: String,",
        "    val base: String?,",
        "    val failure: Boolean,",
        "    val protocol: String,",
        "    val username: String,",
        "    val password: String,",
        "    val hostname: String,",
        "    val port: String,",
        "    val pathname: String,",
        "    val search: String,",
        "    val hash: String,",
        ")",
    ]


def emit_fixture(cases):
    parts = list(chunked(cases, CHUNK))
    lines = [HEADER.rstrip("\n"), ""]
    lines += [
        "// Generated bulk data, not hand-written logic: the chunked builders intentionally exceed",
        "// detekt's method/class-size heuristics to stay within the 64 KB JVM method limit.",
        '@file:Suppress("LongMethod", "LargeClass", "MatchingDeclarationName")',
        "",
        PACKAGE,
        "",
    ]
    lines += emit_data_class()
    lines += [
        "",
        "// Generated by tools/url/generate_urltestdata_fixture.py from the WPT urltestdata corpus.",
        "// Chunked into small builders so no single method exceeds the 64 KB JVM limit.",
        "private object UrlConformanceCaseData {",
    ]
    sum_expr = " +\n            ".join("part%d()" % i for i in range(len(parts)))
    lines.append("    fun all(): List<UrlCase> =")
    lines.append("        " + sum_expr)
    for index, part in enumerate(parts):
        lines.append("")
        lines.append("    private fun part%d(): List<UrlCase> =" % index)
        lines.append("        listOf(")
        for case in part:
            lines.extend(case_lines(case))
        lines.append("        )")
    lines.append("}")
    lines.append("")
    lines.append("/** Every in-scope WPT `urltestdata.json` case (objects carrying an `input`). */")
    lines.append("internal val URL_TEST_CASES: List<UrlCase> = UrlConformanceCaseData.all()")
    return "\n".join(lines) + "\n"


def main():
    cases = load_cases()
    os.makedirs(TEST_DIR, exist_ok=True)
    open(FIXTURE_KT, "w", encoding="utf-8").write(emit_fixture(cases))
    failures = sum(1 for c in cases if c.get("failure"))
    sys.stderr.write("cases=%d failure=%d success=%d\n" % (len(cases), failures, len(cases) - failures))
    sys.stderr.write("wrote %s\n" % FIXTURE_KT)


if __name__ == "__main__":
    main()
