package dev.ujhhgtg.comptime.compiler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class ErrorsTest {
    private fun errorsOf(source: String, options: Map<String, String> = emptyMap(), extra: Map<String, String> = emptyMap()): List<Harness.Message> {
        val r = Harness.compile(mapOf("Main.kt" to source) + extra, options = options)
        assertFalse(r.ok, "expected a compile error")
        return r.errors
    }

    private fun List<Harness.Message>.assertOne(vararg fragments: String, line: Int? = null): Harness.Message {
        val matching = filter { m -> fragments.all { it in m.text } && (line == null || m.line == line) }
        assertEquals(1, matching.size, "expected one error containing ${fragments.toList()}, got:\n" + joinToString("\n"))
        if (line != null) assertEquals(line, matching.single().line, matching.single().toString())
        return matching.single()
    }

    @Test
    fun `forbidden references are rejected where they occur, before anything runs`() {
        val errors = errorsOf("""
            package app

            import dev.ujhhgtg.comptime.comptime

            fun <T> listOf(vararg xs: T): List<T> = xs.toList().reversed()
            fun helper() = 1
            class Box(val v: Int) {
                fun viaThis() = comptime { v + 1 }
            }
            fun <T> generic(): String = comptime { listOf<T>().toString() }

            fun shadowed() = comptime { listOf(1, 2) }
            fun project() = comptime { helper() + 1 }
            fun captured(n: Int): Int {
                val local = n * 2
                return comptime { local }
            }
            fun projectType() = comptime { Box(1).toString() }
        """.trimIndent())
        errors.assertOne("'app.listOf'", "declared in this module", line = 12)
        errors.assertOne("'app.helper'", line = 13)
        errors.assertOne("can't capture 'local'", line = 16)
        errors.assertOne("'app.Box'", line = 18)
        errors.assertOne("outer 'this'", line = 8)
        errors.assertOne("type parameter 'T'", line = 10)
        // Nothing was sent to the host: no job directory.
        assertTrue(errors.none { "host" in it.text })
    }

    @Test
    fun `block shape and result type are checked`() {
        val errors = errorsOf("""
            import dev.ujhhgtg.comptime.comptime
            import dev.ujhhgtg.comptime.comptime as ct

            val lambdaVar = { 1 }
            fun f() = 2
            fun notALambda() = comptime(lambdaVar)
            fun reference() = comptime(::f)
            fun anonymous() = comptime(fun(): Int { return 3 })
            fun aliased() = ct { 4 }
            fun number(): Number = comptime<Number> { 5 }
            fun anyList() = comptime { listOf<Any>(1) }
            fun mutable() = comptime { mutableListOf(1) }
            fun nothing(): Int = comptime { throw IllegalStateException() }
        """.trimIndent())
        errors.assertOne("needs a lambda literal", line = 6)
        errors.assertOne("needs a lambda literal", line = 7)
        errors.assertOne("needs a lambda literal", line = 8)
        errors.assertOne("import aliases aren't supported", line = 9)
        errors.assertOne("kotlin.Number", line = 10)
        errors.assertOne("kotlin.Any", line = 11)
        errors.assertOne("read-only List", line = 12)
        errors.assertOne("kotlin.Nothing", line = 13)
    }

    @Test
    fun `an exception reports type, message, mapped stack trace and output`() {
        val errors = errorsOf("""
            import dev.ujhhgtg.comptime.comptime

            fun f(): Int = comptime {
                println("about to fail")
                System.err.println("on stderr")
                fun inner(): Int = throw IllegalArgumentException("bad input", RuntimeException("root"))
                inner()
            }
        """.trimIndent())
        val e = errors.assertOne("comptime block threw java.lang.IllegalArgumentException: bad input", line = 3)
        assertTrue("Main.kt:6 (comptime block)" in e.text, e.text)
        assertTrue("Main.kt:7 (comptime block)" in e.text, e.text)
        assertEquals(2, Regex("""\(comptime block\)""").findAll(e.text).count(), e.text) // no synthetic scaffolding frames
        assertTrue("caused by java.lang.RuntimeException: root" in e.text, e.text)
        assertTrue("about to fail" in e.text && "on stderr" in e.text, e.text)
    }

    @Test
    fun `a value that doesn't match the declared type names the path`() {
        val errors = errorsOf("""
            import dev.ujhhgtg.comptime.comptime

            @Suppress("UNCHECKED_CAST")
            fun f() = comptime { mapOf("k" to listOf<String?>("a", null)) as Map<String, List<String>> }
        """.trimIndent())
        errors.assertOne("doesn't match", "at [\"k\"][1]", "got null", line = 4)
    }

    @Test
    fun `a host compile error is mapped back to the original line`() {
        // The typealias expands to kotlin.String in IR, so the reference check allows it; the host can't see it.
        val errors = errorsOf("""
            import dev.ujhhgtg.comptime.comptime

            typealias Text = String

            fun f() = comptime {
                val t: Text = "x"
                t
            }
        """.trimIndent())
        errors.assertOne("doesn't compile against the JDK and kotlin-stdlib", "Text", line = 6)
    }

    @Test
    fun `System exit and timeouts name the block that was running`() {
        val errors = errorsOf("""
            import dev.ujhhgtg.comptime.comptime

            fun ok() = comptime { 1 }
            fun exits(): Int = comptime { System.exit(0); 2 }
            fun later() = comptime { 3 }
        """.trimIndent())
        errors.assertOne("did it call System.exit", line = 4)
        errors.assertOne("never ran", line = 5)

        val timeout = errorsOf("""
            import dev.ujhhgtg.comptime.comptime

            fun slow() = comptime { Thread.sleep(120_000); 1 }
            fun after() = comptime { 2 }
        """.trimIndent(), options = mapOf("timeout" to "8"))
        timeout.assertOne("timed out after 8 s while running this block", line = 3)
        timeout.assertOne("never ran", "Main.kt:3", line = 4)
    }

    @Test
    fun `results over the size limit are rejected`() {
        val errors = errorsOf("""
            import dev.ujhhgtg.comptime.comptime
            fun big() = comptime { List(5000) { it } }
            fun small() = comptime { List(10) { it } }
        """.trimIndent(), options = mapOf("sizeLimit" to "8192"))
        errors.assertOne("too large", line = 2)
        assertEquals(1, errors.size, errors.joinToString("\n"))
    }

    @Test
    fun `a large table under the default limit compiles and runs`() {
        val r = Harness.compile(mapOf("Main.kt" to """
            import dev.ujhhgtg.comptime.comptime
            fun a() = comptime { List(2500) { it * 3 } }
            fun b() = comptime { List(2500) { it.toString() } }
            fun c() = comptime { List(2500) { it.toLong() } }
        """.trimIndent())).assertOk()
        val l = r.loader()
        assertEquals(7497, (r.call("a", loader = l) as List<*>).last())
        assertEquals("2499", (r.call("b", loader = l) as List<*>).last())
        assertEquals(2499L, (r.call("c", loader = l) as List<*>).last())
        assertTrue(File(r.classes, "Main\$comptime\$2.class").isFile)
    }
}
