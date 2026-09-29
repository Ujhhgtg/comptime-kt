package dev.ujhhgtg.comptime.compiler

import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.diagnostics.DiagnosticFactory1DelegateProvider
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.KtDiagnosticsContainer
import org.jetbrains.kotlin.diagnostics.Severity
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies
import org.jetbrains.kotlin.diagnostics.error1
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.diagnostics.rendering.CommonRenderers

/** The plugin's diagnostics: a comptime failure, and a successful block's output. Messages are fully rendered. */
object ComptimeErrors : KtDiagnosticsContainer() {
    val COMPTIME_ERROR by error1<PsiElement, String>()

    /** Output of a block that succeeded; shown at info level (Gradle: `--info`). */
    val COMPTIME_OUTPUT by DiagnosticFactory1DelegateProvider<String>(
        Severity.INFO, SourceElementPositioningStrategies.DEFAULT, PsiElement::class, this,
    )

    override fun getRendererFactory(): BaseDiagnosticRendererFactory = ComptimeErrorMessages
}

object ComptimeErrorMessages : BaseDiagnosticRendererFactory() {
    override val MAP by KtDiagnosticFactoryToRendererMap("Comptime") { map ->
        map.put(ComptimeErrors.COMPTIME_ERROR, "{0}", CommonRenderers.STRING)
        map.put(ComptimeErrors.COMPTIME_OUTPUT, "{0}", CommonRenderers.STRING)
    }
}
