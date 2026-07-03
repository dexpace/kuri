# Bumping the bundled Unicode version

kuri resolves IDNA/UTS-46 and NFC entirely from lookup tables it ships in source,
with no runtime ICU or platform-Unicode dependency. This keeps host processing
**offline and deterministic**: every target (JVM, JS, Wasm, native) decodes the
same tables and produces the same result for a given input, independent of the
host platform's Unicode version.

The tables are **generated, never hand-edited**. This runbook covers bumping the
bundled Unicode release that backs them.

> Current bundled version: **Unicode 17.0** (`unicodeVersionDir = "unicode-17.0"`).

## What's bundled

Generated Kotlin tables under `kuri/src/commonMain/kotlin/org/dexpace/kuri/idna/`:

- `IdnaMappingTableData.kt` — UTS-46 mapping table.
- `IdnaValidityData.kt` — leading-combining-mark, Virama, Joining_Type, and
  Bidi_Class ranges used by label validity.
- `NfcData.kt` — NFC canonical decomposition, primary composition, and CCC data.

Generated test fixtures under `kuri/src/commonTest/kotlin/org/dexpace/kuri/idna/`:

- `NfcTestData.kt` — derived from Unicode's `NormalizationTest.txt`.
- `IdnaConformanceData.kt` — the WPT IDNA corpora as a table fixture.
- `IdnaConformanceKnownFailures.kt` — the tracked failing-case baseline.

The generators are Go programs under `tools/` (module
`github.com/dexpace/kuri/tools`), driven by `tools/internal/codegen`. That
package reads raw UCD via `tools/internal/ucd` and derives the conformance
baseline via `tools/internal/idnaref`, a faithful Go port of kuri's runtime IDNA
engine.

## Source of truth

The bundled version is defined by exactly two things:

1. The `unicodeVersionDir` constant in `tools/internal/ucd/ucd.go` — a single
   string (e.g. `"unicode-17.0"`) that names the directory the generators read.
2. The committed generated `.kt` tables themselves.

The raw UCD files live under `.claude/references/<unicodeVersionDir>/` and are
**not** committed (`.claude/` is untracked). The WPT IDNA corpora
(`IdnaTestV2.json`, `toascii.json`) are vendored under
`.claude/references/ada/tests/wpt/` and are the ground truth the known-failures
baseline ratchets against.

The generators are exposed as `codegen`-group Gradle tasks in the root
`build.gradle.kts` (`generateIdnaMappingTable`, `generateIdnaValidityTables`,
`generateNfcTables`, `generateNfcTestFixture`, `generateIdnaConformanceFixture`,
and the aggregate `generateFixtures`). Each runs `go run . <subcommand>` in
`tools/`. They are developer tooling and are deliberately **not** wired into
`check`/`build`. Override the Go toolchain with `-Pgo=/path/to/go`.

## Recorded corpus revisions

The raw conformance corpora are **not fully tracked**: the WPT IDNA corpora and
`NormalizationTest.txt` live untracked under `.claude/references/`, while
`urltestdata.json` is the one corpus committed in-tree (under `tools/url/`). Only the
recorded revisions below are tracked here, so a conformance claim stays reproducible
against a fixed corpus snapshot even when the raw files are absent from the repository.

| Corpus | Upstream source | Pinned revision | Retrieved |
| --- | --- | --- | --- |
| `urltestdata.json` (WHATWG URL parsing + `href`) | WPT `url/resources/urltestdata.json`; committed at `tools/url/urltestdata.json` | upstream revision not recorded at vendoring; backfill from WPT history | 2026-06-30 |
| `toascii.json` (IDNA ToASCII + CheckBidi) | WPT `url/resources/toascii.json`, vendored via ada-url @ `791fb5c` | upstream revision not recorded at vendoring; backfill from WPT history | 2026-06-28 |
| `IdnaTestV2.json` (UTS-46 ToASCII/ToUnicode) | Unicode UTS-46 `IdnaTestV2` via WPT, vendored via ada-url @ `791fb5c` | upstream revision not recorded at vendoring; backfill from WPT history | 2026-06-28 |
| `NormalizationTest.txt` (NFC) | Unicode UCD `NormalizationTest.txt` | Unicode 17.0 (`unicodeVersionDir = "unicode-17.0"`) | 2026-07-01 |

The UCD/Unicode corpus is pinned to the bundled Unicode release (the
`unicodeVersionDir` constant in `tools/internal/ucd/ucd.go`). The WPT JSON corpora
carry no upstream commit recorded at the time they were vendored; the retrieval dates
above (for `urltestdata.json`, from `git log`) are the reproducibility handle until a
precise WPT commit is backfilled from history. `setters_tests.json`,
`percent-encoding.json`, and the `urlsearchparams` vectors are not consumed by the
current generators or suites, so they are intentionally omitted here.

## Update procedure

### 1. Vendor the new release's UCD files

Download into `.claude/references/unicode-<NEW>/` (e.g. `unicode-17.0`). Note
that `IdnaMappingTable.txt` comes from the separate IDNA tree; the rest come from
the version's `ucd/`:

```
# IdnaMappingTable.txt — from the IDNA tree (versioned)
https://www.unicode.org/Public/idna/<VER>/IdnaMappingTable.txt

# the rest — from the version's ucd/
https://www.unicode.org/Public/<VER>/ucd/UnicodeData.txt
https://www.unicode.org/Public/<VER>/ucd/extracted/DerivedJoiningType.txt
https://www.unicode.org/Public/<VER>/ucd/CompositionExclusions.txt
https://www.unicode.org/Public/<VER>/ucd/NormalizationTest.txt
```

If the versioned IDNA directory is not yet published, fall back to
`https://www.unicode.org/Public/idna/latest/IdnaMappingTable.txt` and verify its
`# Version:` header line matches the intended release.

`DerivedJoiningType.txt` may be placed at either `extracted/DerivedJoiningType.txt`
or the top level of the version directory — the loader in
`tools/internal/ucd/ucd.go` resolves both layouts.

### 2. Point the constant at the new directory

Update the single `unicodeVersionDir` constant in `tools/internal/ucd/ucd.go`:

```go
const unicodeVersionDir = "unicode-<NEW>"
```

### 3. Regenerate the tables and fixtures

```sh
./gradlew generateFixtures
```

This rewrites the generated `.kt` files in place from the new UCD. (Run the
individual `generate*` tasks instead if you want to regenerate one at a time.)

### 4. Re-baseline the conformance harness

`generateIdnaConformanceFixture` regenerates `IdnaConformanceKnownFailures.kt` by
running the `idnaref` reference over the corpora, so the tracked baseline always
equals the live failing set. Review the diff: the known-failures set should only
**shrink** (or change for an understood reason). Investigate any new entry before
accepting it.

### 5. Update human-facing version mentions

The version strings stamped into the **generated** headers (`IdnaMappingTableData.kt`,
`IdnaValidityData.kt`, `NfcData.kt`, and the `*TestData` / `*ConformanceData` fixtures)
are derived from `unicodeVersionDir` (via `ucd.BundledUnicodeVersion`), so regenerating
in step 3 rewrites them automatically — do **not** hand-edit those files.

What remains manual is the version mentioned in **hand-written** doc comments, which
codegen never touches. Search the repo for the outgoing version's `Unicode <OLD>` /
`unicode-<OLD>` strings and bump each hit that is not a generated file:

- the Go package/doc comments under `tools/internal/…`, and
- the hand-written Kotlin KDocs — `IdnaMappingTable.kt` and `IdnaValidity.kt` (decoders)
  and `IdnaConformanceTest.kt` and `NormalizerTest.kt` (tests).

Then update the "current version" note at the top of this file. (The mapping-table KDoc
had already drifted a full major release behind before this was written down, so treat
the grep as authoritative rather than trusting this list to stay complete.)

### 6. Run the full gate

```sh
./gradlew build
```

This runs ktlint, detekt, explicit-API strict mode, the binary-compatibility
validator (`apiCheck`), and the Kover coverage floor across all targets. If the
public API changed intentionally, regenerate and commit the snapshots:

```sh
./gradlew apiDump   # then commit api/*.api
```

### 7. Commit

Commit the regenerated `.kt` tables and fixtures together with the
`unicodeVersionDir` change in a single commit.

## Verifying the bump

Confirm the new data is actually in effect, not just regenerated:

- **A newly-assigned code point maps as expected.** Pick a scalar that the new
  release assigns or remaps under UTS-46 and assert that
  `IdnaMappingTableData`/`Idna` returns the new mapping (e.g. previously
  `disallowed`/unassigned, now mapped or valid). The point is to exercise a value
  that *changed* between the old and new releases, so a stale table would fail the
  check.

- **The conformance suites pass.**

  ```sh
  ./gradlew :kuri:jvmTest
  ```

  This runs the IDNA and NFC conformance suites against the regenerated tables and
  fixtures. With the baseline re-derived in step 4, it should be green.

## Corpus vs. bundled-version lag

The WPT corpora (`IdnaTestV2.json`, `toascii.json`) may target a **newer** Unicode
release than the tables kuri bundles. Conformance deltas attributable purely to
that lag are expected and stay tracked in `IdnaConformanceKnownFailures.kt` until
the bundled version catches up. When you bump to the release the corpus targets,
those entries should drop out of the baseline in step 4 — that disappearance is
the signal the lag has closed.
