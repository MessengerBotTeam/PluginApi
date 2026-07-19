plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    `maven-publish`
}

android {
    namespace = "com.xfl.msgbot.plugin.ipc"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") { withSourcesJar() }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    // api, not implementation: a plugin writes against the contract, and gets it by depending here.
    api(project(":contract"))
}

publishing {
    repositories {
        // Published locally so a plugin built beside this repo resolves the real artifact rather
        // than a composite substitution: the two cannot share one AGP version, and a plugin author
        // gets it from a repository anyway.
        mavenLocal()
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/MessengerBotTeam/PluginAPI")
            credentials {
                username = project.findProperty("gpr.user") as String? ?: System.getenv("USERNAME")
                password = project.findProperty("gpr.key") as String? ?: System.getenv("TOKEN")
            }
        }
    }
    publications {
        register<MavenPublication>("gpr") {
            artifactId = "PluginApi-android"
            afterEvaluate { from(components["release"]) }
        }
    }
}
