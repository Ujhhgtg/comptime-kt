plugins {
    kotlin("jvm") version "2.3.20"
}

group = "dev.ujhhgtg"
version = "unspecified"

dependencies {
    implementation(project(":api"))
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    val pluginJarTask = project(":plugin").tasks.named<org.gradle.jvm.tasks.Jar>("jar")
    dependsOn(pluginJarTask)

    compilerOptions {
        val pluginJarPath = pluginJarTask.get().archiveFile.get().asFile.absolutePath
        freeCompilerArgs.add("-Xplugin=$pluginJarPath")
    }
}

kotlin {
    jvmToolchain(21)
}
