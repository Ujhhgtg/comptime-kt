package dev.ujhhgtg.comptime.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.builders.declarations.addField
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOriginImpl
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetFieldImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrReturnImpl
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.name.Name
import java.io.File

/**
 * Decides where a baked value lives and emits it there (see docs/plan.md, "Where values live"):
 *
 * - scalars, strings, `null` and `Unit` become inline constants;
 * - collections with no arrays inside go into a synthetic holder class, built once on first use;
 * - values containing arrays are rebuilt by a synthetic builder function on every evaluation;
 * - inside a non-private inline function, the value is built in place.
 */
class Materializer(private val context: IrPluginContext, private val values: ValueBuilder) {
    private val counters = HashMap<IrFile, Int>()
    private val pending = ArrayList<Pair<IrFile, org.jetbrains.kotlin.ir.declarations.IrDeclaration>>()

    fun materialize(site: CallSite, type: ResultType, value: ResultValue): IrExpression {
        val start = site.call.startOffset
        val end = site.call.endOffset
        val inline = type is ResultType.Scalar || value is ResultValue.Null || site.inPublicInline
        if (inline) return values.build(value, type, start, end)
        val irType = values.irType(type)
        val index = counters.merge(site.file, 1, Int::plus)!! - 1
        val baseName = "${fileStem(site.file)}\$comptime\$$index"

        return if (!type.containsArray()) {
            val holder = context.irFactory.buildClass {
                name = Name.identifier(baseName)
                kind = ClassKind.CLASS
                modality = Modality.FINAL
                visibility = DescriptorVisibilities.PRIVATE
                origin = ORIGIN
            }
            holder.parent = site.file
            holder.createThisReceiverParameter()
            holder.superTypes = listOf(context.irBuiltIns.anyType)
            val field = holder.addField {
                name = Name.identifier("VALUE")
                this.type = irType
                isStatic = true
                isFinal = true
                visibility = DescriptorVisibilities.PUBLIC
                origin = ORIGIN
            }
            field.initializer = context.irFactory.createExpressionBody(start, end, values.build(value, type, start, end))
            pending += site.file to holder
            IrGetFieldImpl(start, end, field.symbol, irType)
        } else {
            val builder = context.irFactory.buildFun {
                name = Name.identifier(baseName)
                returnType = irType
                visibility = DescriptorVisibilities.PRIVATE
                origin = ORIGIN
            }
            builder.parent = site.file
            builder.body = context.irFactory.createBlockBody(start, end).apply {
                statements += IrReturnImpl(start, end, context.irBuiltIns.nothingType, builder.symbol, values.build(value, type, start, end))
            }
            pending += site.file to builder
            IrCallImpl(start, end, irType, builder.symbol)
        }
    }

    /** Adds the generated holders and builders to their files; call after all replacements are done. */
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
