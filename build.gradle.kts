plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.kover) apply false
    alias(libs.plugins.binary.compat) apply false
}

// --- Fixture & lookup-table code generation ----------------------------------
// Thin Gradle wrappers over the Go code generators under tools/, so every fixture and lookup
// table regenerates via `./gradlew` rather than invoking the Go tool by hand. Each task runs the
// `codegen` command (tools/main.go) via `go run . <name>` in tools/, which reads reference corpora
// vendored under tools/ and (for the IDNA tables) the untracked Unicode/WPT data under
// .claude/references/. They are developer tooling: grouped under "codegen" and deliberately NOT
// wired into `check`/`build`. Override the Go toolchain with `-Pgo=/path/to/go`.
val goTool: String = (findProperty("go") as String?) ?: "go"
val toolsDir = file("tools")

val generators: List<Triple<String, String, String>> =
    listOf(
        Triple(
            "generateUrlTestData",
            "url",
            "Regenerate the WPT urltestdata conformance fixture from tools/url/urltestdata.json.",
        ),
        Triple(
            "generateIdnaMappingTable",
            "idna-mapping",
            "Regenerate the UTS-46 IDNA mapping-table data from the bundled Unicode UCD.",
        ),
        Triple(
            "generateIdnaValidityTables",
            "idna-validity",
            "Regenerate the UTS-46 IDNA label-validity table data from the bundled Unicode UCD.",
        ),
        Triple(
            "generateNfcTables",
            "nfc-tables",
            "Regenerate the NFC normalization table data from the bundled Unicode UCD.",
        ),
        Triple(
            "generateNfcTestFixture",
            "nfc-test",
            "Regenerate the NFC conformance fixture from Unicode NormalizationTest.txt.",
        ),
        Triple(
            "generateIdnaConformanceFixture",
            "conformance",
            "Regenerate the IDNA conformance fixture from the WPT IdnaTestV2 and toascii corpora.",
        ),
    )

val codegenTasks: List<TaskProvider<Exec>> =
    generators.map { (taskName, subcommand, summary) ->
        tasks.register<Exec>(taskName) {
            group = "codegen"
            description = summary
            workingDir = toolsDir
            commandLine(goTool, "run", ".", subcommand)
        }
    }

tasks.register("generateFixtures") {
    group = "codegen"
    description = "Run every Go fixture and lookup-table generator under tools/."
    dependsOn(codegenTasks)
}

// --- Git hooks ---------------------------------------------------------------
// Point git at the tracked .githooks/ directory so the Conventional-Commits commit-msg gate is
// active for every contributor without a manual setup step. `git config core.hooksPath` is
// idempotent, so re-running on every build is a cheap no-op once configured. Note it supersedes any
// per-clone hooks under .git/hooks/ for this repo. The `.git` File is
// resolved to a *local* val at configuration time and captured into the onlyIf predicate as a plain
// File (never the build-script/Project object), so the task stays configuration-cache compatible and
// self-skips in a source tarball or any non-git checkout. The `:kuri` module wires this into its
// `check` task (see kuri/build.gradle.kts) so a normal `./gradlew build` installs the hook.
tasks.register<Exec>("installGitHooks") {
    group = "git hooks"
    description = "Route git at the tracked .githooks/ directory (installs the commit-msg gate)."
    val gitDir = rootProject.file(".git")
    onlyIf { gitDir.exists() }
    commandLine("git", "config", "core.hooksPath", ".githooks")
}
