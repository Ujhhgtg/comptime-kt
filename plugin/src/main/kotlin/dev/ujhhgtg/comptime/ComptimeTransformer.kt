package dev.ujhhgtg.comptime

import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.name
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrClassReference
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrGetObjectValue
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrPropertyReference
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrErrorExpressionImpl
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.getNameWithAssert
import org.jetbrains.kotlin.ir.util.kotlinFqName

class ComptimeTransformer(
    private val context: IrPluginContext,
    private val messageCollector: MessageCollector
) : IrElementTransformerVoidWithContext() {

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    override fun visitCall(expression: IrCall): IrExpression {
        val fqName = expression.symbol.owner.kotlinFqName.asString()

        if (fqName == "dev.ujhhgtg.comptime.nameOf") {
            val name = when (val argument = expression.arguments[0]) {
                is IrPropertyReference -> argument.symbol.owner.name.asString()
                is IrClassReference -> (argument.symbol.owner as IrDeclaration).getNameWithAssert().asString()
                is IrFunctionReference -> argument.symbol.owner.name.asString()
                is IrGetObjectValue -> argument.symbol.owner.name.asString()
                is IrCall -> argument.symbol.owner.name.asString().run {
                    if (startsWith("<get-") && endsWith(">"))
                        substring(5, length - 1)
                    else this
                }
                is IrGetValue -> argument.symbol.owner.name.asString()
                null -> error("Unexpected null argument")
                else -> error("Unexpected argument type ${argument::class}")
            }

            return IrConstImpl.string(
                expression.startOffset,
                expression.endOffset,
                context.irBuiltIns.stringType,
                name
            )
        }

//        if (fqName == "dev.ujhhgtg.comptime.comptimeError") {
//            val location = currentFile.fileEntry.let { entry ->
//                val line = entry.getLineNumber(expression.startOffset) + 1
//                val col  = entry.getColumnNumber(expression.startOffset) + 1
//                "${currentFile.name}:$line:$col"
//            }
//            messageCollector.report(
//                CompilerMessageSeverity.ERROR,
//                "comptimeError() is invoked ($location)",
//            )
//            return IrErrorExpressionImpl(
//                expression.startOffset,
//                expression.endOffset,
//                context.irBuiltIns.nothingType,
//                "comptimeError() invoked",
//            )
//        }

        return super.visitCall(expression)
    }
}