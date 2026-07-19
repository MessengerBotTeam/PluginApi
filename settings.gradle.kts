pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "PluginApi"

// The contract is pure JVM so a plugin can be written against it anywhere, including on a
// non-Android engine's own terms. Everything that needs Android -- the binder plumbing every
// plugin would otherwise copy -- lives in :android.
include(":contract", ":android")
