import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootExtension

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.kover) apply false
    alias(libs.plugins.binary.compat) apply false
}

// kotlin-js-store/yarn.lock pins whatever transitive versions this Kotlin/JS Gradle plugin release
// declares for its bundled browser-test tooling (mocha/karma/webpack); those versions aren't otherwise
// reachable from this build script, and a KGP bump is too heavy a lever for a single vulnerable
// transitive dep. Force Yarn's resolution field for each dependency Dependabot flagged, to their first
// patched release, until a future KGP upgrade moves the plugin's own defaults past them.
rootProject.plugins.withType<YarnPlugin>().configureEach {
    rootProject.extensions.configure<YarnRootExtension> {
        resolution("webpack", "5.104.1")
        resolution("serialize-javascript", "7.0.5")
        resolution("diff", "8.0.3")
    }
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
        Triple(
            "generateSetterTestData",
            "setters",
            "Regenerate the WPT setters_tests conformance fixture from the vendored ada WPT corpus.",
        ),
        Triple(
            "generatePercentEncodingTestData",
            "percent-encoding",
            "Regenerate the WPT percent-encoding conformance fixture from the vendored ada WPT corpus.",
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
