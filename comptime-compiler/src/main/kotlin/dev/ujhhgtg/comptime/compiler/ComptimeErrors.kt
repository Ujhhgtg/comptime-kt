package dev.ujhhgtg.comptime.compiler

import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.KtDiagnosticsContainer
import org.jetbrains.kotlin.diagnostics.error1
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.diagnostics.rendering.CommonRenderers

/** The plugin's only diagnostic: a comptime failure, with a fully rendered message. */
object ComptimeErrors : KtDiagnosticsContainer() {
    val COMPTIME_ERROR by error1<PsiElement, String>()

    override fun getRendererFactory(): BaseDiagnosticRendererFactory = ComptimeErrorMessages
}

object ComptimeErrorMessages : BaseDiagnosticRendererFactory() {
    override val MAP by KtDiagnosticFactoryToRendererMap("Comptime") { map ->
        map.put(ComptimeErrors.COMPTIME_ERROR, "{0}", CommonRenderers.STRING)
    }
}
