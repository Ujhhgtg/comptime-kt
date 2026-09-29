import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

kotlin {
    explicitApi()
    compilerOptions { jvmTarget = JvmTarget.JVM_1_8 }
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

publishing {
    publications { create<MavenPublication>("maven") { from(components["java"]) } }
    repositories { maven { name = "test"; url = uri(rootProject.layout.buildDirectory.dir("repo")) } }
}
