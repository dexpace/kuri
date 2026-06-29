#!/usr/bin/env python3
# Copyright (c) 2026 dexpace and Omar Aljarrah
# SPDX-License-Identifier: MIT
r"""Generate kuri's IDNA conformance test fixture and tracked known-failures set.

Offline build tool. It reads the official WPT IDNA corpora shipped in the ada
reference checkout:

    .claude/references/ada/tests/wpt/IdnaTestV2.json
    .claude/references/ada/tests/wpt/toascii.json

and emits two multiplatform-safe Kotlin test data files:

    kuri/src/commonTest/.../idna/IdnaConformanceData.kt           (every case)
    kuri/src/commonTest/.../idna/IdnaConformanceKnownFailures.kt  (tracked set)

Case semantics (matching the corpus generation flags `--exclude-std3
--exclude-bidi`): each entry carries an `input` and an `output`. A non-empty
`output` is the required ToASCII result; an `output` that is `null` or `""`
means the input MUST be rejected. The fixture models this as
`IdnaCase(input, expected)` where `expected == null` denotes a required failure.

Known failures
--------------
kuri's `Idna.domainToAscii` implements the UTS-46 map / Punycode / re-assemble
pipeline but DEFERS three post-mapping steps (NFC normalization, ContextJ
`CheckJoiners`, and the leading-combining-mark validity rule) and bundles the
Unicode 15.1.0 mapping table, whereas this corpus follows Unicode 16.0. To keep
the conformance test honest and ratcheting, this tool runs a faithful Python
port of the *current* `Idna`/`Punycode`/`IdnaMappingTable` behaviour (reading
the very same `IdnaMappingTableData.kt` blob the runtime uses), collects the
exact set of inputs that currently fail, and emits it as
`IDNA_KNOWN_FAILURES`. Every failure is attributed to one of the deferred
steps or to the table version skew -- none stem from a Punycode/mapping bug
(verified: zero wrong-output mismatches). Re-running this tool after closing a
gap shrinks the set; the test asserts the set equals the live failing set, so
a fixed gap breaks the build until the baseline is regenerated.
"""

import json
import os
import re
import sys
import unicodedata
import bisect

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
WPT_DIR = os.path.join(REPO_ROOT, ".claude", "references", "ada", "tests", "wpt")
IDNA_V2 = os.path.join(WPT_DIR, "IdnaTestV2.json")
TOASCII = os.path.join(WPT_DIR, "toascii.json")
TABLE_KT = os.path.join(
    REPO_ROOT, "kuri", "src", "commonMain", "kotlin", "org", "dexpace",
    "kuri", "idna", "IdnaMappingTableData.kt",
)
TEST_DIR = os.path.join(
    REPO_ROOT, "kuri", "src", "commonTest", "kotlin", "org", "dexpace",
    "kuri", "idna",
)
FIXTURE_KT = os.path.join(TEST_DIR, "IdnaConformanceData.kt")
KNOWN_KT = os.path.join(TEST_DIR, "IdnaConformanceKnownFailures.kt")

HEADER = (
    "/*\n"
    " * Copyright (c) 2026 dexpace and Omar Aljarrah\n"
    " * SPDX-License-Identifier: MIT\n"
    " */\n"
)
PACKAGE = "package org.dexpace.kuri.idna"
CHUNK = 320  # cases/entries per generated function (well under the 64 KB method limit)

# --------------------------------------------------------------------------
# Faithful port of the runtime mapping table (IdnaMappingTable.kt).
# --------------------------------------------------------------------------


def _decode_kotlin_literal(raw):
    out, i = [], 0
    while i < len(raw):
        c = raw[i]
        if c != "\\":
            out.append(c)
            i += 1
            continue
        nxt = raw[i + 1]
        if nxt == "u":
            out.append(chr(int(raw[i + 2:i + 6], 16)))
            i += 6
        else:
            out.append({"n": "\n", "t": "\t", "r": "\r"}.get(nxt, nxt))
            i += 2
    return "".join(out)


def _recombine_surrogates(text):
    """Fold UTF-16 surrogate pairs in [text] back into single Unicode scalars.

    A Kotlin string literal stores a supplementary mapped replacement (e.g. an Adlam case fold)
    as a `\\ud83a\\udd37` pair, which `_decode_kotlin_literal` yields as two lone-surrogate code
    units. Iterating those separately would mis-encode the label; the runtime combines the pair
    via `codePointsOf`, so the port must too. Done on the fully assembled blob so a pair split
    across a chunked `"a" + "b"` literal boundary still recombines.
    """
    out, i = [], 0
    while i < len(text):
        hi = ord(text[i])
        lo = ord(text[i + 1]) if i + 1 < len(text) else None
        if 0xD800 <= hi <= 0xDBFF and lo is not None and 0xDC00 <= lo <= 0xDFFF:
            out.append(chr(0x10000 + ((hi - 0xD800) << 10) + (lo - 0xDC00)))
            i += 2
        else:
            out.append(text[i])
            i += 1
    return "".join(out)


def load_mapping_table():
    text = open(TABLE_KT, encoding="utf-8").read()
    body = re.search(r"listOf\((.*)\)", text, re.S).group(1)
    literals = re.findall(r'"((?:[^"\\]|\\.)*)"', body, re.S)
    blob = _recombine_surrogates("".join(_decode_kotlin_literal(lit) for lit in literals))
    starts, kinds, payloads = [], [], []
    for record in blob.split("\n"):
        sep = record.index(" ")
        starts.append(int(record[:sep], 16))
        kinds.append(record[sep + 1])
        payloads.append(record[sep + 2:])
    assert starts[0] == 0, "mapping table must start at U+0000"
    return starts, kinds, payloads


STARTS, KINDS, PAYLOADS = load_mapping_table()


def _range_of(cp):
    return bisect.bisect_right(STARTS, cp) - 1


def map_kind(cp):
    return KINDS[_range_of(cp)]


def map_payload(cp):
    return PAYLOADS[_range_of(cp)]

# --------------------------------------------------------------------------
# Faithful port of Punycode.kt (RFC 3492).
# --------------------------------------------------------------------------

BASE, TMIN, TMAX, SKEW, DAMP = 36, 1, 26, 38, 700
INITIAL_BIAS, INITIAL_N = 72, 0x80
LETTER_DIGITS, DELIM, MAX_CP = 26, "-", 0x10FFFF
MAX_K = BASE * 64
ADAPT_THRESHOLD = ((BASE - TMIN) * TMAX) // 2
INT_MAX = 0x7FFFFFFF


def _threshold(k, bias):
    if k <= bias:
        return TMIN
    if k >= bias + TMAX:
        return TMAX
    return k - bias


def _digit(ch):
    o = ord(ch)
    if ord("a") <= o <= ord("z"):
        return o - ord("a")
    if ord("A") <= o <= ord("Z"):
        return o - ord("A")
    if ord("0") <= o <= ord("9"):
        return o - ord("0") + LETTER_DIGITS
    return -1


def _to_digit(d):
    return chr(ord("a") + d) if d < LETTER_DIGITS else chr(ord("0") + d - LETTER_DIGITS)


def _adapt(delta, points, first):
    scaled = delta // DAMP if first else delta // 2
    scaled += scaled // points
    k = 0
    while scaled > ADAPT_THRESHOLD and k < MAX_K:
        scaled //= (BASE - TMIN)
        k += BASE
    return k + ((BASE - TMIN + 1) * scaled) // (scaled + SKEW)


def _decode_integer(s, start, bias, acc):
    i, w, pos, k = acc, 1, start, BASE
    ok, done = True, False
    while k <= MAX_K and ok and not done:
        digit = _digit(s[pos]) if pos < len(s) else -1
        t = _threshold(k, bias)
        nxt = i + digit * w if digit >= 0 else INT_MAX + 1
        if digit < 0 or nxt > INT_MAX:
            ok = False
        elif digit < t:
            i, pos, done = nxt, pos + 1, True
        elif w * (BASE - t) > INT_MAX:
            ok = False
        else:
            i, pos, w, k = nxt, pos + 1, w * (BASE - t), k + BASE
    return (i, pos) if ok and done else None


def punycode_decode(s):
    last = s.rfind(DELIM)
    if last < 0:
        basics, pos = [], 0
    else:
        basics = []
        for idx in range(last):
            if ord(s[idx]) >= INITIAL_N:
                return None
            basics.append(ord(s[idx]))
        pos = last + 1
    cps = list(basics)
    n, i, bias, ok = INITIAL_N, 0, INITIAL_BIAS, True
    while pos < len(s) and ok:
        old = i
        step = _decode_integer(s, pos, bias, i)
        if step is None:
            ok = False
        else:
            i, pos = step
            out_len = len(cps) + 1
            bias = _adapt(i - old, out_len, old == 0)
            nxt = n + i // out_len
            # Reject out-of-range and lone-surrogate scalars (IgnoreInvalidPunycode = false),
            # matching the runtime decoder and the okhttp reference.
            ok = nxt <= MAX_CP and not (0xD800 <= nxt <= 0xDFFF)
            if ok:
                cps.insert(i % out_len, nxt)
                n = nxt
                i = i % out_len + 1
    return "".join(chr(c) for c in cps) if ok else None


def punycode_encode(s):
    cps = [ord(c) for c in s]
    if not any(c >= INITIAL_N for c in cps):
        return s
    out, basic = [], 0
    for c in cps:
        if c < INITIAL_N:
            out.append(chr(c))
            basic += 1
    if basic:
        out.append(DELIM)
    n, delta, bias, handled, ok = INITIAL_N, 0, INITIAL_BIAS, basic, True
    while handled < len(cps) and ok:
        m = min(c for c in cps if c >= n)
        delta += (m - n) * (handled + 1)
        ok = delta <= INT_MAX
        if not ok:
            break
        n = m
        for c in cps:
            if c < n:
                if delta == INT_MAX:
                    ok = False
                    break
                delta += 1
            elif c == n:
                q, term, k = delta, False, BASE
                while k <= MAX_K:
                    t = _threshold(k, bias)
                    if q < t:
                        term = True
                        break
                    out.append(_to_digit(t + (q - t) % (BASE - t)))
                    q = (q - t) // (BASE - t)
                    k += BASE
                assert term, "generalized integer did not terminate"
                out.append(_to_digit(q))
                bias = _adapt(delta, handled + 1, handled == basic)
                delta, handled = 0, handled + 1
        delta += 1
        n += 1
    return "".join(out) if ok else None

# --------------------------------------------------------------------------
# Faithful port of Idna.kt (current behaviour: map / split / decode / validate
# / re-encode -- NO NFC, NO CheckJoiners, NO leading-combining-mark, NO bidi).
# --------------------------------------------------------------------------

ACE = "xn--"


def _map_all(domain):
    out = []
    for ch in domain:
        kind = map_kind(ord(ch))
        if kind in ("V", "Y"):
            out.append(ch)
        elif kind == "M":
            out.append(map_payload(ord(ch)))
        elif kind == "D":
            return None
        # "I" -> ignored / dropped
    return "".join(out)


def _valid_cp(cp):
    return map_kind(cp) in ("V", "Y")


def _process_label(label):
    decoded = punycode_decode(label[len(ACE):]) if label.startswith(ACE) else label
    if decoded is None:
        return None
    if not all(_valid_cp(ord(c)) for c in decoded):
        return None
    if all(ord(c) < INITIAL_N for c in decoded):
        return decoded
    enc = punycode_encode(decoded)
    return None if enc is None else ACE + enc


def domain_to_ascii(domain, with_nfc=False):
    mapped = _map_all(domain)
    if mapped is None:
        return None
    if with_nfc:
        mapped = unicodedata.normalize("NFC", mapped)
    out = []
    for label in mapped.split("."):
        processed = _process_label(label)
        if processed is None:
            return None
        out.append(processed)
    return ".".join(out)

# --------------------------------------------------------------------------
# Corpus loading + case modelling.
# --------------------------------------------------------------------------


def load_cases():
    cases, seen = [], set()
    for path in (IDNA_V2, TOASCII):
        for entry in json.load(open(path, encoding="utf-8")):
            if not isinstance(entry, dict) or "input" not in entry:
                continue
            output = entry.get("output")
            expected = output if (output is not None and output != "") else None
            key = (entry["input"], expected)
            if key in seen:
                continue
            seen.add(key)
            cases.append((entry["input"], expected))
    return cases


def case_passes(inp, expected):
    actual = domain_to_ascii(inp)
    return actual is None if expected is None else actual == expected


def classify(inp, expected):
    if domain_to_ascii(inp, with_nfc=True) == expected or (
        expected is None and domain_to_ascii(inp, with_nfc=True) is None
    ):
        return "NFC"
    labels = _decoded_labels(inp)
    if "‌" in inp or "‍" in inp or any(
        "‌" in lab or "‍" in lab for lab in labels
    ):
        return "CONTEXTJ"
    for lab in labels:
        if lab and not lab.startswith(ACE) and unicodedata.category(lab[0]) in ("Mn", "Mc", "Me"):
            return "LEADING_COMBINING_MARK"
    if inp == "":
        return "EMPTY_LABEL"
    return "UNICODE_16_SKEW"


def _decoded_labels(domain):
    mapped = _map_all(domain)
    if mapped is None:
        return []
    mapped = unicodedata.normalize("NFC", mapped)
    labels = []
    for lab in mapped.split("."):
        if lab.startswith(ACE):
            dec = punycode_decode(lab[len(ACE):])
            labels.append(dec if dec is not None else lab)
        else:
            labels.append(lab)
    return labels

# --------------------------------------------------------------------------
# Kotlin emission.
# --------------------------------------------------------------------------


MAX_COLS = 120


def escape_tokens(value):
    """Escape [value] into ASCII-only Kotlin tokens, none of which may be split across lines."""
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


def literal_lines(value, indent, suffix):
    """Emit [value] as one or more indented Kotlin string lines, each within [MAX_COLS] columns.

    A literal too long for one line becomes a `"a" + "b"` concatenation, never splitting an escape.
    """
    pad = " " * indent
    one = pad + kotlin_string(value) + suffix
    if len(one) <= MAX_COLS:
        return [one]
    cont = " " * (indent + 4)  # ktlint indents a wrapped binary-expression operand by 4
    budget = MAX_COLS - (indent + 4) - len(' +')  # tightest line is a continuation with ' +'
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
        prefix = pad if index == 0 else cont
        tail = " +" if index < len(segments) - 1 else suffix
        lines.append(prefix + '"' + segment + '"' + tail)
    return lines


def case_lines(inp, expected):
    """Emit one [IdnaCase] entry, wrapping onto several lines only when it would exceed [MAX_COLS]."""
    exp = "null" if expected is None else kotlin_string(expected)
    one = "            IdnaCase(%s, %s)," % (kotlin_string(inp), exp)
    if len(one) <= MAX_COLS:
        return [one]
    lines = ["            IdnaCase("]
    lines += literal_lines(inp, 16, ",")
    lines += ["                null,"] if expected is None else literal_lines(expected, 16, ",")
    lines.append("            ),")
    return lines


def chunked(seq, size):
    for start in range(0, len(seq), size):
        yield seq[start:start + size]


def file_suppress(rules):
    """A `@file:Suppress` block for [rules], justified: these files are bulk generated data, not logic.

    The corpus is chunked into one builder per slice so no single method exceeds the 64 KB JVM
    constant-pool limit; that necessarily trips detekt's source-length heuristics (LongMethod,
    LargeClass) and the filename-vs-declaration rule. Suppressing them here is the documented,
    deterministic alternative to hand-editing a generated file.
    """
    quoted = ", ".join('"%s"' % rule for rule in rules)
    return [
        "// Generated bulk data, not hand-written logic: the chunked builders intentionally exceed",
        "// detekt's method/class-size heuristics to stay within the 64 KB JVM method limit.",
        "@file:Suppress(%s)" % quoted,
        "",
    ]


def emit_fixture(cases):
    parts = list(chunked(cases, CHUNK))
    lines = [HEADER.rstrip("\n"), ""]
    lines += file_suppress(("LongMethod", "LargeClass", "MatchingDeclarationName"))
    lines += [PACKAGE, ""]
    lines.append("/** One WPT IDNA conformance case: [expected] is `null` when [input] must be rejected. */")
    lines.append("internal data class IdnaCase(")
    lines.append("    val input: String,")
    lines.append("    val expected: String?,")
    lines.append(")")
    lines.append("")
    lines.append("// Generated by tools/idna/generate_conformance_fixture.py from the WPT corpora.")
    lines.append("// Chunked into small builders so no single method exceeds the 64 KB JVM limit.")
    lines.append("private object IdnaConformanceCaseData {")
    sum_expr = " +\n            ".join("part%d()" % i for i in range(len(parts)))
    lines.append("    fun all(): List<IdnaCase> =")
    lines.append("        " + sum_expr)
    for index, part in enumerate(parts):
        lines.append("")
        lines.append("    private fun part%d(): List<IdnaCase> =" % index)
        lines.append("        listOf(")
        for inp, expected in part:
            lines.extend(case_lines(inp, expected))
        lines.append("        )")
    lines.append("}")
    lines.append("")
    lines.append("/** Every WPT IDNA ToASCII case (IdnaTestV2 + toascii), de-duplicated by (input, expected). */")
    lines.append("internal val IDNA_CONFORMANCE_CASES: List<IdnaCase> = IdnaConformanceCaseData.all()")
    return "\n".join(lines) + "\n"


def emit_known_failures(ordered_inputs, catcount):
    parts = list(chunked(ordered_inputs, CHUNK))
    lines = [HEADER.rstrip("\n"), ""]
    lines += file_suppress(("LongMethod", "LargeClass"))
    lines += [PACKAGE, ""]
    lines.append("/**")
    lines.append(" * Inputs that `Idna.domainToAscii` currently fails the WPT corpora on, tracked so the")
    lines.append(" * conformance test ratchets: the live failing set must equal this set exactly, so closing")
    lines.append(" * a gap (or a regression) breaks the build until this baseline is regenerated.")
    lines.append(" *")
    lines.append(" * None are Punycode/mapping bugs. Each is attributable to a deferred UTS-46 step or to the")
    lines.append(" * bundled Unicode 15.1.0 mapping table trailing the corpus's Unicode 16.0 semantics:")
    for cat in ("CONTEXTJ", "LEADING_COMBINING_MARK", "NFC", "UNICODE_16_SKEW", "EMPTY_LABEL"):
        if catcount.get(cat):
            lines.append(" *  - %s: %d" % (cat, catcount[cat]))
    lines.append(" *")
    lines.append(" * GENERATED by tools/idna/generate_conformance_fixture.py; do not edit by hand.")
    lines.append(" */")
    lines.append("private object IdnaKnownFailureData {")
    sum_expr = " +\n            ".join("part%d()" % i for i in range(len(parts)))
    lines.append("    fun all(): Set<String> =")
    lines.append("        " + sum_expr)
    for index, part in enumerate(parts):
        lines.append("")
        lines.append("    private fun part%d(): Set<String> =" % index)
        lines.append("        setOf(")
        for inp in part:
            lines.extend(literal_lines(inp, 12, ","))
        lines.append("        )")
    lines.append("}")
    lines.append("")
    lines.append("/** Tracked baseline of currently-failing conformance inputs (see [IdnaKnownFailureData]). */")
    lines.append("internal val IDNA_KNOWN_FAILURES: Set<String> = IdnaKnownFailureData.all()")
    return "\n".join(lines) + "\n"

# --------------------------------------------------------------------------
# Driver.
# --------------------------------------------------------------------------

CATEGORY_ORDER = ("CONTEXTJ", "LEADING_COMBINING_MARK", "NFC", "UNICODE_16_SKEW", "EMPTY_LABEL")


def main():
    cases = load_cases()
    failing, categories = [], {}
    # A genuine Punycode/mapping bug would surface as a *wrong* ToASCII value that no deferred
    # step (NFC/ContextJ/combining-mark) could explain. Those steps legitimately suppress or
    # reshape output, so the bug guard ignores them and fires only on the data-only categories.
    deferred = ("NFC", "CONTEXTJ", "LEADING_COMBINING_MARK")
    bug_suspects = []
    for inp, expected in cases:
        if case_passes(inp, expected):
            continue
        category = classify(inp, expected)
        actual = domain_to_ascii(inp)
        if category not in deferred and expected is not None and actual is not None and actual != expected:
            bug_suspects.append((inp, expected, actual))
        failing.append(inp)
        categories[inp] = category
    assert not bug_suspects, "wrong-output failures outside deferred steps (real bugs): %r" % bug_suspects[:5]

    catcount = {cat: 0 for cat in CATEGORY_ORDER}
    for inp in failing:
        catcount[categories[inp]] += 1
    ordered = sorted(failing, key=lambda x: (CATEGORY_ORDER.index(categories[x]), failing.index(x)))

    os.makedirs(TEST_DIR, exist_ok=True)
    open(FIXTURE_KT, "w", encoding="utf-8").write(emit_fixture(cases))
    open(KNOWN_KT, "w", encoding="utf-8").write(emit_known_failures(ordered, catcount))

    passing = len(cases) - len(failing)
    sys.stderr.write("cases=%d pass=%d (%.1f%%) fail=%d\n" % (
        len(cases), passing, 100.0 * passing / len(cases), len(failing)))
    sys.stderr.write("known-failure categories: %s\n" % catcount)
    sys.stderr.write("wrote %s\n" % FIXTURE_KT)
    sys.stderr.write("wrote %s\n" % KNOWN_KT)


if __name__ == "__main__":
    main()
