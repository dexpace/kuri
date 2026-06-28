#!/usr/bin/env python3
# Copyright (c) 2026 dexpace and Omar Aljarrah
# SPDX-License-Identifier: MIT
r"""Generate kuri's compact UTS-46 IDNA mapping table data file.

This is an offline build tool. It parses the Unicode `IdnaMappingTable.txt`
source (Unicode 15.1.0, shipped under okhttp's reference resources) and emits a
multiplatform-safe Kotlin data file at:

    kuri/src/commonMain/kotlin/org/dexpace/kuri/idna/IdnaMappingTableData.kt

The DATA is derived from Unicode's freely usable IdnaMappingTable.txt. The code
here is an original reimplementation; it does not copy okhttp.

UTS-46 parameter set baked in (SPEC §7.4, [HOST-28]):

  * UseSTD3ASCIIRules = false  -> disallowed_STD3_valid  becomes VALID
                                  disallowed_STD3_mapped  becomes MAPPED
  * Transitional_Processing = false -> deviation kept DISTINCT (runtime treats
                                       it as VALID, i.e. the code point is kept).

Runtime statuses collapse to five letters:

    V = VALID       I = IGNORED     D = DISALLOWED
    M = MAPPED (carries a replacement string)         Y = DEVIATION

Encoding (see IdnaMappingTable.kt for the matching decoder)
-----------------------------------------------------------
After canonicalizing each line's status and merging adjacent ranges that share
the same status (and, for MAPPED, the same replacement), the full code-point
space 0..0x10FFFF is covered by a sorted, gap-free list of ranges. Only each
range's inclusive START is stored; a range runs until the next range's start.

Each range becomes one record:

    <startHexUpper><SP><kindLetter>[<replacementChars>]

Records are joined with '\n' (newline never appears inside a record: hex starts
have no spaces, kind letters are A-Z, and no IDNA replacement contains a
control character). The joined blob is sliced into chunks emitted as a
`List<String>`; concatenating the chunks and splitting on '\n' reproduces the
records exactly. Chunks are sliced at whole-character boundaries so that each
emitted string-literal line stays within the 120-column limit (and therefore
also far below the 64 KB Kotlin/JVM string-constant ceiling). The generated
source is pure ASCII: non-ASCII replacement characters are escaped as \uXXXX
(UTF-16 units).
"""

import os
import re
import sys

# UTS-46 / Unicode constants.
MAX_CODE_POINT = 0x10FFFF
HEX_TOKEN = re.compile(r"^[0-9A-Fa-f]+$")

# Status letters used by the runtime decoder.
KIND_VALID = "V"
KIND_IGNORED = "I"
KIND_DISALLOWED = "D"
KIND_MAPPED = "M"
KIND_DEVIATION = "Y"

# Map each IdnaMappingTable.txt status keyword to a runtime kind under the
# UseSTD3ASCIIRules=false, Transitional_Processing=false parameter set.
STATUS_TO_KIND = {
    "valid": KIND_VALID,
    "disallowed_STD3_valid": KIND_VALID,
    "ignored": KIND_IGNORED,
    "disallowed": KIND_DISALLOWED,
    "mapped": KIND_MAPPED,
    "disallowed_STD3_mapped": KIND_MAPPED,
    "deviation": KIND_DEVIATION,
}

# Characters that are safe to emit verbatim into a Kotlin string literal.
SAFE_LITERAL_MIN = 0x20
SAFE_LITERAL_MAX = 0x7E
SPECIAL_LITERAL_CHARS = {'"', "\\", "$"}

# UTF-16 surrogate encoding bounds (code points above the BMP need a surrogate pair).
BMP_MAX = 0xFFFF
SURROGATE_BASE = 0x10000
HIGH_SURROGATE_MIN = 0xD800
LOW_SURROGATE_MIN = 0xDC00
SURROGATE_HIGH_SHIFT = 10
SURROGATE_LOW_MASK = 0x3FF

# Each chunk is emitted as one string-literal line, indented and wrapped in
# quotes with a trailing comma. The escaped content budget keeps that whole
# line within the project's 120-column limit: 120 - 8 (indent) - 2 (quotes)
# - 1 (comma) = 109; 100 leaves slack and never splits a 6-char \uXXXX escape.
ESCAPED_LINE_BUDGET = 100

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SOURCE_TXT = os.path.join(
    REPO_ROOT,
    ".claude", "references", "okhttp", "okhttp-idna-mapping-table",
    "src", "main", "resources", "okhttp3", "internal", "idna",
    "IdnaMappingTable.txt",
)
OUTPUT_KT = os.path.join(
    REPO_ROOT,
    "kuri", "src", "commonMain", "kotlin", "org", "dexpace", "kuri", "idna",
    "IdnaMappingTableData.kt",
)

LICENSE_HEADER = (
    "/*\n"
    " * Copyright (c) 2026 dexpace and Omar Aljarrah\n"
    " * SPDX-License-Identifier: MIT\n"
    " */\n"
)


def parse_line(line):
    """Parse one data line into (start, end, kind, replacement).

    Returns None for blank/comment lines. `replacement` is the mapped string
    (empty unless kind == MAPPED).
    """
    body = line.split("#", 1)[0].strip()
    if not body:
        return None

    fields = [f.strip() for f in body.split(";")]
    code_range = fields[0]
    status = fields[1].split()[0]
    kind = STATUS_TO_KIND.get(status)
    if kind is None:
        raise ValueError("unknown status: %r" % status)

    if ".." in code_range:
        lo_hex, hi_hex = code_range.split("..")
        start, end = int(lo_hex, 16), int(hi_hex, 16)
    else:
        start = end = int(code_range, 16)

    replacement = ""
    if kind == KIND_MAPPED:
        tokens = fields[2].split() if len(fields) > 2 else []
        code_points = [int(t, 16) for t in tokens if HEX_TOKEN.match(t)]
        replacement = "".join(chr(cp) for cp in code_points)

    return start, end, kind, replacement


def load_ranges(path):
    """Load and validate the table as a sorted, gap-free range list."""
    ranges = []
    with open(path, "r", encoding="utf-8") as handle:
        for line in handle:
            parsed = parse_line(line)
            if parsed is not None:
                ranges.append(parsed)

    ranges.sort(key=lambda r: r[0])

    expected = 0
    for start, end, _, _ in ranges:
        if start != expected:
            raise ValueError("gap/overlap at U+%04X (expected U+%04X)" % (start, expected))
        expected = end + 1
    if expected != MAX_CODE_POINT + 1:
        raise ValueError("coverage ends at U+%04X, not U+10FFFF" % (expected - 1))

    return ranges


def merge_adjacent(ranges):
    """Merge neighbouring ranges sharing the same (kind, replacement)."""
    merged = []
    for start, end, kind, replacement in ranges:
        if merged:
            prev_start, prev_end, prev_kind, prev_rep = merged[-1]
            if prev_kind == kind and prev_rep == replacement and prev_end + 1 == start:
                merged[-1] = (prev_start, end, kind, replacement)
                continue
        merged.append((start, end, kind, replacement))
    return merged


def encode_records(ranges):
    """Render merged ranges to the newline-joined record blob."""
    records = []
    for start, _, kind, replacement in ranges:
        records.append("%X %s%s" % (start, kind, replacement))
    return "\n".join(records)


def escape_char(ch):
    """Escape a single character for a Kotlin string literal (pure ASCII).

    Kotlin string literals are UTF-16, so a `\\u` escape carries exactly four hex
    digits. Code points above the BMP are emitted as a surrogate pair of escapes.
    """
    code = ord(ch)
    if SAFE_LITERAL_MIN <= code <= SAFE_LITERAL_MAX and ch not in SPECIAL_LITERAL_CHARS:
        return ch
    if code <= BMP_MAX:
        return "\\u%04x" % code
    offset = code - SURROGATE_BASE
    high = HIGH_SURROGATE_MIN + (offset >> SURROGATE_HIGH_SHIFT)
    low = LOW_SURROGATE_MIN + (offset & SURROGATE_LOW_MASK)
    return "\\u%04x\\u%04x" % (high, low)


def chunk_blob(blob):
    """Slice the blob at whole-character boundaries so each chunk's escaped form fits a line."""
    chunks = []
    current = []
    width = 0
    for ch in blob:
        escaped = escape_char(ch)
        if width + len(escaped) > ESCAPED_LINE_BUDGET and current:
            chunks.append("".join(current))
            current = []
            width = 0
        current.append(escaped)
        width += len(escaped)
    if current:
        chunks.append("".join(current))
    return chunks


def render_kotlin(chunks):
    lines = [
        LICENSE_HEADER,
        "package org.dexpace.kuri.idna",
        "",
        "/**",
        " * Compact, generated UTS-46 IDNA mapping table data (Unicode 15.1.0).",
        " *",
        " * GENERATED by tools/idna/generate_idna_mapping_table.py from Unicode's",
        " * IdnaMappingTable.txt. Do not edit by hand; re-run the generator instead.",
        " *",
        " * The chunks concatenate into a newline-joined list of range records, each",
        " * `<startHex> <kindLetter><replacement?>`. See [IdnaMappingTable] for the",
        " * decoder and encoding contract (SPEC §7.4, [HOST-26]/[HOST-28]).",
        " */",
        "internal val IDNA_MAPPING_TABLE_CHUNKS: List<String> =",
        "    listOf(",
    ]
    for chunk in chunks:
        lines.append('        "%s",' % chunk)
    lines.append("    )")
    lines.append("")
    return "\n".join(lines)


def main():
    ranges = load_ranges(SOURCE_TXT)
    merged = merge_adjacent(ranges)
    blob = encode_records(merged)
    chunks = chunk_blob(blob)

    with open(OUTPUT_KT, "w", encoding="utf-8") as handle:
        handle.write(render_kotlin(chunks))

    blob_bytes = len(blob.encode("utf-8"))
    widest = max(len(c) for c in chunks)
    print("source lines parsed : %d ranges" % len(ranges))
    print("merged records      : %d" % len(merged))
    print("blob chars          : %d" % len(blob))
    print("blob utf-8 bytes    : %d" % blob_bytes)
    print("chunks              : %d (widest escaped line %d <= %d)" % (len(chunks), widest, ESCAPED_LINE_BUDGET))
    print("output              : %s" % OUTPUT_KT)


if __name__ == "__main__":
    sys.exit(main())
