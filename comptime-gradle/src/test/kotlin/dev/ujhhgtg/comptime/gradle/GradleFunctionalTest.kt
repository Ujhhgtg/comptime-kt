package dev.ujhhgtg.comptime.gradle

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Builds fixture projects with the real Gradle and Kotlin daemons, against the artifacts published to
 * `build/repo`. Covers the phase 1 done criterion (round trip from inside the Kotlin daemon) and the spike's
 * incremental-compilation questions.
 */
class GradleFunctionalTest {
    @TempDir
    lateinit var dir: File

    private val repo = System.getProperty("comptime.testRepo")
    private val version = System.getProperty("comptime.version")
    private val kotlinVersion = System.getProperty("comptime.kotlinVersion")

    private fun write(path: String, text: String) = File(dir, path).apply { parentFile.mkdirs(); writeText(text.trimIndent() + "\n") }

    private fun fixture(appBuildExtra: String = "") {
        write("settings.gradle.kts", """
            pluginManagement {
                repositories {
                    maven(uri("${repo.replace("\\", "/")}"))
                    maven("https://maven-central.storage-download.googleapis.com/maven2/")
                    gradlePluginPortal()
                }
            }
            dependencyResolutionManagement {
                repositories {
                    maven(uri("${repo.replace("\\", "/")}"))
                    maven("https://maven-central.storage-download.googleapis.com/maven2/")
                    mavenCentral()
                }
            }
            rootProject.name = "fixture"
            include(":lib", ":app")
        """)
        write("gradle.properties", """
            org.gradle.jvmargs=-Xmx1g
            kotlin.compiler.execution.strategy=daemon
            kotlin.suppressGradlePluginWarnings=DeprecatedGradleVersionWarning
        """)
        write("build.gradle.kts", """
            plugins {
                kotlin("jvm") version "$kotlinVersion" apply false
                id("dev.ujhhgtg.comptime") version "$version" apply false
            }
        """)
        write("lib/build.gradle.kts", """
            plugins { kotlin("jvm") }
        """)
        write("lib/src/main/kotlin/lib/Lib.kt", """
            package lib
            const val LIB_GREETING = "hello from lib"
        """)
        write("app/build.gradle.kts", """
            import java.time.Duration

            plugins {
                kotlin("jvm")
                id("dev.ujhhgtg.comptime")
                application
            }
            dependencies { implementation(project(":lib")) }
            application { mainClass.set("app.MainKt") }
            $appBuildExtra
        """)
        write("app/src/main/kotlin/app/Main.kt", """
            package app

            import dev.ujhhgtg.comptime.comptime

            val answer = comptime { 6 * 7 }
            val greeting = comptime { lib.LIB_GREETING.uppercase() }
            val table = comptime { (0 until 4).map { it * it } }

            fun main() {
                println("answer=${'$'}answer")
                println("greeting=${'$'}greeting")
                println("table=${'$'}table")
                println("data=" + Data.value)
            }
        """)
        write("app/src/main/kotlin/app/Data.kt", """
            package app

            import dev.ujhhgtg.comptime.comptime

            object Data {
                val value = comptime {
                    java.io.File("runs.log").appendText("x")
                    java.io.File("data.txt").readText().trim()
                }
            }
        """)
        write("app/src/main/kotlin/app/Other.kt", """
            package app
            fun other() = 1
        """)
        write("app/data.txt", "one")
    }

    private fun gradle(vararg args: String): BuildResult =
        GradleRunner.create()
            .withProjectDir(dir)
            .withArguments(*args, "--stacktrace")
            .forwardOutput()
            .build()

    private fun BuildResult.compileOutcome() = task(":app:compileKotlin")?.outcome

    private val runs get() = File(dir, "app/runs.log").takeIf { it.isFile }?.readText()?.length ?: 0

    @Test
    fun `round trip inside the Kotlin daemon`() {
        fixture()
        val result = gradle(":app:run", "--info")
        assertTrue("answer=42" in result.output, result.output)
        assertTrue("greeting=HELLO FROM LIB" in result.output, result.output)
        assertTrue("table=[0, 1, 4, 9]" in result.output, result.output)
        assertTrue("data=one" in result.output, result.output)
        assertTrue("Options for KOTLIN DAEMON" in result.output, "expected the compile to run in the Kotlin daemon")
        // comptime-runtime is compileOnly: it isn't on the runtime classpath.
        val deps = gradle(":app:dependencies", "--configuration", "runtimeClasspath").output
        assertFalse("comptime-runtime" in deps, deps)
    }

    @Test
    fun `a changed plugin option forces a full recompile, and IC tracks holder classes`() {
        fixture()
        gradle(":app:run")
        assertEquals(1, runs)

        // Undeclared file: Gradle doesn't know about it, so the compile stays up to date and the value is stale.
        write("app/data.txt", "two")
        val stale = gradle(":app:run")
        assertEquals(TaskOutcome.UP_TO_DATE, stale.compileOutcome())
        assertTrue("data=one" in stale.output, stale.output)

        // Editing an unrelated file recompiles incrementally and doesn't rerun blocks in other files.
        write("app/src/main/kotlin/app/Other.kt", "package app\nfun other() = 2\n")
        val incremental = gradle(":app:run")
        assertEquals(TaskOutcome.SUCCESS, incremental.compileOutcome())
        assertEquals(1, runs)
        assertTrue("data=one" in incremental.output, incremental.output)

        // A changed plugin option (the timeout) makes the task non-incremental: every file recompiles and every
        // block reruns, so the value is fresh.
        File(dir, "app/build.gradle.kts").appendText("\ncomptime { timeout.set(Duration.ofSeconds(61)) }\n")
        val fresh = gradle(":app:run", "--info")
        assertEquals(TaskOutcome.SUCCESS, fresh.compileOutcome())
        assertTrue("The input changes require a full rebuild for incremental task ':app:compileKotlin'" in fresh.output, fresh.output)
        assertTrue("data=two" in fresh.output, fresh.output)
        assertEquals(2, runs)

        // Removing a block drops its holder class from the output.
        val holder = File(dir, "app/build/classes/kotlin/main/app/Main\$comptime\$0.class")
        assertTrue(holder.isFile, "holder class for the table")
        write("app/src/main/kotlin/app/Main.kt", File(dir, "app/src/main/kotlin/app/Main.kt").readText()
            .replace("comptime { (0 until 4).map { it * it } }", "listOf(0, 1, 4, 9)"))
        gradle(":app:run")
        assertFalse(holder.exists(), "holder class should be removed by incremental compilation")
    }
}
