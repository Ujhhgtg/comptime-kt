
import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.getNameWithAssert
import org.jetbrains.kotlin.ir.util.kotlinFqName

class NameOfTransformer(private val context: IrPluginContext) : IrElementTransformerVoidWithContext() {

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    override fun visitCall(expression: IrCall): IrExpression {
        if (expression.symbol.owner.kotlinFqName.asString() == "dev.ujhhgtg.nameof") {
            val name = when (val argument = expression.arguments[0]) {
                is IrPropertyReference -> argument.symbol.owner.name.asString()
                is IrClassReference -> (argument.symbol.owner as IrDeclaration).getNameWithAssert().asString()
                is IrFunctionReference -> argument.symbol.owner.name.asString()
                is IrGetObjectValue -> argument.symbol.owner.name.asString() // compat for object
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
        return super.visitCall(expression)
    }
}