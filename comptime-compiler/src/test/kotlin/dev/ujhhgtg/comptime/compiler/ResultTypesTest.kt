package dev.ujhhgtg.comptime.compiler

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/** Every supported result type in one module, so the host runs once. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResultTypesTest {
    private lateinit var result: Harness.Result
    private lateinit var loader: ClassLoader

    @BeforeAll
    fun compile() {
        result = Harness.compile(mapOf("Main.kt" to """
            import dev.ujhhgtg.comptime.comptime

            fun bool() = comptime { 1 < 2 }
            fun byte(): Byte = comptime { (-7).toByte() }
            fun short(): Short = comptime { 30000.toShort() }
            fun int() = comptime { Int.MIN_VALUE }
            fun long() = comptime { Long.MIN_VALUE }
            fun float() = comptime { 1.5f }
            fun floatNaN() = comptime { Float.NaN }
            fun double() = comptime { -0.0 }
            fun doubleInf() = comptime { Double.NEGATIVE_INFINITY }
            fun char() = comptime { '\u0000' }
            fun string() = comptime { "tab\t dollar${'$'} quote\" 😀 " + "\uD800" }
            fun bigString() = comptime { "x".repeat(200_000) }
            fun ubyte() = comptime { 255u.toUByte() }
            fun ushort() = comptime { 65535u.toUShort() }
            fun uint() = comptime { UInt.MAX_VALUE }
            fun ulong() = comptime { ULong.MAX_VALUE }
            fun nullString(): String? = comptime { null }
            fun nullableInt(): Int? = comptime { 5 }
            fun unit() = comptime { check(1 + 1 == 2) }

            fun list() = comptime { (0 until 256).map { n ->
                var c = n
                repeat(8) { c = if (c and 0x80 != 0) (c shl 1) xor 0x07 else c shl 1 }
                c and 0xFF
            } }
            fun set() = comptime { linkedSetOf("c", "a", "b") as Set<String> }
            fun map() = comptime { mapOf("z" to listOf(1), "a" to listOf(2, 3)) }
            fun emptyList() = comptime { listOf<String>() }
            fun emptyMap() = comptime { emptyMap<Int, Int>() }
            fun nested() = comptime { mapOf(1 to setOf(listOf<Int?>(null, 1))) }
            fun nullableList(): List<String>? = comptime { null }
            fun listOfUInts() = comptime { listOf(1u, 2u) }
            fun array() = comptime { arrayOf("a", "b") }
            fun arrayOfArrays() = comptime { arrayOf(intArrayOf(1), intArrayOf(2, 3)) }
            fun intArray() = comptime { IntArray(5) { it * it } }
            fun charArray() = comptime { "hi".toCharArray() }
            fun boolArray() = comptime { booleanArrayOf(true, false) }
            fun doubleArray() = comptime { doubleArrayOf(0.5, Double.NaN) }
            fun listOfArrays() = comptime { listOf(byteArrayOf(1, 2)) }
            fun files() = comptime { System.getProperty("user.dir") }
            fun platformType() = comptime { System.getenv("COMPTIME_SURELY_UNSET_VARIABLE") }
            fun javaList() = comptime { java.util.Arrays.asList("x", "y") }
        """.trimIndent())).assertOk()
        loader = result.loader()
    }

    private fun call(name: String) = result.call(name, loader = loader)

    @Test fun scalars() {
        assertEquals(true, call("bool"))
        assertEquals((-7).toByte(), call("byte"))
        assertEquals(30000.toShort(), call("short"))
        assertEquals(Int.MIN_VALUE, call("int"))
        assertEquals(Long.MIN_VALUE, call("long"))
        assertEquals(1.5f, call("float"))
        assertTrue((call("floatNaN") as Float).isNaN())
        assertEquals((-0.0).toRawBits(), (call("double") as Double).toRawBits())
        assertEquals(Double.NEGATIVE_INFINITY, call("doubleInf"))
        assertEquals('\u0000', call("char"))
        assertEquals("tab\t dollar$ quote\" 😀 \uD800", call("string"))
        assertEquals(200_000, (call("bigString") as String).length)
    }

    @Test fun unsigned() {
        // Unsigned results are inline classes: the JVM signature carries the underlying signed value.
        assertEquals((-1).toByte(), call("ubyte"))
        assertEquals((-1).toShort(), call("ushort"))
        assertEquals(-1, call("uint"))
        assertEquals(-1L, call("ulong"))
        assertEquals(listOf(1u, 2u).toString(), call("listOfUInts").toString())
    }

    @Test fun nullsAndUnit() {
        assertNull(call("nullString"))
        assertEquals(5, call("nullableInt"))
        assertNull(call("nullableList"))
        assertNull(call("unit").takeIf { it != Unit }) // void method
    }

    @Test fun collections() {
        val crc = call("list") as List<*>
        assertEquals(256, crc.size)
        assertEquals(0x07, crc[1])
        assertEquals(listOf("c", "a", "b"), (call("set") as Set<*>).toList())
        assertEquals(listOf("z", "a"), (call("map") as Map<*, *>).keys.toList())
        assertEquals(mapOf("z" to listOf(1), "a" to listOf(2, 3)), call("map"))
        assertEquals(emptyList<String>(), call("emptyList"))
        assertEquals(emptyMap<Int, Int>(), call("emptyMap"))
        assertEquals(mapOf(1 to setOf(listOf(null, 1))), call("nested"))
        assertEquals(listOf("x", "y"), call("javaList"))
    }

    @Test fun arrays() {
        assertArrayEquals(arrayOf("a", "b"), call("array") as Array<*>)
        val nested = call("arrayOfArrays") as Array<*>
        assertArrayEquals(intArrayOf(2, 3), nested[1] as IntArray)
        assertArrayEquals(intArrayOf(0, 1, 4, 9, 16), call("intArray") as IntArray)
        assertArrayEquals(charArrayOf('h', 'i'), call("charArray") as CharArray)
        assertArrayEquals(booleanArrayOf(true, false), call("boolArray") as BooleanArray)
        val doubles = call("doubleArray") as DoubleArray
        assertEquals(0.5, doubles[0])
        assertTrue(doubles[1].isNaN())
        assertArrayEquals(byteArrayOf(1, 2), (call("listOfArrays") as List<*>)[0] as ByteArray)
    }

    @Test fun `collections are built once, arrays fresh on each evaluation`() {
        assertSame(call("list"), call("list"))
        assertSame(call("map"), call("map"))
        assertNotSame(call("intArray"), call("intArray"))
        assertNotSame(call("listOfArrays"), call("listOfArrays"))
    }

    @Test fun `blocks run in the module directory`() {
        assertEquals(result.workDir.canonicalPath, java.io.File(call("files") as String).canonicalPath)
    }

    @Test fun `platform types accept null`() {
        assertNull(call("platformType"))
    }

    @Test fun `holder classes are synthetic and private`() {
        val holder = loader.loadClass("Main\$comptime\$0")
        assertTrue(holder.isSynthetic)
        assertFalse(java.lang.reflect.Modifier.isPublic(holder.modifiers))
    }
}
