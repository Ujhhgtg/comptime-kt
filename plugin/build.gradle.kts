plugins {
    kotlin("jvm") version "2.3.20"
    kotlin("kapt") version "2.3.20"
}

group = "dev.ujhhgtg"
version = "unspecified"

dependencies {
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.3.20")
    compileOnly("com.google.auto.service:auto-service-annotations:1.1.1")
    kapt("com.google.auto.service:auto-service:1.1.1")
}

kotlin {
    jvmToolchain(21)
}
