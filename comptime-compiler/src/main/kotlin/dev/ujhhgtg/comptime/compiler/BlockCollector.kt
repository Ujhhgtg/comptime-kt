package dev.ujhhgtg.comptime.compiler

import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.name.FqName

/** A `comptime` call found in a file, before any checks. */
class CallSite(
    val file: IrFile,
    val call: IrCall,
    /** True when the call sits inside a non-private inline function, whose body other modules copy. */
    val inPublicInline: Boolean,
)

/**
 * Finds every `comptime` call in a file. Calls nested inside another block's lambda aren't collected: they run as
 * part of the outer block in the host.
 */
class BlockCollector(private val file: IrFile) : IrVisitorVoid() {
    val sites = mutableListOf<CallSite>()
    private val functions = ArrayDeque<IrFunction>()
    private var insideBlock = 0

    override fun visitElement(element: org.jetbrains.kotlin.ir.IrElement) {
        element.acceptChildrenVoid(this)
    }

    override fun visitFunction(declaration: IrFunction) {
        functions.addLast(declaration)
        super.visitFunction(declaration)
        functions.removeLast()
    }

    override fun visitCall(expression: IrCall) {
        if (!isComptime(expression)) return super.visitCall(expression)
        if (insideBlock == 0) {
            val inPublicInline = functions.any {
                it is IrSimpleFunction && it.isInline && !DescriptorVisibilities.isPrivate(it.visibility)
            }
            sites += CallSite(file, expression, inPublicInline)
        }
        insideBlock++
        super.visitCall(expression)
        insideBlock--
    }

    companion object {
        val COMPTIME_FQ_NAME = FqName("dev.ujhhgtg.comptime.comptime")

        fun isComptime(call: IrCall): Boolean {
            val callee = call.symbol.owner
            return callee.name.asString() == "comptime" && callee.kotlinFqName == COMPTIME_FQ_NAME
        }

        /** The lambda literal passed to [call], or `null` when the argument is anything else. */
        fun lambdaOf(call: IrCall): IrFunctionExpression? {
            val argument = call.arguments.lastOrNull() as? IrFunctionExpression ?: return null
            return argument.takeIf { it.origin == IrStatementOrigin.LAMBDA }
        }
    }
}
