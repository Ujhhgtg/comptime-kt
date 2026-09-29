package dev.ujhhgtg.comptime.compiler

import java.io.ByteArrayInputStream
import java.io.DataInputStream

/** A decoded comptime result: the tagged tree written by the host's encoder. */
sealed class ResultValue {
    data object Null : ResultValue()
    data object UnitValue : ResultValue()
    data class Scalar(val kind: ScalarKind, val value: Any) : ResultValue()
    data class Sequence(val items: List<ResultValue>) : ResultValue()
    data class MapValue(val entries: List<Pair<ResultValue, ResultValue>>) : ResultValue()
    data class PrimitiveArray(val kind: ScalarKind, val values: List<Any>) : ResultValue()

    companion object {
        fun decode(bytes: ByteArray): ResultValue {
            val input = DataInputStream(ByteArrayInputStream(bytes))
            val value = read(input)
            if (input.read() != -1) throw IllegalStateException("trailing bytes in comptime result")
            return value
        }

        private fun read(input: DataInputStream): ResultValue = when (val tag = input.readUnsignedByte()) {
            0 -> Null
            1 -> Scalar(ScalarKind.BOOLEAN, input.readBoolean())
            2 -> Scalar(ScalarKind.BYTE, input.readByte())
            3 -> Scalar(ScalarKind.SHORT, input.readShort())
            4 -> Scalar(ScalarKind.INT, input.readInt())
            5 -> Scalar(ScalarKind.LONG, input.readLong())
            6 -> Scalar(ScalarKind.FLOAT, Float.fromBits(input.readInt()))
            7 -> Scalar(ScalarKind.DOUBLE, Double.fromBits(input.readLong()))
            8 -> Scalar(ScalarKind.CHAR, input.readChar())
            9 -> {
                val length = input.readInt()
                val chars = CharArray(length) { input.readChar() }
                Scalar(ScalarKind.STRING, String(chars))
            }
            10 -> Scalar(ScalarKind.UBYTE, input.readByte())
            11 -> Scalar(ScalarKind.USHORT, input.readShort())
            12 -> Scalar(ScalarKind.UINT, input.readInt())
            13 -> Scalar(ScalarKind.ULONG, input.readLong())
            14, 15, 17 -> Sequence(List(input.readInt()) { read(input) })
            16 -> MapValue(List(input.readInt()) { read(input) to read(input) })
            18 -> {
                val letter = input.readUnsignedByte()
                val kind = ScalarKind.entries.first { it.letter.code == letter }
                val size = input.readInt()
                val values: List<Any> = List(size) {
                    when (kind) {
                        ScalarKind.BOOLEAN -> input.readBoolean()
                        ScalarKind.BYTE -> input.readByte()
                        ScalarKind.SHORT -> input.readShort()
                        ScalarKind.INT -> input.readInt()
                        ScalarKind.LONG -> input.readLong()
                        ScalarKind.FLOAT -> Float.fromBits(input.readInt())
                        ScalarKind.DOUBLE -> Double.fromBits(input.readLong())
                        ScalarKind.CHAR -> input.readChar()
                        else -> throw IllegalStateException("bad primitive array kind $kind")
                    }
                }
                PrimitiveArray(kind, values)
            }
            19 -> UnitValue
            else -> throw IllegalStateException("bad tag $tag in comptime result")
        }

        /** Rough bytecode size of the code that rebuilds [value]; see docs/plan.md, "Size limit". */
        fun estimateBytecode(value: ResultValue): Long = when (value) {
            Null, UnitValue -> 3L
            is Scalar -> if (value.kind == ScalarKind.STRING) 3L else 3L + BOX
            // new array + per element: dup, index, value, store; then the call.
            is Sequence -> 12L + value.items.sumOf { 6L + estimateBytecode(it) }
            is MapValue -> 12L + value.entries.sumOf { (k, v) -> 16L + estimateBytecode(k) + estimateBytecode(v) }
            is PrimitiveArray -> 8L + value.values.size * 8L
        }

        private const val BOX = 3

        /**
         * Class-file constant pool entries [value] needs for its `ldc` operands: one per distinct int outside the
         * `sipush` range, char or float; two per distinct long, double or string.
         */
        fun constantPoolEntries(value: ResultValue): Int {
            // Keyed by kind and bits, so 1 and 1L or 0.0 and -0.0 count separately, as in the pool.
            val seen = HashMap<String, Int>()
            fun add(v: Any) {
                val (key, weight) = when (v) {
                    is Int -> if (v in Short.MIN_VALUE..Short.MAX_VALUE) return else "I$v" to 1
                    is Char -> if (v.code <= Short.MAX_VALUE) return else "C${v.code}" to 1
                    is Float -> if (v == 0f || v == 1f || v == 2f) return else "F${v.toRawBits()}" to 1
                    is Long -> if (v == 0L || v == 1L) return else "J$v" to 2
                    is Double -> if (v == 0.0 || v == 1.0) return else "D${v.toRawBits()}" to 2
                    is String -> "S$v" to 2
                    else -> return
                }
                seen[key] = weight
            }
            fun visit(v: ResultValue) {
                when (v) {
                    Null, UnitValue -> {}
                    is Scalar -> add(v.value)
                    is Sequence -> v.items.forEach(::visit)
                    is MapValue -> v.entries.forEach { (k, e) -> visit(k); visit(e) }
                    is PrimitiveArray -> v.values.forEach(::add)
                }
            }
            visit(value)
            return seen.values.sum()
        }
    }
}
