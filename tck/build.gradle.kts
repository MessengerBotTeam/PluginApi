plugins {
    kotlin("jvm")
    `maven-publish`
}

repositories {
    mavenCentral()
}

dependencies {
    api(project(":contract"))
    // JUnit 4, because Android unit tests run it: an engine's own test extends these classes.
    api("junit:junit:4.13.2")
    api(kotlin("test-junit"))
}

kotlin {
    jvmToolchain(21)
}

publishing {
    repositories {
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
            artifactId = "PluginApi-tck"
            from(components["java"])
        }
    }
}
