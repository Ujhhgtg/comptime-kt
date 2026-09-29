pluginManagement {
    repositories {
        // Google's Maven Central mirror first: repo1 rate-limits aggressively.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}

rootProject.name = "comptime-kt"

include(
    ":comptime-runtime",
    ":comptime-compiler",
    ":comptime-host",
    ":comptime-gradle",
)
