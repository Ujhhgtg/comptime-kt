import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
    `maven-publish`
}

kotlin {
    compilerOptions { jvmTarget = JvmTarget.JVM_11 }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    compileOnly(libs.kotlin.gradle.plugin.api)

    testImplementation(gradleTestKit())
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

gradlePlugin {
    plugins {
        create("comptime") {
            id = "dev.ujhhgtg.comptime"
            implementationClass = "dev.ujhhgtg.comptime.gradle.ComptimeGradlePlugin"
            displayName = "comptime for Kotlin"
            description = "Runs comptime { } blocks at build time and bakes their results into the compiled code"
        }
    }
}

// The plugin resolves its sibling artifacts at its own version.
val generateVersion by tasks.registering {
    val outDir = layout.buildDirectory.dir("generated/version")
    val version = project.version.toString()
    inputs.property("version", version)
    outputs.dir(outDir)
    doLast {
        val file = outDir.get().file("dev/ujhhgtg/comptime/gradle/ComptimeVersion.kt").asFile
        file.parentFile.mkdirs()
        file.writeText("package dev.ujhhgtg.comptime.gradle\n\ninternal const val COMPTIME_VERSION = \"$version\"\n")
    }
}
kotlin.sourceSets.main { kotlin.srcDir(generateVersion) }

publishing {
    repositories { maven { name = "test"; url = uri(rootProject.layout.buildDirectory.dir("repo")) } }
}

tasks.test {
    useJUnitPlatform()
    // Functional tests build fixture projects against the artifacts published to build/repo.
    dependsOn(
        ":comptime-runtime:publishAllPublicationsToTestRepository",
        ":comptime-host:publishAllPublicationsToTestRepository",
        ":comptime-compiler:publishAllPublicationsToTestRepository",
        "publishAllPublicationsToTestRepository",
    )
    systemProperty("comptime.testRepo", rootProject.layout.buildDirectory.dir("repo").get().asFile.absolutePath)
    systemProperty("comptime.version", project.version.toString())
    systemProperty("comptime.kotlinVersion", libs.versions.kotlin.get())
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
