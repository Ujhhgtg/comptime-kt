import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
        optIn.add("org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi")
        optIn.add("org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI")
    }
    sourceSets.main { kotlin.srcDir(rootProject.file("protocol/src")) }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

val testRuntimeJar by configurations.creating { isTransitive = false }
val testHostClasspath by configurations.creating

dependencies {
    compileOnly(libs.kotlin.compiler.embeddable)

    testImplementation(libs.kotlin.compiler.embeddable)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)

    testRuntimeJar(project(":comptime-runtime"))
    testHostClasspath(project(":comptime-host"))
}

tasks.test {
    useJUnitPlatform()
    val pluginJar = tasks.jar.flatMap { it.archiveFile }
    inputs.files(pluginJar, testRuntimeJar, testHostClasspath)
    maxHeapSize = "2g"
    doFirst {
        systemProperty("comptime.pluginJar", pluginJar.get().asFile.absolutePath)
        systemProperty("comptime.runtimeJar", testRuntimeJar.singleFile.absolutePath)
        systemProperty("comptime.hostClasspath", testHostClasspath.asPath)
    }
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = false
    }
}

publishing {
    publications { create<MavenPublication>("maven") { from(components["java"]) } }
    repositories { maven { name = "test"; url = uri(rootProject.layout.buildDirectory.dir("repo")) } }
}
