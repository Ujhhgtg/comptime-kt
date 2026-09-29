package dev.ujhhgtg.comptime.compiler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class BlockSemanticsTest {
    private val lib get() = sharedLib

    companion object {
        private val sharedLib by lazy { compileLib() }

        private fun compileLib() = Harness.compile(
        mapOf("Lib.kt" to """
            package lib

            const val LIB_NAME = "lib\${'$'}name"
            const val LIB_SIZE = 3

            object Limits {
                const val MAX: Long = 1L shl 40
            }

            class Holder {
                companion object {
                    const val FLAG = true
                }
            }

            inline fun inlinedTable(): List<Int> = dev.ujhhgtg.comptime.comptime { listOf(LIB_SIZE, LIB_SIZE * 2) }
        """.trimIndent()),
        moduleName = "lib",
    ).assertOk()
    }

    private fun compileMain(vararg files: Pair<String, String>, options: Map<String, String> = emptyMap(), multiOptions: List<Pair<String, String>> = emptyList()) =
        Harness.compile(files.toMap(), classpath = listOf(lib.classes), options = options, multiOptions = multiOptions).assertOk()

    @Test
    fun `consts from this module, other modules and Java are spliced`() {
        val r = compileMain(
            "Main.kt" to """
                package app

                import dev.ujhhgtg.comptime.comptime
                import lib.LIB_NAME
                import lib.Limits
                import lib.Holder

                const val GREETING = "hi"
                const val SMALL: Byte = -3
                const val USMALL: UByte = 200u
                const val DOLLAR = "\${'$'}notATemplate"

                object Local { const val NEG = -5 }

                fun sameModule() = comptime { GREETING + "!" }
                fun otherModule() = comptime { LIB_NAME.length + lib.LIB_SIZE }
                fun qualified() = comptime { Limits.MAX + lib.Limits.MAX + (if (Holder.FLAG && lib.Holder.Companion.FLAG) 1 else 0) }
                fun javaConst() = comptime { JavaConsts.ANSWER + Integer.MAX_VALUE.toLong() }
                fun template() = comptime { "${'$'}GREETING/${'$'}{GREETING}/${'$'}{Limits.MAX}/${'$'}${'$'}GREETING" }
                fun multiDollar() = comptime { ${'$'}${'$'}"${'$'}GREETING ${'$'}${'$'}GREETING" }
                fun typed() = comptime { listOf(SMALL) }
                fun unsignedConst() = comptime { listOf(USMALL) }
                fun minus() = comptime { 10-Local.NEG }
                fun dollarString() = comptime { DOLLAR }
                fun stdlibConsts() = comptime { listOf(Int.MIN_VALUE.toLong(), Long.MIN_VALUE, Char.MAX_VALUE.code.toLong()) }
                fun nanConst() = comptime { Double.NaN.isNaN() && kotlin.Float.POSITIVE_INFINITY.isInfinite() }
            """.trimIndent(),
            "JavaConsts.java" to """
                package app;
                public class JavaConsts { public static final int ANSWER = 42; }
            """.trimIndent(),
        )
        val l = r.loader()
        fun call(name: String) = r.call(name, "app.MainKt", l)
        assertEquals("hi!", call("sameModule"))
        assertEquals("lib\$name".length + 3, call("otherModule"))
        assertEquals((1L shl 41) + 1, call("qualified"))
        assertEquals(42L + Int.MAX_VALUE, call("javaConst"))
        assertEquals("hi/hi/${1L shl 40}/\$hi", call("template"))
        assertEquals("\$GREETING hi", call("multiDollar"))
        assertEquals(listOf((-3).toByte()), call("typed"))
        assertEquals(listOf(200u.toUByte()).toString(), call("unsignedConst").toString())
        assertEquals(15, call("minus"))
        assertEquals("\$notATemplate", call("dollarString"))
        assertEquals(listOf(Int.MIN_VALUE.toLong(), Long.MIN_VALUE, 0xFFFFL), call("stdlibConsts"))
        assertEquals(true, call("nanConst"))
    }

    @Test
    fun `CRLF sources, labels, nested blocks and multi-line lambdas`() {
        val source = """
            import dev.ujhhgtg.comptime.comptime

            fun labelled() = comptime {
                if (System.getProperty("java.version").isNotEmpty()) return@comptime "early"
                "late"
            }

            fun customLabel() = comptime outer@{
                listOf(1, 2, 3).forEach { if (it == 2) return@outer it * 10 }
                0
            }

            fun parenthesized() = comptime({ "paren" })

            fun named() = comptime(block = { "named" })

            fun explicitType() = comptime<Long> { 7 }

            fun nested() = comptime { comptime { 20 } + comptime { 1 } * 2 }

            fun qualifiedCall() = dev.ujhhgtg.comptime.comptime { "fq" }

            fun localDeclarations() = comptime {
                data class P(val x: Int)
                fun twice(p: P) = P(p.x * 2)
                val ps = buildList { repeat(3) { add(twice(P(it))) } }
                ps.map { it.x }
            }
        """.trimIndent().replace("\n", "\r\n")
        val r = compileMain("Main.kt" to source)
        val l = r.loader()
        fun call(name: String) = r.call(name, loader = l)
        assertEquals("early", call("labelled"))
        assertEquals(20, call("customLabel"))
        assertEquals("paren", call("parenthesized"))
        assertEquals("named", call("named"))
        assertEquals(7L, call("explicitType"))
        assertEquals(22, call("nested"))
        assertEquals("fq", call("qualifiedCall"))
        assertEquals(listOf(0, 2, 4), call("localDeclarations"))
    }

    @Test
    fun `line numbers map back on CRLF files`() {
        val source = "import dev.ujhhgtg.comptime.comptime\r\n\r\nfun f(): Int = comptime {\r\n    val x = 1\r\n    if (x == 1) error(\"boom \$x\")\r\n    x\r\n}\r\n"
        val r = Harness.compile(mapOf("Main.kt" to source))
        val error = r.errors.single()
        assertTrue("Main.kt:5 (comptime block)" in error.text, error.text)
        assertEquals(3, error.line)
    }

    @Test
    fun `imports are filtered to what the block uses`() {
        val r = compileMain(
            "Main.kt" to """
                import dev.ujhhgtg.comptime.comptime
                import java.io.File as JFile
                import java.util.UUID
                import java.util.concurrent.*
                import lib.LIB_NAME
                import kotlin.math.PI

                fun f() = comptime { JFile("x").name + ConcurrentHashMap<String, Int>().size + LIB_NAME }
            """.trimIndent(),
        )
        assertEquals("x0lib\$name", r.call("f"))
        val synthetic = File(r.jobDir, "src/b0.kt").readText()
        assertTrue("import java.io.File as JFile" in synthetic, synthetic)
        assertTrue("import java.util.concurrent.*" in synthetic, synthetic)
        assertFalse("UUID" in synthetic, synthetic)
        assertFalse("import lib" in synthetic, synthetic)
        assertFalse("PI" in synthetic, synthetic)
    }

    @Test
    fun `declared env vars are forced onto the host`() {
        val r = compileMain(
            "Main.kt" to """
                import dev.ujhhgtg.comptime.comptime
                fun flavor() = comptime { System.getenv("COMPTIME_FLAVOR") }
                fun unset() = comptime { System.getenv("PATH") }
            """.trimIndent(),
            multiOptions = listOf("env" to "COMPTIME_FLAVOR=prod", "envUnset" to "PATH"),
        )
        assertEquals("prod", r.call("flavor"))
        assertEquals(null, r.call("unset"))
    }

    @Test
    fun `blocks read files relative to the module directory`() {
        val dir = Files.createTempDirectory("comptime-module").toFile()
        File(dir, "data.txt").writeText("from disk")
        val r = Harness.compile(
            mapOf("Main.kt" to "import dev.ujhhgtg.comptime.comptime\nfun f() = comptime { java.io.File(\"data.txt\").readText() }"),
            options = mapOf("moduleDir" to dir.absolutePath),
        ).assertOk()
        assertEquals("from disk", r.call("f"))
    }

    @Test
    fun `values inside public inline functions are built in place`() {
        // lib.inlinedTable() was evaluated when lib was built; calling it from here needs no holder class of lib's.
        val r = compileMain(
            "Main.kt" to """
                fun viaInline() = lib.inlinedTable()
                internal inline fun internalInline() = dev.ujhhgtg.comptime.comptime { setOf("a") }
                fun viaInternal() = internalInline()
            """.trimIndent(),
        )
        assertEquals(listOf(3, 6), r.call("viaInline"))
        assertEquals(setOf("a"), r.call("viaInternal"))
        assertTrue(lib.classes.walk().none { it.name.contains("\$comptime\$") }, "no holder class for the inline function")
    }
}
