package dev.ujhhgtg.comptime

import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.getNameWithAssert
import org.jetbrains.kotlin.ir.util.kotlinFqName

class ComptimeTransformer(private val context: IrPluginContext) : IrElementTransformerVoidWithContext() {

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    override fun visitCall(expression: IrCall): IrExpression {
        val fqName = expression.symbol.owner.kotlinFqName.asString()

        // 1. Existing nameOf logic
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
                else -> "unknown"
            }
            return createStringConst(expression, name)
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

        if (fqName.startsWith("dev.ujhhgtg.comptime.This.Class.")) {
            val enclosingClass = currentClass?.scope?.scopeOwnerSymbol?.owner as? IrClass
            val result = when (fqName) {
                "dev.ujhhgtg.comptime.This.Class.<get-name>" -> enclosingClass?.kotlinFqName?.asString()
                "dev.ujhhgtg.comptime.This.Class.<get-simpleName>" -> enclosingClass?.name?.asString()
                else -> error("Unknown comptime constant: $fqName")
            } ?: return super.visitCall(expression)
            return createStringConst(expression, result)
        }

        if (fqName.startsWith("dev.ujhhgtg.comptime.This.Method.")) {
            val enclosingMethod = currentFunction?.scope?.scopeOwnerSymbol?.owner as? IrFunction
            val result = when (fqName) {
                "dev.ujhhgtg.comptime.This.Method.<get-name>" -> enclosingMethod?.kotlinFqName?.asString()
                "dev.ujhhgtg.comptime.This.Method.<get-simpleName>" -> enclosingMethod?.name?.asString()
                else -> error("Unknown comptime constant: $fqName")
            } ?: return super.visitCall(expression)
            return createStringConst(expression, result)
        }

        return super.visitCall(expression)
    }

    private fun createStringConst(expression: IrExpression, value: String): IrConstImpl {
        return IrConstImpl.string(
            expression.startOffset,
            expression.endOffset,
            context.irBuiltIns.stringType,
            value
        )
    }
}
