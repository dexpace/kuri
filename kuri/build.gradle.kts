@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SonatypeHost
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

// Enforce an 80% JVM line-coverage floor. koverVerify is automatically wired
// into check for the total (merged) variant by the Kover plugin — no manual
// task dependency is needed.
kover {
    reports {
        verify {
            rule {
                minBound(80)
            }
        }
    }
}

// Install the tracked commit-msg hook as part of a normal build. Wiring lives on this side of the
// cross-project edge — depending on the root task through a lazy provider resolved at configuration
// time — which keeps it configuration-cache compatible (no execution-time Project access). Because
// `build` depends on `check`, `./gradlew build` and `./gradlew check` both install the hook, and the
// install task itself is idempotent.
tasks.named("check") {
    dependsOn(rootProject.tasks.named("installGitHooks"))
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
        description.set("A standards-faithful URI and URL library for Kotlin and Java.")
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
