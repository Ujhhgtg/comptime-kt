package dev.ujhhgtg.comptime.compiler

import dev.ujhhgtg.comptime.protocol.JobLayout
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/** Phase 5: big values, the result cache, block output, and the in-process compile. */
class PolishTest {
    @Test
    fun `values too big for one method are split across helpers`() {
        val r = Harness.compile(mapOf("Main.kt" to """
            import dev.ujhhgtg.comptime.comptime

            fun bigList() = comptime { List(100_000) { it % 30_000 } }
            fun bigStrings() = comptime { List(20_000) { "s${'$'}it" } }
            fun bigMap() = comptime { (0 until 20_000).associate { it to it.toString() } }
            fun bigSet() = comptime { (0 until 30_000).toSet() }
            fun bigIntArray() = comptime { IntArray(200_000) { it % 1000 } }
            fun bigArray() = comptime { Array(30_000) { it.toString() } }
            fun nested() = comptime { listOf(listOf(1), List(50_000) { it % 7 }, listOf(2)) }
        """.trimIndent())).assertOk()
        val l = r.loader()
        fun call(name: String) = r.call(name, loader = l)
        assertEquals(List(100_000) { it % 30_000 }, call("bigList"))
        assertEquals(List(20_000) { "s$it" }, call("bigStrings"))
        assertEquals((0 until 20_000).associate { it to it.toString() }, call("bigMap"))
        assertEquals((0 until 30_000).toList(), (call("bigSet") as Set<*>).toList())
        assertArrayEquals(IntArray(200_000) { it % 1000 }, call("bigIntArray") as IntArray)
        assertArrayEquals(Array(30_000) { it.toString() }, call("bigArray") as Array<*>)
        assertEquals(listOf(listOf(1), List(50_000) { it % 7 }, listOf(2)), call("nested"))

        // The list's holder carries part helpers; no method in any generated class reaches the JVM's 64 KB limit.
        val holder = l.loadClass("Main\$comptime\$0")
        assertTrue(holder.declaredMethods.count { it.name.startsWith("part\$") } > 1, holder.declaredMethods.joinToString { it.name })
    }

    @Test
    fun `the result cache skips blocks whose key is unchanged`() {
        val module = Files.createTempDirectory("comptime-cache-module").toFile()
        val cache = File(module, "cache")
        val source = """
            import dev.ujhhgtg.comptime.comptime
            fun counted() = comptime {
                java.io.File("runs.log").appendText("x")
                System.getenv("COMPTIME_CACHE_FLAVOR") ?: "none"
            }
        """.trimIndent()
        fun compile(env: String) = Harness.compile(
            mapOf("Main.kt" to source),
            options = mapOf("moduleDir" to module.absolutePath, "cacheDir" to cache.absolutePath),
            multiOptions = listOf("env" to "COMPTIME_CACHE_FLAVOR=$env"),
        ).assertOk()
        val runs = { File(module, "runs.log").readText().length }

        assertEquals("mint", compile("mint").call("counted"))
        assertEquals(1, runs())
        val hit = compile("mint")
        assertEquals("mint", hit.call("counted"))
        assertEquals(1, runs(), "a cache hit doesn't run the block")
        assertTrue(!hit.jobDir.exists(), "a job where every block hit the cache doesn't start the host")

        // A declared env value is part of the key.
        assertEquals("lime", compile("lime").call("counted"))
        assertEquals(2, runs())
    }

    @Test
    fun `output of a successful block is reported at info level`() {
        val r = Harness.compile(mapOf("Main.kt" to """
            import dev.ujhhgtg.comptime.comptime
            fun f() = comptime {
                println("generated 3 entries")
                System.err.println("careful")
                3
            }
            fun quiet() = comptime { 4 }
        """.trimIndent())).assertOk()
        val info = r.messages.filter { it.severity == CompilerMessageSeverity.INFO && "comptime block printed" in it.text }
        assertEquals(1, info.size, r.messages.joinToString("\n"))
        assertTrue("generated 3 entries" in info.single().text && "careful" in info.single().text, info.single().text)
        assertEquals(2, info.single().line)
    }

    @Test
    fun `blocks compile in-process by default, and in the host when asked`() {
        val source = mapOf("Main.kt" to "import dev.ujhhgtg.comptime.comptime\nfun f() = comptime { listOf(1, 2) }")

        val inProcess = Harness.compile(source).assertOk()
        assertEquals(listOf(1, 2), inProcess.call("f"))
        assertTrue("\"precompiled\": true" in File(inProcess.jobDir, "manifest.json").readText())

        val inHost = Harness.compile(source, options = mapOf("inProcessCompile" to "false")).assertOk()
        assertEquals(listOf(1, 2), inHost.call("f"))
        assertTrue("\"precompiled\": false" in File(inHost.jobDir, "manifest.json").readText())
        assertTrue(JobLayout(inHost.jobDir).compileResult.isFile)
    }
}
