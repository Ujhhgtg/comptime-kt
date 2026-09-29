package dev.ujhhgtg.comptime.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Phase 4: blocks run on the compile task's JDK by default, or on the JDK pinned with `comptime { jdk }`. Needs JDK
 * 17 and 21 installed where Gradle's toolchain detection finds them.
 */
class JdkPinningTest : FixtureTest() {
    private fun fixture(comptimeConfig: String, mainSource: String) {
        settings(":app")
        write("build.gradle.kts", """
            plugins {
                kotlin("jvm") version "$kotlinVersion" apply false
                id("dev.ujhhgtg.comptime") version "$version" apply false
            }
        """)
        write("app/build.gradle.kts", """
            plugins {
                kotlin("jvm")
                id("dev.ujhhgtg.comptime")
                application
            }
            kotlin { jvmToolchain(17) }
            application { mainClass.set("app.MainKt") }
            comptime { $comptimeConfig }
        """)
        write("app/src/main/kotlin/app/Main.kt", mainSource)
    }

    private val versionMain = """
        package app

        import dev.ujhhgtg.comptime.comptime

        val feature = comptime { Runtime.version().feature() }

        fun main() = println("feature=${'$'}feature")
    """

    @Test
    fun `blocks run on the compile JDK by default`() {
        fixture("", versionMain)
        val result = gradle(":app:run", "--info")
        assertEquals("17", result.printed("feature"))
        assertTrue(Regex("""Kotlin compilation 'jdkHome' argument: \S*17""").containsMatchIn(result.output), result.output)
    }

    @Test
    fun `a pinned JDK runs blocks while the toolchain stays on 17`() {
        fixture("jdk.set(JavaLanguageVersion.of(21))", versionMain)
        assertEquals("21", gradle(":app:run").printed("feature"))

        // Changing the pinned JDK is a task input change: the compile reruns and rebakes.
        write("app/build.gradle.kts", read("app/build.gradle.kts").replace("JavaLanguageVersion.of(21)", "JavaLanguageVersion.of(17)"))
        assertEquals("17", gradle(":app:run").printed("feature"))
    }

    @Test
    fun `an API newer than the compile JDK fails in the main compile`() {
        fixture("jdk.set(JavaLanguageVersion.of(21))", """
            package app

            import dev.ujhhgtg.comptime.comptime

            // Math.clamp is new in JDK 21; the module compiles against 17, so the main compile rejects it.
            val clamped = comptime { Math.clamp(5L, 0, 3) }

            fun main() = println(clamped)
        """)
        val result = gradleFails(":app:compileKotlin")
        assertTrue("Unresolved reference 'clamp'" in result.output, result.output)
    }
}
