#!/usr/bin/env python3
# Copyright (c) 2026 dexpace and Omar Aljarrah
# SPDX-License-Identifier: MIT
r"""Generate kuri's compact NFC (Normalization Form C) table data file.

Offline build tool. It parses two Unicode 16.0 source files:

    .claude/references/unicode-16.0/UnicodeData.txt
    .claude/references/unicode-16.0/CompositionExclusions.txt

and emits a multiplatform-safe Kotlin data file at:

    kuri/src/commonMain/kotlin/org/dexpace/kuri/idna/NfcData.kt

The DATA is derived from the freely usable Unicode Character Database. The code
here is an original reimplementation following Unicode Standard Annex #15.

Three tables are emitted (all pure-ASCII, chunked under the 64 KB Kotlin/JVM
string-constant ceiling; see Normalizer.kt for the matching decoder):

  * Canonical Combining Class map  -- non-zero CCC only (field 3).
  * Canonical Decomposition map    -- field 5, canonical entries only (rows
    whose mapping has NO `<...>` compatibility tag). First/Last range rows
    carry no decomposition and are skipped.
  * Canonical Composition map      -- pair(starter, combining) -> composed,
    EXCLUDING composition-exclusion targets (CompositionExclusions.txt),
    singletons, and non-starter decompositions (UAX #15 primary composites).

Record encodings (joined into one blob, then sliced into <=100-char ASCII
chunks at character boundaries):

    CCC     : "<cpHex>:<cccHex>"        joined by ';'
    DECOMP  : "<cpHex>=<d1Hex> <d2Hex>" joined by ';'
    COMP    : "<aHex>,<bHex>=<cpHex>"   joined by ';'

All hex is lowercase without a `0x` prefix. None of ';', ':', '=', ',' or ' '
appears inside a hex token, so the blob round-trips by simple splitting.
"""

import os
import sys

# Field indices in UnicodeData.txt (semicolon-separated).
FIELD_CODE_POINT = 0
FIELD_NAME = 1
FIELD_COMBINING_CLASS = 3
FIELD_DECOMPOSITION = 5

RADIX_HEX = 16
CANONICAL_PAIR_LENGTH = 2

# Each chunk is emitted as one quoted string-literal line. 120 - 8 (indent)
# - 2 (quotes) - 1 (comma) = 109; 100 leaves slack. The data is pure ASCII, so
# no character ever expands, and chunks never split a token in a harmful way
# (the decoder concatenates chunks before splitting).
CHUNK_BUDGET = 100

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
UNICODE_DIR = os.path.join(REPO_ROOT, ".claude", "references", "unicode-16.0")
UNICODE_DATA = os.path.join(UNICODE_DIR, "UnicodeData.txt")
COMPOSITION_EXCLUSIONS = os.path.join(UNICODE_DIR, "CompositionExclusions.txt")
OUTPUT_KT = os.path.join(
    REPO_ROOT, "kuri", "src", "commonMain", "kotlin", "org", "dexpace",
    "kuri", "idna", "NfcData.kt",
)

LICENSE_HEADER = (
    "/*\n"
    " * Copyright (c) 2026 dexpace and Omar Aljarrah\n"
    " * SPDX-License-Identifier: MIT\n"
    " */\n"
)


def load_unicode_data(path):
    """Parse UnicodeData.txt into (ccc, decomposition) maps.

    `ccc` keeps only non-zero combining classes. `decomposition` keeps only
    canonical mappings (no `<...>` compatibility tag). First/Last range rows
    carry neither, so they fall through untouched.
    """
    ccc = {}
    decomposition = {}
    with open(path, "r", encoding="utf-8") as handle:
        for line in handle:
            fields = line.rstrip("\n").split(";")
            if len(fields) <= FIELD_DECOMPOSITION:
                continue
            code_point = int(fields[FIELD_CODE_POINT], RADIX_HEX)
            combining = int(fields[FIELD_COMBINING_CLASS])
            if combining:
                ccc[code_point] = combining
            mapping = fields[FIELD_DECOMPOSITION].strip()
            if mapping and not mapping.startswith("<"):
                decomposition[code_point] = [int(t, RADIX_HEX) for t in mapping.split()]
    return ccc, decomposition


def load_exclusions(path):
    """Parse the explicit composition-exclusion set from CompositionExclusions.txt."""
    exclusions = set()
    with open(path, "r", encoding="utf-8") as handle:
        for line in handle:
            body = line.split("#", 1)[0].strip()
            if body:
                exclusions.add(int(body, RADIX_HEX))
    return exclusions


def build_composition(ccc, decomposition, exclusions):
    """Derive UAX #15 primary composites: pair(starter, combining) -> composed.

    A canonical pair `cp -> [a, b]` yields a composition unless `cp` is an
    explicit exclusion, `cp` is a singleton (length != 2), or `a` is a
    non-starter (CCC != 0, i.e. a non-starter decomposition).
    """
    composition = {}
    for code_point, mapping in decomposition.items():
        if len(mapping) != CANONICAL_PAIR_LENGTH:
            continue
        starter, combining = mapping
        if ccc.get(starter, 0) != 0 or code_point in exclusions:
            continue
        composition[(starter, combining)] = code_point
    return composition


def encode_ccc(ccc):
    records = ["%x:%x" % (cp, value) for cp, value in sorted(ccc.items())]
    return ";".join(records)


def encode_decomposition(decomposition):
    records = []
    for code_point, mapping in sorted(decomposition.items()):
        targets = " ".join("%x" % cp for cp in mapping)
        records.append("%x=%s" % (code_point, targets))
    return ";".join(records)


def encode_composition(composition):
    records = []
    for (starter, combining), composed in sorted(composition.items()):
        records.append("%x,%x=%x" % (starter, combining, composed))
    return ";".join(records)


def chunk_blob(blob):
    """Slice a pure-ASCII blob into fixed-width chunks (no escaping needed)."""
    return [blob[i:i + CHUNK_BUDGET] for i in range(0, len(blob), CHUNK_BUDGET)]


def render_val(name, kdoc, chunks):
    lines = ["/** %s */" % kdoc, "internal val %s: List<String> =" % name, "    listOf("]
    for chunk in chunks:
        lines.append('        "%s",' % chunk)
    lines.append("    )")
    return lines


def render_kotlin(ccc_blob, decomp_blob, comp_blob):
    chunks = (chunk_blob(ccc_blob), chunk_blob(decomp_blob), chunk_blob(comp_blob))
    lines = [
        LICENSE_HEADER.rstrip("\n"),
        "",
        "// Generated bulk data, not hand-written logic: the chunked string tables intentionally",
        "// exceed detekt's size heuristics to stay within the 64 KB JVM string-constant limit.",
        '@file:Suppress("MatchingDeclarationName")',
        "",
        "package org.dexpace.kuri.idna",
        "",
        "// Compact, generated NFC table data (Unicode 16.0), derived from UnicodeData.txt and",
        "// CompositionExclusions.txt by tools/idna/generate_nfc_tables.py. Do not edit by hand;",
        "// re-run the generator instead. See Normalizer for the decoder and the UAX #15 contract.",
    ]
    blurbs = [
        ("NFC_CCC_CHUNKS", "Canonical Combining Class records `cpHex:cccHex` (non-zero only), joined by ';'.", chunks[0]),
        ("NFC_DECOMPOSITION_CHUNKS", "Canonical decomposition records `cpHex=d1 d2...`, joined by ';'.", chunks[1]),
        ("NFC_COMPOSITION_CHUNKS", "Canonical composition records `aHex,bHex=cpHex`, joined by ';'.", chunks[2]),
    ]
    for name, kdoc, table in blurbs:
        lines.append("")
        lines.extend(render_val(name, kdoc, table))
    lines.append("")
    return "\n".join(lines)


def main():
    ccc, decomposition = load_unicode_data(UNICODE_DATA)
    exclusions = load_exclusions(COMPOSITION_EXCLUSIONS)
    composition = build_composition(ccc, decomposition, exclusions)

    ccc_blob = encode_ccc(ccc)
    decomp_blob = encode_decomposition(decomposition)
    comp_blob = encode_composition(composition)

    with open(OUTPUT_KT, "w", encoding="utf-8") as handle:
        handle.write(render_kotlin(ccc_blob, decomp_blob, comp_blob))

    print("ccc entries          : %d (%d chars)" % (len(ccc), len(ccc_blob)))
    print("decomposition entries: %d (%d chars)" % (len(decomposition), len(decomp_blob)))
    print("composition pairs    : %d (%d chars)" % (len(composition), len(comp_blob)))
    print("explicit exclusions  : %d" % len(exclusions))
    print("output               : %s" % OUTPUT_KT)


if __name__ == "__main__":
    sys.exit(main())
