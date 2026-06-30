plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.kover) apply false
    alias(libs.plugins.binary.compat) apply false
}

// --- Fixture & lookup-table code generation ----------------------------------
// Thin Gradle wrappers over the Go code generators under tools/, so every fixture and lookup
// table regenerates via `./gradlew` rather than invoking the Go tool by hand. Each task runs a
// `tools/cmd/codegen` subcommand, which reads reference corpora vendored under tools/ and (for the
// IDNA tables) the untracked Unicode/WPT data under .claude/references/. They are developer
// tooling: grouped under "codegen" and deliberately NOT wired into `check`/`build`. Override the
// Go toolchain with `-Pgo=/path/to/go`.
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
