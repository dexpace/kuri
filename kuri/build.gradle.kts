@file:OptIn(
    org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class,
    kotlinx.validation.ExperimentalBCVApi::class,
)

import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SonatypeHost
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
    alias(libs.plugins.binary.compat)
    alias(libs.plugins.dokka)
    alias(libs.plugins.maven.publish)
}

group = "org.dexpace"

// Align Java compilation target with the Kotlin JVM target so the Kotlin Gradle
// plugin's consistency check passes when both toolchain (JDK 21) and bytecode
// target (JVM 8) are in use simultaneously.
tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = JavaVersion.VERSION_1_8.toString()
    targetCompatibility = JavaVersion.VERSION_1_8.toString()
}

// Generate the library version constant from `project.version` (the release-please-managed `version=`
// in gradle.properties) so `Kuri.VERSION` always matches the published Maven coordinate and can never
// drift the way a hand-maintained literal did. The file is regenerated whenever the version changes and
// compiled into commonMain; it is kept out of ktlint below and stays `internal`, so detekt (which scans
// only `src/`), BCV (public API only) and Dokka (suppresses build-dir files) all ignore it.
val generatedKuriVersionDir: Provider<Directory> = layout.buildDirectory.dir("generated/kuriVersion")

val generateKuriVersion: TaskProvider<Task> =
    tasks.register("generateKuriVersion") {
        val kuriVersion: String = project.version.toString()
        val outputDir: Provider<Directory> = generatedKuriVersionDir
        inputs.property("kuriVersion", kuriVersion)
        outputs.dir(outputDir)
        doLast {
            val packageDir: java.io.File = outputDir.get().dir("org/dexpace/kuri").asFile
            packageDir.mkdirs()
            packageDir.resolve("KuriVersion.kt").writeText(
                buildString {
                    appendLine("/*")
                    appendLine(" * Copyright (c) 2026 dexpace and Omar Aljarrah")
                    appendLine(" * SPDX-License-Identifier: MIT")
                    appendLine(" */")
                    appendLine("package org.dexpace.kuri")
                    appendLine()
                    appendLine("internal const val KURI_VERSION: String = \"$kuriVersion\"")
                },
            )
        }
    }

kotlin {
    jvmToolchain(21)
    explicitApi()

    // Target Kotlin 2.0 language/API level: Kotlin 2.4 dropped support for 1.9.
    compilerOptions {
        languageVersion.set(KotlinVersion.KOTLIN_2_0)
        apiVersion.set(KotlinVersion.KOTLIN_2_0)
        allWarningsAsErrors.set(true)
        // Compiling against a below-default language version emits a deprecation
        // warning; suppress it so allWarningsAsErrors doesn't fail the build.
        freeCompilerArgs.add("-Xsuppress-version-warnings")
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
            // Resolve stdlib symbols against the Java 8 API so newer-JDK symbols
            // can't leak into a library that must run on Java 8.
            freeCompilerArgs.add("-Xjdk-release=1.8")
        }
    }

    js {
        browser {
            // Chrome's sandbox needs privileges CI runners don't reliably grant (a runner
            // image or user-namespace change can trip "No usable sandbox"). The Karma test
            // bundle is our own trusted code, so run headless Chrome with the sandbox off.
            testTask {
                useKarma {
                    useChromeHeadlessNoSandbox()
                }
            }
        }
        nodejs()
    }

    wasmJs {
        browser {
            testTask {
                useKarma {
                    useChromeHeadlessNoSandbox()
                }
            }
        }
        nodejs()
    }

    // Android (ART) via the KMP-dedicated library plugin. commonTest runs as host (JVM) unit
    // tests. It is deliberately NOT shared into the device (instrumented) test: its backticked
    // names contain spaces, which are invalid DEX method names below API 35 (DEX v040), so a
    // dedicated on-device smoke test in src/androidDeviceTest verifies kuri runs on ART instead.
    android {
        namespace = "org.dexpace.kuri"
        compileSdk = 35
        minSdk = 21

        withHostTestBuilder {}.configure {}
        withDeviceTestBuilder {}.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }

        // Emit Java 8 bytecode, consistent with the jvm() target.
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
        }
    }

    linuxX64()
    linuxArm64()
    macosArm64()
    mingwX64()
    iosX64()
    iosArm64()
    iosSimulatorArm64()
    watchosArm64()
    watchosSimulatorArm64()
    tvosArm64()
    tvosSimulatorArm64()

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain {
            // Compile the build-generated KURI_VERSION constant into the library. Passing the task
            // provider both registers its output dir as a source root and wires the compile dependency,
            // so every target (jvm/js/wasmJs/native/android/metadata) regenerates it before compiling.
            kotlin.srcDir(generateKuriVersion)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        // The JVM test set also holds a Java interop test (src/jvmTest/java) that guards the
        // documented Java call sites. It uses JUnit 4 directly, but no explicit junit:junit
        // dependency is needed: kotlin-test-junit is the kotlin.test-to-JUnit-4 bridge and
        // exposes junit:junit as an api dependency, so binding the backend also puts JUnit 4
        // on the Java test's compile classpath, at the version the Kotlin release was built for.
        jvmTest.dependencies {
            implementation(kotlin("test-junit"))
        }
        // Instrumented tests run under AndroidJUnitRunner (JUnit4), so bind kotlin.test to JUnit4
        // and pull in the AndroidX test runtime that provides the runner.
        getByName("androidDeviceTest").dependencies {
            implementation(kotlin("test-junit"))
            implementation("androidx.test:runner:1.6.2")
            implementation("androidx.test.ext:junit:1.2.1")
        }
    }
}

// API reference (Dokka v2). Pull the per-package `package.md` prose into the generated site so each
// public package carries its overview. `configureEach` applies the includes across every source set;
// the package docs describe the common packages that all platform source sets share.
dokka {
    dokkaSourceSets.configureEach {
        includes.from(
            "src/commonMain/kotlin/org/dexpace/kuri/package.md",
            "src/commonMain/kotlin/org/dexpace/kuri/error/package.md",
            "src/commonMain/kotlin/org/dexpace/kuri/host/package.md",
            "src/commonMain/kotlin/org/dexpace/kuri/query/package.md",
        )
    }
}

// Keep the code-generated version constant out of the ktlint set: it is machine-written, carries its
// own license header, and is compiled but never hand-edited, so formatting rules should not gate on it.
ktlint {
    filter {
        exclude { element -> element.file.invariantSeparatorsPath.contains("/generated/kuriVersion/") }
    }
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    source.setFrom(
        "src/commonMain/kotlin",
        "src/commonTest/kotlin",
        "src/jvmMain/kotlin",
        "src/jvmTest/kotlin",
    )
}

// JVM coverage floors, wired into `check` for the total (merged) variant by the Kover plugin.
// Line: 99% — the residual < 1% is provably-unreachable defensive code (aggressive `check`/`require`
// message bodies and exhaustive-`when` `error()` guards whose false arms upstream invariants make
// impossible), so 99 is the honest ceiling: every reachable line is exercised.
// Branch: 85% (actual ~86%) — deliberately lower than the line floor and with headroom, because the
// same aggressive assertions each contribute an uncoverable failure branch and `check`s over the
// generated Unicode tables can never take their error arm. The floor guards against gross regression
// without penalising the addition of new defensive assertions.
kover {
    reports {
        verify {
            rule {
                minBound(99, CoverageUnit.LINE)
                minBound(85, CoverageUnit.BRANCH)
            }
        }
    }
}

// Enforce the native (KLIB) public ABI, not only the JVM/Android one. binary-compatibility-validator's
// klib validation is experimental and off by default, so `apiCheck` was already compiling every native
// klib but then skipping the comparison — wasted work that guaranteed nothing. Enabling it makes
// `apiDump` emit the merged `api/kuri.klib.api` snapshot and `apiCheck` diff each native target's ABI
// against it, so a source-compatible but ABI-breaking change to the native surface fails the build.
// The comparison only re-compiles the klib targets buildable on the CI host running `apiCheck`: the Linux
// quality-gate job verifies linuxX64/linuxArm64/mingw/js/wasm, and infers the Apple targets it cannot
// build there from the grouped dump rather than re-verifying them. That holds only because the public API
// is fully common (the dump has no per-target sections); a platform-specific public declaration would
// need `apiCheck` to also run on macOS to be guarded. Regenerate the merged dump on macOS (see CLAUDE.md).
apiValidation {
    klib {
        enabled = true
    }
}

// Publish every Kotlin Multiplatform target (plus the root `kotlinMultiplatform` publication) to Maven
// Central through the Central Portal. The vanniktech plugin wires the per-target publications, a
// Dokka-generated Javadoc jar and GPG signing; coordinates derive from the module `group` and the
// `version` set in gradle.properties (managed by release-please). Central release is left manual for
// local/dry runs — CI runs the `publishAndReleaseToMavenCentral` task to upload and release in one go.
mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = false)
    signAllPublications()

    configure(
        KotlinMultiplatform(
            javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml"),
        ),
    )

    pom {
        name.set("kuri")
        description.set("A standards-faithful URI and URL library for Kotlin Multiplatform and Java.")
        url.set("https://github.com/dexpace/kuri")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("OmarAljarrah")
                name.set("Omar Aljarrah")
            }
        }
        scm {
            url.set("https://github.com/dexpace/kuri")
            connection.set("scm:git:https://github.com/dexpace/kuri.git")
            developerConnection.set("scm:git:ssh://git@github.com/dexpace/kuri.git")
        }
    }
}
