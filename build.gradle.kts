plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.kover) apply false
    alias(libs.plugins.binary.compat) apply false
}

// --- Fixture & lookup-table code generation ----------------------------------
// Thin Gradle wrappers over the offline Python generators under tools/, so every fixture and
// lookup table regenerates via `./gradlew` instead of a bare `python3` invocation. They read
// reference corpora vendored under tools/ and (for the IDNA tables) the untracked Unicode/WPT
// data under .claude/references/, so they are developer tooling: grouped under "codegen" and
// deliberately NOT wired into `check`/`build`. Override the interpreter with `-Ppython=…`.
val pythonInterpreter: String = (findProperty("python") as String?) ?: "python3"

val generators: List<Triple<String, String, String>> =
    listOf(
        Triple(
            "generateUrlTestData",
            "tools/url/generate_urltestdata_fixture.py",
            "Regenerate the WPT urltestdata conformance fixture from tools/url/urltestdata.json.",
        ),
        Triple(
            "generateIdnaMappingTable",
            "tools/idna/generate_idna_mapping_table.py",
            "Regenerate the UTS-46 IDNA mapping-table data from the bundled Unicode UCD.",
        ),
        Triple(
            "generateIdnaValidityTables",
            "tools/idna/generate_idna_validity_tables.py",
            "Regenerate the UTS-46 IDNA label-validity table data from the bundled Unicode UCD.",
        ),
        Triple(
            "generateNfcTables",
            "tools/idna/generate_nfc_tables.py",
            "Regenerate the NFC normalization table data from the bundled Unicode UCD.",
        ),
        Triple(
            "generateNfcTestFixture",
            "tools/idna/generate_nfc_test_fixture.py",
            "Regenerate the NFC conformance fixture from Unicode NormalizationTest.txt.",
        ),
        Triple(
            "generateIdnaConformanceFixture",
            "tools/idna/generate_conformance_fixture.py",
            "Regenerate the IDNA conformance fixture from the WPT IdnaTestV2 and toascii corpora.",
        ),
    )

val codegenTasks: List<TaskProvider<Exec>> =
    generators.map { (taskName, script, summary) ->
        tasks.register<Exec>(taskName) {
            group = "codegen"
            description = summary
            workingDir = rootDir
            commandLine(pythonInterpreter, script)
        }
    }

tasks.register("generateFixtures") {
    group = "codegen"
    description = "Run every Python fixture and lookup-table generator under tools/."
    dependsOn(codegenTasks)
}
