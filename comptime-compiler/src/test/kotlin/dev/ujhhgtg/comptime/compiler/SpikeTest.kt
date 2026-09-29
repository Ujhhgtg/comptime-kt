package dev.ujhhgtg.comptime.compiler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpikeTest {
    @Test
    fun `6 times 7 is baked as 42`() {
        val result = Harness.compile(mapOf("Main.kt" to """
            import dev.ujhhgtg.comptime.comptime

            fun answer(): Int = comptime { 6 * 7 }
        """.trimIndent())).assertOk()
        assertEquals(42, result.call("answer"))
    }
}
