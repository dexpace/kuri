@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
    alias(libs.plugins.binary.compat)
}

group = "org.dexpace"
version = "0.1.0-SNAPSHOT"

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
