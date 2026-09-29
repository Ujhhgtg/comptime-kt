package dev.ujhhgtg.comptime.gradle

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Base for functional tests: fixture projects built with the real Gradle and Kotlin daemons, against the artifacts
 * published to `build/repo`.
 */
abstract class FixtureTest {
    @TempDir
    lateinit var dir: File

    protected val repo: String = System.getProperty("comptime.testRepo").replace("\\", "/")
    protected val version: String = System.getProperty("comptime.version")
    protected val kotlinVersion: String = System.getProperty("comptime.kotlinVersion")

    protected fun write(path: String, text: String) =
        File(dir, path).apply { parentFile.mkdirs(); writeText(text.trimIndent() + "\n") }

    protected fun read(path: String) = File(dir, path).readText()

    /** Settings with the test repo, the Maven Central mirror and, optionally, Google's repo, plus [projects]. */
    protected fun settings(vararg projects: String, google: Boolean = false) {
        val googleRepo = if (google) "google()" else ""
        write("settings.gradle.kts", """
            pluginManagement {
                repositories {
                    maven(uri("$repo"))
                    $googleRepo
                    maven("https://maven-central.storage-download.googleapis.com/maven2/")
                    gradlePluginPortal()
                }
            }
            dependencyResolutionManagement {
                repositories {
                    maven(uri("$repo"))
                    $googleRepo
                    maven("https://maven-central.storage-download.googleapis.com/maven2/")
                    mavenCentral()
                }
            }
            rootProject.name = "fixture"
            include(${projects.joinToString { "\"$it\"" }})
        """)
        write("gradle.properties", """
            org.gradle.jvmargs=-Xmx1536m
            org.gradle.java.installations.auto-download=false
            kotlin.compiler.execution.strategy=daemon
            kotlin.suppressGradlePluginWarnings=DeprecatedGradleVersionWarning
        """)
    }

    private fun runner(args: Array<out String>, env: Map<String, String>?): GradleRunner =
        GradleRunner.create()
            .withProjectDir(dir)
            .withArguments(*args, "--stacktrace")
            .forwardOutput()
            .apply { if (env != null) withEnvironment(System.getenv() + env) }

    protected fun gradle(vararg args: String, env: Map<String, String>? = null): BuildResult = runner(args, env).build()

    protected fun gradleFails(vararg args: String, env: Map<String, String>? = null): BuildResult = runner(args, env).buildAndFail()

    /** The value a fixture's `main` printed as `name=value`. */
    protected fun BuildResult.printed(name: String): String? =
        output.lineSequence().firstOrNull { it.startsWith("$name=") }?.substringAfter('=')
}
