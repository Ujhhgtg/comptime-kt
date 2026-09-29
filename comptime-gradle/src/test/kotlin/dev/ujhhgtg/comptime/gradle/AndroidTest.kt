package dev.ujhhgtg.comptime.gradle

import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Phase 4: an Android library module with blocks in it, built with AGP's built-in Kotlin. Needs an Android SDK
 * (`ANDROID_HOME`, or `/opt/android-sdk`) with the platform [COMPILE_SDK] installed; skipped otherwise.
 */
class AndroidTest : FixtureTest() {
    private val sdk: File? = (System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: "/opt/android-sdk")
        .let(::File).takeIf { File(it, "platforms/android-$COMPILE_SDK").isDirectory }

    @Test
    fun `an Android library module bakes values`() {
        assumeTrue(sdk != null, "no Android SDK with platform $COMPILE_SDK")
        settings(":android", google = true)
        write("local.properties", "sdk.dir=${sdk!!.absolutePath.replace("\\", "/")}")
        write("build.gradle.kts", """
            plugins {
                id("com.android.library") version "$AGP_VERSION" apply false
                kotlin("jvm") version "$kotlinVersion" apply false
                id("dev.ujhhgtg.comptime") version "$version" apply false
            }
        """)
        write("android/build.gradle.kts", """
            plugins {
                id("com.android.library")
                id("dev.ujhhgtg.comptime")
            }
            android {
                namespace = "dev.fixture.android"
                compileSdk = $COMPILE_SDK
                defaultConfig { minSdk = 24 }
            }
            dependencies { testImplementation("junit:junit:4.13.2") }
            comptime { inputs.from("words.txt") }
        """)
        write("android/words.txt", "apple\nbanana")
        write("android/src/main/kotlin/dev/fixture/android/Values.kt", """
            package dev.fixture.android

            import dev.ujhhgtg.comptime.comptime

            const val BASE = 40

            fun answer() = comptime { BASE + 2 }
            fun squares() = comptime { (0 until 4).map { it * it } }
            fun words() = comptime { java.io.File("words.txt").readLines().map { it.uppercase() } }
        """)
        write("android/src/test/kotlin/dev/fixture/android/ValuesTest.kt", """
            package dev.fixture.android

            import org.junit.Assert.assertEquals
            import org.junit.Test

            class ValuesTest {
                @Test fun baked() {
                    assertEquals(42, answer())
                    assertEquals(listOf(0, 1, 4, 9), squares())
                    assertEquals(listOf("APPLE", "BANANA"), words())
                }
            }
        """)

        val result = gradle(":android:testDebugUnitTest")
        assertEquals(TaskOutcome.SUCCESS, result.task(":android:testDebugUnitTest")?.outcome)
        // AGP's built-in Kotlin compiled the module; the two collections live in holder classes.
        val classes = File(dir, "android/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/dev/fixture/android")
        assertTrue(File(classes, "Values\$comptime\$0.class").isFile && File(classes, "Values\$comptime\$1.class").isFile, classes.list().orEmpty().joinToString())
    }

    private companion object {
        const val AGP_VERSION = "9.4.1"
        const val COMPILE_SDK = 36
    }
}
