package dev.ujhhgtg.comptime.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.expressions.IrConstKind
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetObjectValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrVarargImpl
import org.jetbrains.kotlin.ir.expressions.impl.fromSymbolOwner
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.impl.makeTypeProjection
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.types.typeWithArguments
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.Variance

/**
 * Builds IR that constructs a decoded result value: constants for scalars, `listOf(vararg)` and friends for
 * collections. See docs/plan.md, "IR construction".
 */
class ValueBuilder(private val context: IrPluginContext) {
    private val finder = context.finderForBuiltins()
    private val builtIns = context.irBuiltIns

    private fun varargFunction(pkg: String, name: String): IrSimpleFunctionSymbol =
        finder.findFunctions(CallableId(FqName(pkg), Name.identifier(name))).singleOrNull { symbol ->
            symbol.owner.parameters.singleOrNull()?.varargElementType != null
        } ?: error("comptime: can't find $pkg.$name(vararg)")

    private fun noArgFunction(pkg: String, name: String): IrSimpleFunctionSymbol =
        finder.findFunctions(CallableId(FqName(pkg), Name.identifier(name))).single { it.owner.parameters.isEmpty() }

    private fun klass(fqName: String): IrClassSymbol =
        finder.findClass(ClassId.topLevel(FqName(fqName))) ?: error("comptime: can't find class $fqName")

    private val listOf by lazy { varargFunction("kotlin.collections", "listOf") }
    private val setOf by lazy { varargFunction("kotlin.collections", "setOf") }
    private val mapOf by lazy { varargFunction("kotlin.collections", "mapOf") }
    private val arrayOf by lazy { varargFunction("kotlin", "arrayOf") }
    private val emptyList by lazy { noArgFunction("kotlin.collections", "emptyList") }
    private val emptySet by lazy { noArgFunction("kotlin.collections", "emptySet") }
    private val emptyMap by lazy { noArgFunction("kotlin.collections", "emptyMap") }
    private val pairClass by lazy { klass("kotlin.Pair") }
    private val pairConstructor: IrConstructorSymbol by lazy { finder.findConstructors(ClassId.topLevel(FqName("kotlin.Pair"))).single() }
    private val primitiveArrayOf = HashMap<ScalarKind, IrSimpleFunctionSymbol>()

    fun irType(type: ResultType): IrType {
        val base: IrType = when (type) {
            is ResultType.Scalar -> scalarType(type.kind)
            is ResultType.ListOf -> builtIns.listClass.typeWith(irType(type.element))
            is ResultType.SetOf -> builtIns.setClass.typeWith(irType(type.element))
            is ResultType.MapOf -> builtIns.mapClass.typeWith(irType(type.key), irType(type.value))
            is ResultType.ArrayOf -> builtIns.arrayClass.typeWith(irType(type.element))
            is ResultType.PrimitiveArray -> klass(type.kind.arrayName!!).typeWith()
        }
        return if (type.nullable) base.makeNullable() else base
    }

    private fun scalarType(kind: ScalarKind): IrType = when (kind) {
        ScalarKind.BOOLEAN -> builtIns.booleanType
        ScalarKind.BYTE -> builtIns.byteType
        ScalarKind.SHORT -> builtIns.shortType
        ScalarKind.INT -> builtIns.intType
        ScalarKind.LONG -> builtIns.longType
        ScalarKind.FLOAT -> builtIns.floatType
        ScalarKind.DOUBLE -> builtIns.doubleType
        ScalarKind.CHAR -> builtIns.charType
        ScalarKind.STRING -> builtIns.stringType
        ScalarKind.UNIT -> builtIns.unitType
        ScalarKind.UBYTE, ScalarKind.USHORT, ScalarKind.UINT, ScalarKind.ULONG -> klass(kind.kotlinName).typeWith()
    }

    /** IR that evaluates to [value], typed as [type]. */
    fun build(value: ResultValue, type: ResultType, start: Int, end: Int): IrExpression {
        if (value is ResultValue.Null) return IrConstImpl.constNull(start, end, irType(type).makeNullable())
        return when (type) {
            is ResultType.Scalar -> scalar(value, type.kind, start, end)
            is ResultType.ListOf -> sequenceCall((value as ResultValue.Sequence).items, type.element, listOf, emptyList, builtIns.listClass, start, end)
            is ResultType.SetOf -> sequenceCall((value as ResultValue.Sequence).items, type.element, setOf, emptySet, builtIns.setClass, start, end)
            is ResultType.ArrayOf -> sequenceCall((value as ResultValue.Sequence).items, type.element, arrayOf, null, builtIns.arrayClass, start, end)
            is ResultType.MapOf -> map(value as ResultValue.MapValue, type, start, end)
            is ResultType.PrimitiveArray -> primitiveArray(value as ResultValue.PrimitiveArray, type.kind, start, end)
        }
    }

    private fun scalar(value: ResultValue, kind: ScalarKind, start: Int, end: Int): IrExpression {
        if (kind == ScalarKind.UNIT) return IrGetObjectValueImpl(start, end, builtIns.unitType, builtIns.unitClass)
        val v = (value as ResultValue.Scalar).value
        val type = scalarType(kind)
        return when (kind) {
            ScalarKind.BOOLEAN -> IrConstImpl.boolean(start, end, type, v as Boolean)
            ScalarKind.BYTE, ScalarKind.UBYTE -> IrConstImpl(start, end, type, IrConstKind.Byte, v as Byte)
            ScalarKind.SHORT, ScalarKind.USHORT -> IrConstImpl(start, end, type, IrConstKind.Short, v as Short)
            ScalarKind.INT, ScalarKind.UINT -> IrConstImpl(start, end, type, IrConstKind.Int, v as Int)
            ScalarKind.LONG, ScalarKind.ULONG -> IrConstImpl(start, end, type, IrConstKind.Long, v as Long)
            ScalarKind.FLOAT -> IrConstImpl.float(start, end, type, v as Float)
            ScalarKind.DOUBLE -> IrConstImpl.double(start, end, type, v as Double)
            ScalarKind.CHAR -> IrConstImpl.char(start, end, type, v as Char)
            ScalarKind.STRING -> IrConstImpl.string(start, end, type, v as String)
            ScalarKind.UNIT -> error("unreachable")
        }
    }

    private fun sequenceCall(
        items: List<ResultValue>,
        elementType: ResultType,
        varargFn: IrSimpleFunctionSymbol,
        emptyFn: IrSimpleFunctionSymbol?,
        container: IrClassSymbol,
        start: Int,
        end: Int,
    ): IrExpression {
        val element = irType(elementType)
        val resultType = container.typeWith(element)
        if (items.isEmpty() && emptyFn != null) {
            return IrCallImpl(start, end, resultType, emptyFn).apply { typeArguments[0] = element }
        }
        val vararg = IrVarargImpl(
            start, end,
            builtIns.arrayClass.typeWithArguments(listOf(makeTypeProjection(element, Variance.OUT_VARIANCE))),
            element,
            items.map { build(it, elementType, start, end) },
        )
        return IrCallImpl(start, end, resultType, varargFn).apply {
            typeArguments[0] = element
            arguments[0] = vararg
        }
    }

    private fun map(value: ResultValue.MapValue, type: ResultType.MapOf, start: Int, end: Int): IrExpression {
        val key = irType(type.key)
        val v = irType(type.value)
        val resultType = builtIns.mapClass.typeWith(key, v)
        if (value.entries.isEmpty()) {
            return IrCallImpl(start, end, resultType, emptyMap).apply {
                typeArguments[0] = key
                typeArguments[1] = v
            }
        }
        val pairType = pairClass.typeWith(key, v)
        val pairs = value.entries.map { (k, value) ->
            IrConstructorCallImpl.fromSymbolOwner(start, end, pairType, pairConstructor, classTypeParametersCount = 2).apply {
                typeArguments[0] = key
                typeArguments[1] = v
                arguments[0] = build(k, type.key, start, end)
                arguments[1] = build(value, type.value, start, end)
            }
        }
        val vararg = IrVarargImpl(
            start, end,
            builtIns.arrayClass.typeWithArguments(listOf(makeTypeProjection(pairType, Variance.OUT_VARIANCE))),
            pairType,
            pairs,
        )
        return IrCallImpl(start, end, resultType, mapOf).apply {
            typeArguments[0] = key
            typeArguments[1] = v
            arguments[0] = vararg
        }
    }

    private fun primitiveArray(value: ResultValue.PrimitiveArray, kind: ScalarKind, start: Int, end: Int): IrExpression {
        val fn = primitiveArrayOf.getOrPut(kind) {
            varargFunction("kotlin", kind.arrayName!!.removePrefix("kotlin.").removeSuffix("Array").replaceFirstChar { it.lowercaseChar() } + "ArrayOf")
        }
        val arrayType = klass(kind.arrayName!!).typeWith()
        val elementType = scalarType(kind)
        val vararg = IrVarargImpl(
            start, end, arrayType, elementType,
            value.values.map { scalar(ResultValue.Scalar(kind, it), kind, start, end) },
        )
        return IrCallImpl(start, end, arrayType, fn).apply { arguments[0] = vararg }
    }
}
