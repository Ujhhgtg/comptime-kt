package dev.ujhhgtg.comptime.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.DescriptorVisibility
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.builders.declarations.addField
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOriginImpl
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetFieldImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrReturnImpl
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.name.Name
import java.io.File

/**
 * Decides where a baked value lives and emits it there (see docs/plan.md, "Where values live"):
 *
 * - scalars, strings, `null` and `Unit` become inline constants;
 * - inside a non-private inline function, the value is built in place;
 * - anything else gets a private synthetic class `<File>$comptime$<n>`: collections with no arrays inside are built
 *   once into its static `VALUE` field; values containing arrays are rebuilt by its static `build()` on every
 *   evaluation.
 *
 * Construction that would overflow one method is split across static `part$<k>` helpers in the same class, so a
 * value is limited by the class's constant pool, not by the JVM's 64 KB method limit.
 */
class Materializer(
    private val context: IrPluginContext,
    private val values: ValueBuilder,
    private val methodBudget: Long,
) {
    private val counters = HashMap<IrFile, Int>()
    private val pending = ArrayList<Pair<IrFile, IrClass>>()

    fun materialize(site: CallSite, type: ResultType, value: ResultValue): IrExpression {
        val start = site.call.startOffset
        val end = site.call.endOffset
        if (isInline(site, type, value)) return values.build(value, type, start, end)

        val irType = values.irType(type)
        val index = counters.merge(site.file, 1, Int::plus)!! - 1
        val holder = context.irFactory.buildClass {
            name = Name.identifier("${fileStem(site.file)}\$comptime\$$index")
            kind = ClassKind.CLASS
            modality = Modality.FINAL
            visibility = DescriptorVisibilities.PRIVATE
            origin = ORIGIN
        }
        holder.parent = site.file
        holder.createThisReceiverParameter()
        holder.superTypes = listOf(context.irBuiltIns.anyType)
        pending += site.file to holder

        var parts = 0
        val body = values.buildSplit(value, type, start, end, methodBudget) { returnType, expression ->
            val part = staticFunction(holder, "part\$${parts++}", returnType, DescriptorVisibilities.PRIVATE, expression, start, end)
            IrCallImpl(start, end, returnType, part.symbol)
        }

        return if (!type.containsArray()) {
            val field = holder.addField {
                name = Name.identifier("VALUE")
                this.type = irType
                isStatic = true
                isFinal = true
                visibility = DescriptorVisibilities.PUBLIC
                origin = ORIGIN
            }
            field.initializer = context.irFactory.createExpressionBody(start, end, body)
            IrGetFieldImpl(start, end, field.symbol, irType)
        } else {
            val build = staticFunction(holder, "build", irType, DescriptorVisibilities.PUBLIC, body, start, end)
            IrCallImpl(start, end, irType, build.symbol)
        }
    }

    /** Values that are emitted at the call site itself, so the method-size limit applies to them directly. */
    fun isInline(site: CallSite, type: ResultType, value: ResultValue): Boolean =
        type is ResultType.Scalar || value is ResultValue.Null || site.inPublicInline

    /** A function in [owner] without a dispatch receiver, which the JVM backend emits as a static method. */
    private fun staticFunction(
        owner: IrClass,
        name: String,
        returnType: IrType,
        visibility: DescriptorVisibility,
        expression: IrExpression,
        start: Int,
        end: Int,
    ): IrSimpleFunction {
        val function = context.irFactory.buildFun {
            this.name = Name.identifier(name)
            this.returnType = returnType
            this.visibility = visibility
            origin = ORIGIN
        }
        function.parent = owner
        function.body = context.irFactory.createBlockBody(start, end).apply {
            statements += IrReturnImpl(start, end, context.irBuiltIns.nothingType, function.symbol, expression)
        }
        owner.declarations += function
        return function
    }

    /** Adds the generated classes to their files; call after all replacements are done. */
    fun flush() {
        for ((file, declaration) in pending) file.declarations += declaration
        pending.clear()
    }

    private fun fileStem(file: IrFile): String =
        File(file.fileEntry.name).nameWithoutExtension.filter { it.isLetterOrDigit() || it == '_' }.ifEmpty { "File" }

    companion object {
        val ORIGIN: IrDeclarationOrigin = IrDeclarationOriginImpl("COMPTIME_VALUE", isSynthetic = true)
    }
}
