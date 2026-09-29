package dev.ujhhgtg.comptime.compiler.fir

import dev.ujhhgtg.comptime.compiler.BlockCollector
import dev.ujhhgtg.comptime.compiler.ComptimeErrors
import dev.ujhhgtg.comptime.compiler.ResultType
import org.jetbrains.kotlin.text
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.ExpressionCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeErrorType
import org.jetbrains.kotlin.fir.types.ConeFlexibleType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeKotlinTypeProjection
import org.jetbrains.kotlin.fir.types.isMarkedNullable
import org.jetbrains.kotlin.fir.types.lowerBoundIfFlexible
import org.jetbrains.kotlin.fir.types.renderReadableWithFqNames
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.types.upperBoundIfFlexible

/** Registers the FIR checker, which reports block-shape and result-type errors during analysis. */
class ComptimeFirRegistrar : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() {
        +::ComptimeCheckers
    }
}

class ComptimeCheckers(session: FirSession) : FirAdditionalCheckersExtension(session) {
    override val expressionCheckers: ExpressionCheckers = object : ExpressionCheckers() {
        override val functionCallCheckers: Set<FirFunctionCallChecker> = setOf(ComptimeCallChecker)
    }
}

/**
 * The checks that need only the call itself: the argument is a lambda literal, the callee isn't reached through an
 * import alias, and the result type is supported. They run during analysis, so errors appear before IR (and in an
 * IDE that loads the plugin). The reference check needs the whole block and stays in IR.
 */
object ComptimeCallChecker : FirFunctionCallChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        if (!expression.isComptime()) return
        // A call nested in another block runs as part of that block on the host, like any other code in it.
        if (context.callsOrAssignments.any { it !== expression && it is FirFunctionCall && it.isComptime() }) return

        val argument = expression.arguments.lastOrNull()
        if (argument !is FirAnonymousFunctionExpression || !argument.anonymousFunction.isLambda) {
            reporter.reportOn(argument?.source ?: expression.source, ComptimeErrors.COMPTIME_ERROR, "comptime needs a lambda literal, like comptime { ... }")
            return
        }

        val callee = expression.calleeReference.source.text?.toString()
        if (callee != null && callee != "comptime") {
            reporter.reportOn(
                expression.calleeReference.source, ComptimeErrors.COMPTIME_ERROR,
                "comptime must be called by its name or fully qualified name; import aliases aren't supported",
            )
            return
        }

        val type = expression.resolvedType
        if (type is ConeErrorType) return
        val analysis = ResultType.analysis { analyze(type.fullyExpandedType()) }
        if (analysis is ResultType.Analysis.Unsupported) {
            reporter.reportOn(
                expression.source, ComptimeErrors.COMPTIME_ERROR,
                "comptime result type ${type.renderReadableWithFqNames()} is not supported: ${analysis.part}. " +
                    "Allowed: primitives, String, unsigned types, List, Set, Map, Array, primitive arrays, their nullable forms, and Unit",
            )
        }
    }

    private fun FirFunctionCall.isComptime(): Boolean =
        calleeReference.toResolvedCallableSymbol()?.callableId?.asSingleFqName() == BlockCollector.COMPTIME_FQ_NAME

    private fun analyze(type: ConeKotlinType): ResultType {
        val lower = type.lowerBoundIfFlexible()
        val upper = type.upperBoundIfFlexible()
        val classType = lower as? ConeClassLikeType ?: ResultType.unsupported(type.renderReadableWithFqNames())
        val name = classType.lookupTag.classId.asSingleFqName().asString()
        val upperName = (upper as? ConeClassLikeType)?.lookupTag?.classId?.asSingleFqName()?.asString()
        return ResultType.shape(
            name = name,
            nullable = type.isMarkedNullable || (type is ConeFlexibleType && upper.isMarkedNullable),
            flexibleMutability = type is ConeFlexibleType && upperName != name,
        ) { i ->
            val projection = classType.typeArguments.getOrNull(i) as? ConeKotlinTypeProjection
                ?: ResultType.unsupported("star projection in $name")
            analyze(projection.type)
        }
    }
}
