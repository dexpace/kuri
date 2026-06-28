import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

group = "org.dexpace"
version = "0.1.0-SNAPSHOT"

// Align Java compilation target with the Kotlin JVM target so the Kotlin Gradle
// plugin's consistency check passes when both toolchain (JDK 25) and bytecode
// target (JVM 8) are in use simultaneously.
tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = JavaVersion.VERSION_1_8.toString()
    targetCompatibility = JavaVersion.VERSION_1_8.toString()
}

kotlin {
    jvmToolchain(25)

    // Target Kotlin 2.0 language/API level: Kotlin 2.4 dropped support for 1.9.
    compilerOptions {
        languageVersion.set(KotlinVersion.KOTLIN_2_0)
        apiVersion.set(KotlinVersion.KOTLIN_2_0)
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
            // Resolve stdlib symbols against the Java 8 API so newer-JDK symbols
            // can't leak into a library that must run on Java 8.
            freeCompilerArgs.add("-Xjdk-release=1.8")
        }
    }

    js(IR) {
        browser()
        nodejs()
    }

    wasmJs {
        browser()
        nodejs()
    }

    linuxX64()
    linuxArm64()
    macosX64()
    macosArm64()
    mingwX64()
    iosX64()
    iosArm64()
    iosSimulatorArm64()
    watchosX64()
    watchosArm64()
    watchosSimulatorArm64()
    tvosX64()
    tvosArm64()
    tvosSimulatorArm64()

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
