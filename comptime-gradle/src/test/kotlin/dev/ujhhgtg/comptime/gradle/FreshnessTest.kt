package dev.ujhhgtg.comptime.gradle

import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Phase 3: declared `inputs` and `env` keep baked values fresh. */
class FreshnessTest : FixtureTest() {
    private fun fixture() {
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
            application { mainClass.set("app.MainKt") }
            comptime {
                inputs.from("data/", "schema.sql")
                env.add("COMPTIME_FLAVOR")
            }
        """)
        write("app/src/main/kotlin/app/Main.kt", """
            package app

            import dev.ujhhgtg.comptime.comptime

            val schema = comptime { java.io.File("schema.sql").readText().trim() }
            val words = comptime { java.io.File("data").listFiles()!!.sortedBy { it.name }.map { it.readText().trim() } }
            val flavor = comptime { System.getenv("COMPTIME_FLAVOR") ?: "none" }

            fun main() {
                println("schema=${'$'}schema")
                println("words=${'$'}words")
                println("flavor=${'$'}flavor")
            }
        """)
        write("app/src/main/kotlin/app/Other.kt", "package app\nfun other() = 1")
        write("app/schema.sql", "create table a (x int);")
        write("app/data/1.txt", "alpha")
    }

    @Test
    fun `declared files and env vars rebake values, on the same Kotlin daemon`() {
        fixture()
        val first = gradle(":app:run", "--info", env = mapOf("COMPTIME_FLAVOR" to "vanilla"))
        assertEquals("create table a (x int);", first.printed("schema"))
        assertEquals("[alpha]", first.printed("words"))
        assertEquals("vanilla", first.printed("flavor"))

        // A declared file changes: the compile reruns and the block sees the new content.
        write("app/schema.sql", "create table b (y int);")
        val edited = gradle(":app:run", env = mapOf("COMPTIME_FLAVOR" to "vanilla"))
        assertEquals(TaskOutcome.SUCCESS, edited.task(":app:compileKotlin")?.outcome)
        assertEquals("create table b (y int);", edited.printed("schema"))

        // A file added to a declared directory counts too.
        write("app/data/2.txt", "beta")
        assertEquals("[alpha, beta]", gradle(":app:run", env = mapOf("COMPTIME_FLAVOR" to "vanilla")).printed("words"))

        // A declared env var changes between builds. The Kotlin daemon from the first build is reused (it isn't
        // started again) and still has the old environment, yet the block sees the new value.
        val flavored = gradle(":app:run", "--info", env = mapOf("COMPTIME_FLAVOR" to "choco,late 100%"))
        assertEquals("choco,late 100%", flavored.printed("flavor"))
        assertFalse("starting the daemon as" in flavored.output, "the Kotlin daemon should have been reused")
        assertTrue("connected to the daemon" in flavored.output || "Options for KOTLIN DAEMON" in flavored.output, flavored.output)

        // Unset is a value too.
        assertEquals("none", gradle(":app:run", env = emptyMap()).printed("flavor"))

        // Nothing changed: up to date.
        val again = gradle(":app:run", env = emptyMap())
        assertEquals(TaskOutcome.UP_TO_DATE, again.task(":app:compileKotlin")?.outcome)
    }
}
