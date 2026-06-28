# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

**kuri** — a URI/URL parsing and manipulation library for Java and Kotlin.

## Current state

This is a greenfield repository. As of now it contains only `README.md` and `LICENSE`.
There is **no source code, no build script (Gradle/Maven), and no test suite yet** — even
though `.gitignore` is pre-seeded with Gradle entries, no `build.gradle(.kts)` or wrapper
exists.

When asked to build, test, or run anything, first check whether the relevant tooling has
been added; do not assume commands that aren't present in the repo.

## Toolchain (intended targets)

- **JDK:** Corretto 25 (project language level JDK 25).
- **Kotlin:** 2.3.10, language/API version 2.3.
- **JVM bytecode target:** 1.8 — keep this in mind for a published library; target Java 8
  bytecode for broad consumer compatibility even though the build JDK is newer. Avoid
  APIs unavailable on the Java 8 runtime in shipped library code (`InputStream.transferTo`
  (9+), `Thread.threadId()` (19+), records, sealed `permits`, etc.).
- **Cross-compile toolchain discipline:** a module that genuinely needs a newer JDK must
  override **all three** of `jvmToolchain(N)`, the `java { sourceCompatibility /
  targetCompatibility }` block, and `compilerOptions { jvmTarget.set(JvmTarget.JVM_N) }`.
  Overriding only the toolchain emits Java-8-format bytecode that references newer stdlib
  symbols (`NoSuchMethodError` on JDK 8).

## Target build setup & conventions

This repo is greenfield, but it sits in the **Dexpace** family alongside
`dexpace/java-sdk`, which it mirrors. Apply these conventions as the library is built out
(they describe the intended setup, not what's wired in today):

- **Build tool:** Gradle (Kotlin DSL). `gradle/libs.versions.toml` is the single source of
  truth for dependency and plugin versions. Group `org.dexpace`.
- **Quality gates wired into `check` (all break the build):** `ktlint` + `detekt`,
  `allWarningsAsErrors`, **explicit-API strict mode**, **binary-compatibility-validator**
  (`apiCheck`/`apiDump`), and a Kover line-coverage floor. Run `./gradlew build` to execute
  the full gate; `./gradlew apiDump` only after an **intentional** public-API change, and
  commit the regenerated `api/*.api` snapshots alongside it.
- **MIT license header in every source file** — each `.kt`/`.java`/`.kts` starts with the
  `Copyright (c) 2026 dexpace and Omar Aljarrah` / `SPDX-License-Identifier: MIT` block.
  This is a review convention; nothing enforces it automatically.
- **Java interop is first-class.** Immutable data uses a **private constructor + `Builder`
  + `newBuilder()`** (pre-filled), with `@JvmOverloads`/`@JvmStatic` for ergonomic Java
  call sites. Public declarations need explicit visibility and explicit return types
  (explicit-API strict mode); use `internal` for cross-package impl details,
  `@JvmSynthetic internal` when the mangled name would still be Java-callable, `private`
  otherwise.
- **Concurrency:** prefer `ReentrantLock` (`lock.withLock { … }`) over `synchronized`
  (synchronized pins carrier threads under Loom). Blocking calls must respect
  `Thread.interrupt()` — catch `InterruptedException`, restore the interrupt flag, and
  throw `InterruptedIOException` (or attach the interrupt as suppressed).
- **Commit style:** `feat:` / `test:` / `docs:` / `chore:` prefixes (`merge:` for merge
  commits). **PR titles follow the same prefixed style** (e.g. `docs: add CLAUDE.md`).

## Design notes

- The library targets **both Java and Kotlin consumers**. When the public API is
  introduced, keep it idiomatic and ergonomic from Java (e.g. avoid Kotlin-only
  constructs in the public surface where they degrade the Java experience).

## Code style

All code follows the **Dexpace styleguide**: https://github.com/dexpace/styleguide
(Kotlin rules: https://github.com/dexpace/styleguide/tree/main/kotlin). The styleguide is
authoritative — consult it when a rule below is ambiguous. It also includes cross-cutting
guides for Security, Performance, and Git & Code Review.

Concern ordering, used to resolve conflicts: **correctness > performance > developer
experience**. Style priorities (in order): clarity, simplicity, concision,
maintainability, consistency (tiebreaker).

### Universal rules (apply everywhere)

- **Data + functions, not objects** — prefer `data class` records and top-level/extension
  functions over inheritance hierarchies.
- **Explicit over implicit** — no hidden control flow, reflection, framework magic, or
  global mutable state in new code; all dependencies visible.
- **Immutable by default** — `val` over `var`; return new values; expose read-only
  collections in public APIs.
- **Errors are values** — cover every error path; wrap with context at boundaries.
- **Composition over inheritance** — pipelines and `by` delegation, not class hierarchies.
- **Transform, don't mutate** — pure functions: input in, output out.
- **Always say why** — comments explain rationale, not mechanics.
- **Assert aggressively** — average ≥2 assertions per function (preconditions,
  postconditions, invariants).
- **Limits on everything** — fixed bounds on loops, retries, queues, buffers, timeouts.
- **Small functions** — one thing each; **60-line hard cap**, target 15–30 lines.
- **Performance from the outset** — optimize the slowest resource at design time.
- **Zero technical debt** — "perfection over technical debt — debt never gets paid."
- Don't mix styles within a package; refactor at module level or larger.

### Kotlin specifics

- **Formatting & tooling:** `ktlint` formats, `detekt` lints, both gating CI; config in
  `.editorconfig`, tool versions pinned via the Gradle version catalog. **120-column** hard
  line limit. Explicit imports only (no wildcards). Trailing commas allowed everywhere.
  Expression bodies only for single-expression functions. Braces on conditionals except
  single-line `val y = if (x) a else b`.
- **Naming:** `camelCase` members, `PascalCase` types, `SCREAMING_SNAKE_CASE` const/value
  constants; acronyms as words (`httpClient`). Scope-proportional names. `is/has/should`
  for booleans; nouns for state, verbs for actions. No Hungarian/`I*` prefixes, no
  `*Manager`/`*Helper`/`*Util`. Backticked identifiers only in test names. Packages
  lowercase, structured by feature not layer.
- **Nullability:** non-null default; **`!!` is banned** (outside `main`, tests, justified
  Java bridges). Resolve at the boundary; prefer `?:` with meaningful defaults, smart
  casts, and `requireNotNull`/`checkNotNull` over manual throws. `lateinit` only for
  framework injection; `by lazy` for self-driven init. No `java.util.Optional` — use `T?`.
- **Declarations:** one per line; `const val` for compile-time constants only; limit
  `companion object` to constants/factories (utilities go top-level); explicit types on
  public/protected signatures, infer locally.
- **Data modeling:** `data class` (all-`val`) for value types; `@JvmInline value class`
  for IDs/wrappers (never `typealias`); `sealed` hierarchies for closed polymorphism with
  exhaustive `when` (no `else`); `object` for singletons; never `open` without a documented
  inheritance contract; default to `internal` visibility.
- **Error handling:** domain failures are sealed ADTs via a project-defined
  `Result<T, E>` (`Ok`/`Err`), **not** `kotlin.Result`. Exceptions only for unrecoverable
  failures, programmer errors, external faults; never catch bare `Exception`/`Throwable`.
  `require`/`check`/`error` for assertions; translate foreign exceptions at module
  boundaries; `runCatching` only at adapters (rethrow `CancellationException`).
- **API design:** narrowest visibility that compiles; small consumer-driven interfaces
  (`fun interface` for SAM); declaration-site variance (`out`/`in`); default + named args
  over builders for ≤7 params; explicit public return types; `@RequiresOptIn` for unstable
  APIs; read-only collections in/out; `suspend` for coroutine callers only (bridge Java
  with `CompletableFuture`).
- **Testing:** backticked sentence names (`<action> <expected> when <condition>`);
  arrange/act/assert; one concern per test; independent and parallel-safe; fakes/builders
  over shared `lateinit`; mock only at real seams (network, DB, clock); `@ParameterizedTest`
  / Kotest `withData` for tables; inject `Clock`/`Random`/ID sources; `runTest` (no
  `Thread.sleep`); fluent assertions (AssertJ/Kotest) with context.
- **Documentation:** KDoc on every `public` symbol (summary, body, then `@param`/`@return`/
  `@throws`/`@sample`/`@see`); document *why*, nullable/`Result` returns, and every
  deliberately thrown exception; `package.md` per public package; `@Deprecated` with
  `ReplaceWith` and a removal version.

> Note: because the build is still Gradle-less, the `ktlint`/`detekt`/version-catalog
> tooling above is the target setup to wire in when the build is created — it is not yet
> present in the repo.
