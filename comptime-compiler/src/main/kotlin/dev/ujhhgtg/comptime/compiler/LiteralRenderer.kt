package dev.ujhhgtg.comptime.compiler

import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstKind
import org.jetbrains.kotlin.ir.types.classFqName

/**
 * Renders constant values as Kotlin source that has the same type and value in any context. Every rendering is
 * parenthesized, so a splice into `x-FOO` can't produce `x--1`. See docs/plan.md, "Const splicing".
 */
object LiteralRenderer {
    fun render(const: IrConst): String {
        val value = const.value
        return when (const.type.classFqName?.asString()) {
            "kotlin.UByte" -> "(${(value as Byte).toUByte()}u.toUByte())"
            "kotlin.UShort" -> "(${(value as Short).toUShort()}u.toUShort())"
            "kotlin.UInt" -> "(${(value as Int).toUInt()}u)"
            "kotlin.ULong" -> "(${(value as Long).toULong()}uL)"
            else -> when (const.kind) {
                IrConstKind.Null -> "(null)"
                IrConstKind.Boolean -> "($value)"
                IrConstKind.Char -> "(${char(value as Char)})"
                IrConstKind.Byte -> "((${value}).toByte())"
                IrConstKind.Short -> "((${value}).toShort())"
                IrConstKind.Int -> int(value as Int)
                IrConstKind.Long -> long(value as Long)
                IrConstKind.String -> "(${string(value as String)})"
                IrConstKind.Float -> float(value as Float)
                IrConstKind.Double -> double(value as Double)
            }
        }
    }

    fun int(v: Int): String = if (v == Int.MIN_VALUE) "(-2147483647 - 1)" else "($v)"

    fun long(v: Long): String = if (v == Long.MIN_VALUE) "(-9223372036854775807L - 1L)" else "(${v}L)"

    fun float(v: Float): String {
        val text = v.toString()
        return if (v.isFinite() && text.toFloat().toRawBits() == v.toRawBits()) "(${text}f)"
        else "(Float.fromBits(${v.toRawBits()}))"
    }

    fun double(v: Double): String {
        val text = v.toString()
        return if (v.isFinite() && text.toDouble().toRawBits() == v.toRawBits()) "($text)"
        else "(Double.fromBits(${v.toRawBits()}L))"
    }

    fun char(c: Char): String = "'" + escape(c, quote = '\'') + "'"

    fun string(s: String): String = buildString {
        append('"')
        for (c in s) append(escape(c, quote = '"'))
        append('"')
    }

    private fun escape(c: Char, quote: Char): String = when {
        c == quote -> "\\$c"
        c == '\\' -> "\\\\"
        c == '$' -> "\\$"
        c == '\n' -> "\\n"
        c == '\r' -> "\\r"
        c == '\t' -> "\\t"
        c == '\b' -> "\\b"
        c.code < 0x20 || c.code == 0x7f || c.isSurrogate() || !c.isDefined() || c.category == CharCategory.FORMAT ->
            "\\u%04x".format(c.code)
        else -> c.toString()
    }
}
