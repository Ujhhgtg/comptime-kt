import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

kotlin {
    compilerOptions { jvmTarget = JvmTarget.JVM_1_8 }
    sourceSets.main { kotlin.srcDir(rootProject.file("protocol/src")) }
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

dependencies {
    implementation(libs.kotlin.compiler.embeddable)
}

publishing {
    publications { create<MavenPublication>("maven") { from(components["java"]) } }
    repositories { maven { name = "test"; url = uri(rootProject.layout.buildDirectory.dir("repo")) } }
}
