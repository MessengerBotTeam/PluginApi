plugins {
    kotlin("jvm")
    `maven-publish`
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
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
            // Kept as the old coordinates: this module is what "PluginApi" always was, and the
            // Android half is a separate artifact rather than a rename of this one.
            artifactId = "PluginApi"
            from(components["java"])
        }
    }
}