pluginManagement {
    repositories {
        // Android Gradle Plugin and the KMP Android library plugin are published only here.
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        // androidx.test.* (instrumented-test runtime) is published only here.
        google()
        mavenCentral()
    }
}

rootProject.name = "kuri"

include(":kuri")
include(":kuri-bind")
