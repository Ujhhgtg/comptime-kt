package dev.ujhhgtg.comptime.protocol

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.arguments.parseCommandLineArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import java.io.File

/**
 * Compiles every synthetic source in `src/` with one [K2JVMCompiler] call, against only the JDK at [jdkHome] and the
 * given stdlib jar. Diagnostics go to `out/compile.json`.
 *
 * Shared as source: the compiler plugin runs it in-process (inside the warm Kotlin daemon), and the host runs it
 * when the plugin couldn't.
 */
internal class BlockCompiler(
    private val layout: JobLayout,
    private val stdlib: File,
    private val jdkHome: File,
    private val extraArgs: List<String>,
) {
    fun compile(): Boolean {
        layout.classes.mkdirs()
        layout.out.mkdirs()
        val sources = layout.src.listFiles { f -> f.extension == "kt" }.orEmpty().sortedBy { it.name }
        val diagnostics = ArrayList<Map<String, Any?>>()
        val collector = object : MessageCollector {
            private var errors = false
            override fun clear() {}
            override fun hasErrors() = errors
            override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageSourceLocation?) {
                if (!severity.isError) return
                errors = true
                diagnostics += linkedMapOf(
                    "severity" to severity.presentableName,
                    "file" to location?.path?.let { File(it).name },
                    "line" to location?.line?.toLong(),
                    "column" to location?.column?.toLong(),
                    "message" to message,
                )
            }
        }

        val argList = listOf(
            "-no-stdlib",
            "-no-reflect",
            "-nowarn",
            "-classpath", stdlib.absolutePath,
            "-jdk-home", jdkHome.absolutePath,
            "-module-name", "comptime-job",
            "-d", layout.classes.absolutePath,
        ) + extraArgs + sources.map { it.absolutePath }

        val arguments = K2JVMCompilerArguments()
        parseCommandLineArguments(argList, arguments)
        val exitCode = try {
            K2JVMCompiler().exec(collector, Services.EMPTY, arguments)
        } catch (t: Throwable) {
            diagnostics += linkedMapOf("severity" to "exception", "message" to t.stackTraceToString())
            ExitCode.INTERNAL_ERROR
        }

        val success = exitCode == ExitCode.OK && !collector.hasErrors()
        layout.compileResult.writeText(
            Json.write(linkedMapOf("success" to success, "exitCode" to exitCode.name, "diagnostics" to diagnostics))
        )
        return success
    }
}
