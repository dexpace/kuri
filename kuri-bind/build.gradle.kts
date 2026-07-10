/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 * SPDX-License-Identifier: MIT
 */
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
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

tasks.withType<JavaCompile>().configureEach {
    // The jvmTest Java fixtures include a `record` (a Java 16 language feature) to prove record binding,
    // so the test-only Java compilation targets 16. Shipped code is pure Kotlin pinned to JVM 1.8 (see
    // the jvm block below); no Java is compiled into the published artifact, so this does not relax the
    // runtime floor of the library itself.
    val forTestFixtures = name == "compileJvmTestJava"
    val level = if (forTestFixtures) JavaVersion.VERSION_16 else JavaVersion.VERSION_1_8
    sourceCompatibility = level.toString()
    targetCompatibility = level.toString()
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    // The record test fixture (RecordItem.java) compiles to Java 16 bytecode while the paired Kotlin
    // test code keeps the shipped 1.8 target. The cross-task JVM-target equality check exists to protect
    // published jars from mixed targets; test classes are never published and run on the JDK 21 toolchain,
    // so relax the check to a warning for this one source set rather than dragging the test target up.
    if (name == "compileTestKotlinJvm") {
        jvmTargetValidationMode.set(org.jetbrains.kotlin.gradle.dsl.jvm.JvmTargetValidationMode.WARNING)
    }
}

kotlin {
    jvmToolchain(21)
    explicitApi()

    compilerOptions {
        languageVersion.set(KotlinVersion.KOTLIN_2_0)
        apiVersion.set(KotlinVersion.KOTLIN_2_0)
        allWarningsAsErrors.set(true)
        freeCompilerArgs.add("-Xsuppress-version-warnings")
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
            freeCompilerArgs.add("-Xjdk-release=1.8")
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":kuri"))
        }
        jvmMain.dependencies {
            implementation(libs.kotlin.reflect)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(kotlin("test-junit"))
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

// Coverage floors (see :kuri for the rationale). Line: 99% — the single residual line is
// PlanCompiler's defensive `else -> null`, unreachable because `bindingAnnotation` only ever yields a
// known marker. Branch: 88% (actual ~91%) — floored below the line coverage and with headroom, as the
// reflective binder's `require`/`check` guards each add an uncoverable failure branch.
kover {
    reports {
        verify {
            rule {
                minBound(99, CoverageUnit.LINE)
                minBound(88, CoverageUnit.BRANCH)
            }
        }
    }
}
