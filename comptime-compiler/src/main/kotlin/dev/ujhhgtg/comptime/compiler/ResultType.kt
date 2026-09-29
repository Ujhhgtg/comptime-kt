package dev.ujhhgtg.comptime.compiler

import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.name.FqName

/** Scalar result kinds, with their descriptor letters and Kotlin names. */
enum class ScalarKind(val letter: Char, val kotlinName: String, val arrayName: String? = null) {
    BOOLEAN('Z', "kotlin.Boolean", "kotlin.BooleanArray"),
    BYTE('B', "kotlin.Byte", "kotlin.ByteArray"),
    SHORT('S', "kotlin.Short", "kotlin.ShortArray"),
    INT('I', "kotlin.Int", "kotlin.IntArray"),
    LONG('J', "kotlin.Long", "kotlin.LongArray"),
    FLOAT('F', "kotlin.Float", "kotlin.FloatArray"),
    DOUBLE('D', "kotlin.Double", "kotlin.DoubleArray"),
    CHAR('C', "kotlin.Char", "kotlin.CharArray"),
    STRING('T', "kotlin.String"),
    UBYTE('b', "kotlin.UByte"),
    USHORT('s', "kotlin.UShort"),
    UINT('i', "kotlin.UInt"),
    ULONG('j', "kotlin.ULong"),
    UNIT('V', "kotlin.Unit");

    val isPrimitive get() = arrayName != null
}

/**
 * The allowed shape of a comptime result type (see docs/plan.md, "Result types"). [descriptor] is the compact
 * prefix form shared with the host's encoder: `?` before a nullable type, then a letter per kind, then its
 * arguments. `L?T` is `List<String?>`, `?LT` is `List<String>?`.
 */
sealed class ResultType {
    abstract val nullable: Boolean

    data class Scalar(val kind: ScalarKind, override val nullable: Boolean) : ResultType()
    data class ListOf(val element: ResultType, override val nullable: Boolean) : ResultType()
    data class SetOf(val element: ResultType, override val nullable: Boolean) : ResultType()
    data class MapOf(val key: ResultType, val value: ResultType, override val nullable: Boolean) : ResultType()
    data class ArrayOf(val element: ResultType, override val nullable: Boolean) : ResultType()
    data class PrimitiveArray(val kind: ScalarKind, override val nullable: Boolean) : ResultType()

    val descriptor: String
        get() = buildString { appendDescriptor(this@ResultType) }

    /** Fully qualified Kotlin source rendering. */
    fun render(): String {
        val base = when (this) {
            is Scalar -> kind.kotlinName
            is ListOf -> "kotlin.collections.List<${element.render()}>"
            is SetOf -> "kotlin.collections.Set<${element.render()}>"
            is MapOf -> "kotlin.collections.Map<${key.render()}, ${value.render()}>"
            is ArrayOf -> "kotlin.Array<${element.render()}>"
            is PrimitiveArray -> kind.arrayName!!
        }
        return if (nullable) "$base?" else base
    }

    fun containsArray(): Boolean = when (this) {
        is Scalar -> false
        is ListOf -> element.containsArray()
        is SetOf -> element.containsArray()
        is MapOf -> key.containsArray() || value.containsArray()
        is ArrayOf, is PrimitiveArray -> true
    }

    companion object {
        private val FLEXIBLE_NULLABILITY = FqName("kotlin.internal.ir.FlexibleNullability")
        private val FLEXIBLE_MUTABILITY = FqName("kotlin.internal.ir.FlexibleMutability")

        private val scalarsByName = ScalarKind.entries.associateBy { it.kotlinName }
        private val primitiveArraysByName = ScalarKind.entries.filter { it.isPrimitive }.associateBy { it.arrayName!! }

        /** Maps [type] to a [ResultType], or explains which part of it isn't supported. */
        fun of(type: IrType): Analysis = try {
            Analysis.Supported(analyze(type))
        } catch (e: Unsupported) {
            Analysis.Unsupported(e.part)
        }

        private class Unsupported(val part: String) : Exception(null, null, false, false)

        private fun analyze(type: IrType): ResultType {
            val simple = type as? IrSimpleType ?: throw Unsupported(type.toString())
            val nullable = simple.isMarkedNullable() || simple.hasAnnotation(FLEXIBLE_NULLABILITY)
            val flexibleMutability = simple.hasAnnotation(FLEXIBLE_MUTABILITY)
            val name = simple.classFqName?.asString() ?: throw Unsupported(renderLoosely(simple))

            fun arg(i: Int): ResultType {
                val projection = simple.arguments.getOrNull(i) as? IrTypeProjection
                    ?: throw Unsupported("star projection in ${renderLoosely(simple)}")
                return analyze(projection.type)
            }

            scalarsByName[name]?.let { return Scalar(it, nullable) }
            primitiveArraysByName[name]?.let { return PrimitiveArray(it, nullable) }
            return when (name) {
                "kotlin.collections.List" -> ListOf(arg(0), nullable)
                "kotlin.collections.Set" -> SetOf(arg(0), nullable)
                "kotlin.collections.Map" -> MapOf(arg(0), arg(1), nullable)
                "kotlin.collections.MutableList" -> if (flexibleMutability) ListOf(arg(0), nullable) else throw Unsupported(mutableHint(name))
                "kotlin.collections.MutableSet" -> if (flexibleMutability) SetOf(arg(0), nullable) else throw Unsupported(mutableHint(name))
                "kotlin.collections.MutableMap" -> if (flexibleMutability) MapOf(arg(0), arg(1), nullable) else throw Unsupported(mutableHint(name))
                "kotlin.Array" -> ArrayOf(arg(0), nullable)
                else -> throw Unsupported(name)
            }
        }

        private fun mutableHint(name: String) = "$name (declare the result as a read-only ${name.removePrefix("kotlin.collections.Mutable")})"

        private fun renderLoosely(type: IrSimpleType): String = type.classFqName?.asString() ?: type.classifier.toString()

        private fun StringBuilder.appendDescriptor(t: ResultType) {
            if (t.nullable) append('?')
            when (t) {
                is Scalar -> append(t.kind.letter)
                is ListOf -> { append('L'); appendDescriptor(t.element) }
                is SetOf -> { append('E'); appendDescriptor(t.element) }
                is MapOf -> { append('M'); appendDescriptor(t.key); appendDescriptor(t.value) }
                is ArrayOf -> { append('A'); appendDescriptor(t.element) }
                is PrimitiveArray -> { append('['); append(t.kind.letter) }
            }
        }
    }

    sealed class Analysis {
        class Supported(val type: ResultType) : Analysis()
        class Unsupported(val part: String) : Analysis()
    }
}
