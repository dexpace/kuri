#!/usr/bin/env python3
# Copyright (c) 2026 dexpace and Omar Aljarrah
# SPDX-License-Identifier: MIT
r"""Generate kuri's compact UTS-46 label-validity data file.

This is an offline build tool. It parses two Unicode Character Database source
files (Unicode 16.0.0) and emits a multiplatform-safe Kotlin data file at:

    kuri/src/commonMain/kotlin/org/dexpace/kuri/idna/IdnaValidityData.kt

Inputs (freely usable Unicode data; the Kotlin output is an original encoding):

  * DerivedJoiningType.txt -> the Joining_Type of each code point. We keep only
    the four types the RFC 5892 ContextJ rules consult: L (Left_Joining),
    D (Dual_Joining), R (Right_Joining) and T (Transparent). Everything else
    (Non_Joining U, Join_Causing C) is "other" and is omitted.
  * UnicodeData.txt -> two sets of code points:
      - Mark = General_Category in {Mn, Mc, Me}  (leading-combining-mark rule).
      - Virama = Canonical_Combining_Class == 9   (ContextJ Virama check).
    First>/<Last range pairs are expanded so an entire block is covered.

Three datasets are emitted, each a newline-joined blob of inclusive-range
records, chunked into Kotlin string literals (concatenate the chunks, split on
'\n' to recover the records):

  * Mark / Virama record:  <START>-<END>          (uppercase hex, '-' joins).
  * Joining record:        <START>-<END>;<TYPE>   (TYPE one of L D R T).

'-' and ';' never occur inside an uppercase hex token, so the split is exact.
Records are sorted, non-overlapping and adjacency-merged. Blobs are pure ASCII
apart from the record-separating newline, which is escaped as
 so every
emitted literal stays inside the 120-column limit and far under the 64 KB
Kotlin/JVM string-constant ceiling.
"""

import os
import sys

# Unicode / parameter constants.
MAX_CODE_POINT = 0x10FFFF
VIRAMA_CCC = 9
MARK_CATEGORIES = frozenset(("Mn", "Mc", "Me"))
JOINING_TYPES = frozenset(("L", "D", "R", "T"))

# UnicodeData.txt is ';'-delimited; these are the field indices we read.
FIELD_CODE_POINT = 0
FIELD_NAME = 1
FIELD_CATEGORY = 2
FIELD_CCC = 3

# Record / chunk encoding (matched by the IdnaValidity decoder).
RANGE_SEPARATOR = "-"
TYPE_SEPARATOR = ";"
RECORD_SEPARATOR = "\n"
NEWLINE_ESCAPE = "\\u000a"

# 120 - 8 (indent) - 2 (quotes) - 1 (comma) = 109; 100 leaves slack and never
# splits a 6-char escape across two emitted lines.
ESCAPED_LINE_BUDGET = 100

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
UNICODE_DIR = os.path.join(REPO_ROOT, ".claude", "references", "unicode-16.0")
JOINING_TXT_CANDIDATES = (
    os.path.join(UNICODE_DIR, "extracted", "DerivedJoiningType.txt"),
    os.path.join(UNICODE_DIR, "DerivedJoiningType.txt"),
)
UNICODE_DATA_TXT = os.path.join(UNICODE_DIR, "UnicodeData.txt")
OUTPUT_KT = os.path.join(
    REPO_ROOT,
    "kuri", "src", "commonMain", "kotlin", "org", "dexpace", "kuri", "idna",
    "IdnaValidityData.kt",
)

LICENSE_HEADER = (
    "/*\n"
    " * Copyright (c) 2026 dexpace and Omar Aljarrah\n"
    " * SPDX-License-Identifier: MIT\n"
    " */\n"
)


def first_existing(paths):
    """Return the first path that exists, else raise."""
    for path in paths:
        if os.path.exists(path):
            return path
    raise FileNotFoundError("none of these inputs exist: %r" % (paths,))


def parse_code_range(token):
    """Parse a 'XXXX' or 'XXXX..YYYY' code-range token into (start, end)."""
    if ".." in token:
        lo_hex, hi_hex = token.split("..")
        return int(lo_hex, 16), int(hi_hex, 16)
    value = int(token, 16)
    return value, value


def load_joining(path):
    """Load (start, end, type) ranges for Joining_Type in {L, D, R, T}."""
    ranges = []
    with open(path, "r", encoding="utf-8") as handle:
        for line in handle:
            body = line.split("#", 1)[0].strip()
            if not body:
                continue
            token, jtype = (f.strip() for f in body.split(";"))
            if jtype not in JOINING_TYPES:
                continue
            start, end = parse_code_range(token)
            ranges.append((start, end, jtype))
    return ranges


def load_unicode_data(path):
    """Load Mark and Virama (start, end) ranges from UnicodeData.txt.

    Expands <..., First>/<..., Last> entries so the whole enclosed block is
    covered with the First row's category and combining class.
    """
    marks, viramas = [], []
    pending_start = None
    with open(path, "r", encoding="utf-8") as handle:
        for line in handle:
            fields = line.rstrip("\n").split(";")
            code_point = int(fields[FIELD_CODE_POINT], 16)
            name = fields[FIELD_NAME]
            category = fields[FIELD_CATEGORY]
            ccc = int(fields[FIELD_CCC])
            if name.endswith(", First>"):
                pending_start = code_point
                continue
            start = pending_start if name.endswith(", Last>") else code_point
            pending_start = None
            if category in MARK_CATEGORIES:
                marks.append((start, code_point))
            if ccc == VIRAMA_CCC:
                viramas.append((start, code_point))
    return marks, viramas


def merge_set_ranges(ranges):
    """Sort and adjacency-merge plain (start, end) ranges."""
    merged = []
    for start, end in sorted(ranges):
        if merged and start <= merged[-1][1] + 1:
            merged[-1] = (merged[-1][0], max(merged[-1][1], end))
        else:
            merged.append((start, end))
    return merged


def merge_typed_ranges(ranges):
    """Sort and adjacency-merge (start, end, type) ranges of equal type."""
    merged = []
    for start, end, jtype in sorted(ranges):
        if merged and jtype == merged[-1][2] and start <= merged[-1][1] + 1:
            merged[-1] = (merged[-1][0], max(merged[-1][1], end), jtype)
        else:
            merged.append((start, end, jtype))
    return merged


def encode_set(ranges):
    """Render plain ranges to the newline-joined record blob."""
    return RECORD_SEPARATOR.join(
        "%X%s%X" % (start, RANGE_SEPARATOR, end) for start, end in ranges
    )


def encode_typed(ranges):
    """Render typed ranges to the newline-joined record blob."""
    return RECORD_SEPARATOR.join(
        "%X%s%X%s%s" % (start, RANGE_SEPARATOR, end, TYPE_SEPARATOR, jtype)
        for start, end, jtype in ranges
    )


def chunk_blob(blob):
    """Slice the blob so each chunk's escaped form fits the line budget."""
    chunks, current, width = [], [], 0
    for ch in blob:
        escaped = NEWLINE_ESCAPE if ch == RECORD_SEPARATOR else ch
        if width + len(escaped) > ESCAPED_LINE_BUDGET and current:
            chunks.append("".join(current))
            current, width = [], 0
        current.append(escaped)
        width += len(escaped)
    if current:
        chunks.append("".join(current))
    return chunks


def render_property(name, kdoc, chunks):
    """Render one `internal val <name>: List<String>` chunked-literal property."""
    lines = ["/** %s */" % kdoc, "internal val %s: List<String> =" % name, "    listOf("]
    for chunk in chunks:
        lines.append('        "%s",' % chunk)
    lines.append("    )")
    return lines


def render_kotlin(mark_chunks, virama_chunks, joining_chunks):
    """Assemble the full Kotlin source for IdnaValidityData.kt."""
    header = [
        LICENSE_HEADER.rstrip("\n"),
        "",
        "package org.dexpace.kuri.idna",
        "",
        "/*",
        " * Compact, generated label-validity range tables (Unicode 16.0.0).",
        " *",
        " * GENERATED by tools/idna/generate_idna_validity_tables.py from Unicode's",
        " * DerivedJoiningType.txt and UnicodeData.txt. Do not edit by hand; re-run the generator.",
        " *",
        " * Each blob concatenates to newline-joined inclusive-range records decoded by",
        " * [IdnaValidity]: <START>-<END> for the Mark/Virama sets and <START>-<END>;<TYPE>",
        " * for Joining_Type (SPEC 7.4; UTS-46 leading-combining-mark; RFC 5892 A.1/A.2).",
        " */",
    ]
    body = [""]
    body += render_property(
        "MARK_RANGE_CHUNKS",
        "General_Category Mark (Mn/Mc/Me) code-point ranges; the leading-combining-mark rule.",
        mark_chunks,
    )
    body.append("")
    body += render_property(
        "VIRAMA_RANGE_CHUNKS",
        "Canonical_Combining_Class == 9 (Virama) code-point ranges; ContextJ Virama check.",
        virama_chunks,
    )
    body.append("")
    body += render_property(
        "JOINING_TYPE_CHUNKS",
        "Joining_Type ranges (types L/D/R/T only); RFC 5892 ContextJ ZWNJ join context.",
        joining_chunks,
    )
    return "\n".join(header + body) + "\n"


def report(label, ranges, chunks):
    """Print one dataset's range and chunk statistics."""
    widest = max((len(c) for c in chunks), default=0)
    print("%-14s : %4d ranges, %3d chunks (widest %d <= %d)"
          % (label, len(ranges), len(chunks), widest, ESCAPED_LINE_BUDGET))


def main():
    joining_path = first_existing(JOINING_TXT_CANDIDATES)
    joining = merge_typed_ranges(load_joining(joining_path))
    raw_marks, raw_viramas = load_unicode_data(UNICODE_DATA_TXT)
    marks = merge_set_ranges(raw_marks)
    viramas = merge_set_ranges(raw_viramas)

    mark_chunks = chunk_blob(encode_set(marks))
    virama_chunks = chunk_blob(encode_set(viramas))
    joining_chunks = chunk_blob(encode_typed(joining))

    with open(OUTPUT_KT, "w", encoding="utf-8") as handle:
        handle.write(render_kotlin(mark_chunks, virama_chunks, joining_chunks))

    report("Mark", marks, mark_chunks)
    report("Virama", viramas, virama_chunks)
    report("Joining_Type", joining, joining_chunks)
    print("output         : %s" % OUTPUT_KT)


if __name__ == "__main__":
    sys.exit(main())
