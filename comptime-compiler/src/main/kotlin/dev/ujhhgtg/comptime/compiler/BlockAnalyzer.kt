package dev.ujhhgtg.comptime.compiler

import org.jetbrains.kotlin.fir.pipeline.wasInlined
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrClassReference
import org.jetbrains.kotlin.ir.expressions.IrComposite
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrDeclarationReference
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrRichFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrRichPropertyReference
import org.jetbrains.kotlin.ir.expressions.IrValueAccessExpression
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.constructedClass
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.getPackageFragment
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.ir.visitors.IrTypeVisitorVoid
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.name.FqName

/** A const read that the compiler already folded to [const]; its span gets the literal text. */
class Splice(val start: Int, val end: Int, val const: IrConst)

/** A problem found in a block, located at [element]. */
class BlockProblem(val element: IrElement, val message: String)

/**
 * Walks a block's IR once to (1) check that it references only the JDK, kotlin-stdlib, constants and its own
 * declarations, (2) find the constants [ConstInliner][org.jetbrains.kotlin.fir.pipeline.ConstInliner] already
 * folded, and (3) record which external names it uses, for import filtering. See docs/plan.md, "Reference check".
 */
class BlockAnalyzer(private val lambda: IrFunctionExpression) {
    val problems = mutableListOf<BlockProblem>()
    val splices = mutableListOf<Splice>()
    val usedNames = linkedSetOf<FqName>()

    private val locals = HashSet<IrDeclaration>()

    fun analyze(): BlockAnalyzer {
        lambda.function.acceptVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)
            override fun visitDeclaration(declaration: org.jetbrains.kotlin.ir.declarations.IrDeclarationBase) {
                locals += declaration
                super.visitDeclaration(declaration)
            }
        })
        lambda.function.acceptVoid(Checker())
        return this
    }

    private inner class Checker : IrTypeVisitorVoid() {
        override fun visitType(container: IrElement, type: IrType) {
            val simple = type as? IrSimpleType ?: return
            when (val classifier = simple.classifier) {
                is IrClassSymbol -> checkSymbol(classifier, container)
                is IrTypeParameterSymbol -> if (classifier.owner !in locals) {
                    report(container, "comptime blocks can't use the type parameter '${classifier.owner.name}' from outside the block")
                }
                else -> {}
            }
        }

        override fun visitDeclarationReference(expression: IrDeclarationReference) {
            // Receivers and arguments first: an implicit outer `this` shares the call's offsets, and only the first
            // diagnostic at a position is kept, so it must come before the complaint about the callee.
            // The value check goes before the expression's own type is visited, for the same reason.
            if (expression is IrValueAccessExpression) checkValue(expression.symbol.owner, expression)
            super.visitDeclarationReference(expression)
            when (expression) {
                is IrValueAccessExpression -> {}
                is IrCall -> if (!BlockCollector.isComptime(expression)) checkSymbol(expression.symbol, expression)
                else -> checkSymbol(expression.symbol, expression)
            }
        }

        override fun visitRichFunctionReference(expression: IrRichFunctionReference) {
            expression.reflectionTargetSymbol?.let { checkSymbol(it, expression) }
            super.visitRichFunctionReference(expression)
        }

        override fun visitRichPropertyReference(expression: IrRichPropertyReference) {
            expression.reflectionTargetSymbol?.let { checkSymbol(it, expression) }
            super.visitRichPropertyReference(expression)
        }

        override fun visitClassReference(expression: IrClassReference) {
            checkSymbol(expression.symbol, expression)
            super.visitClassReference(expression)
        }

        override fun visitConst(expression: IrConst) {
            if (expression.wasInlined == true && expression.startOffset >= 0 && expression.endOffset > expression.startOffset) {
                splices += Splice(expression.startOffset, expression.endOffset, expression)
            }
            super.visitConst(expression)
        }

        override fun visitComposite(expression: IrComposite) {
            val last = expression.statements.lastOrNull()
            if (expression.statements.size == 2 && last is IrConst && last.wasInlined == true) {
                report(expression, "comptime blocks can't read a constant through an expression receiver; name the constant directly")
                return
            }
            super.visitComposite(expression)
        }
    }

    private fun report(at: IrElement, message: String) {
        if (problems.none { it.element.startOffset == at.startOffset && it.element.endOffset == at.endOffset }) {
            problems += BlockProblem(at, message)
        }
    }

    private fun checkValue(value: IrValueDeclaration, at: IrElement) {
        if (value in locals) return
        val isReceiver = value is IrValueParameter &&
            (value.kind == IrParameterKind.DispatchReceiver || value.kind == IrParameterKind.ExtensionReceiver || value.kind == IrParameterKind.Context)
        report(
            at,
            if (isReceiver) "comptime blocks can't use an outer 'this' or implicit receiver"
            else "comptime blocks can't capture '${value.name}'; only the JDK, kotlin-stdlib, constants and the block's own declarations are allowed",
        )
    }

    private fun checkSymbol(symbol: IrSymbol, at: IrElement) {
        if (!symbol.isBound) return
        val declaration = symbol.owner as? IrDeclaration ?: return
        if (declaration in locals || declaration is IrTypeParameter) return
        val fragment = runCatching { declaration.getPackageFragment() }.getOrNull() ?: return
        val name = describe(declaration)
        if (fragment is IrFile) {
            report(at, "comptime blocks can't use '$name', which is declared in this module; only the JDK, kotlin-stdlib, constants and the block's own declarations are allowed")
            return
        }
        val pkg = fragment.packageFqName
        if (!isAllowedPackage(pkg)) {
            report(at, "comptime blocks can't use '$name'; only the JDK, kotlin-stdlib, constants and the block's own declarations are allowed")
            return
        }
        recordName(declaration)
    }

    private fun recordName(declaration: IrDeclaration) {
        (declaration as? IrDeclarationWithName)?.fqNameWhenAvailable?.let { usedNames += it }
        var cls: IrClass? = declaration.parentClassOrNull
        while (cls != null) {
            cls.fqNameWhenAvailable?.let { usedNames += it }
            cls = cls.parentClassOrNull
        }
    }

    private fun describe(declaration: IrDeclaration): String {
        val named: IrDeclaration = when (declaration) {
            is IrConstructor -> declaration.constructedClass
            is IrSimpleFunction -> declaration.correspondingPropertySymbol?.owner ?: declaration
            is IrField -> declaration.correspondingPropertySymbol?.owner ?: declaration
            else -> declaration
        }
        return (named as? IrDeclarationWithName)?.fqNameWhenAvailable?.asString() ?: named.toString()
    }

    companion object {
        private val ALLOWED_ROOTS = setOf("kotlin", "java", "javax", "jdk")

        fun isAllowedPackage(pkg: FqName): Boolean =
            !pkg.isRoot && pkg.pathSegments().first().asString() in ALLOWED_ROOTS
    }
}
