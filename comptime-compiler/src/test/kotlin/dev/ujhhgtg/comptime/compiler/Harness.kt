package dev.ujhhgtg.comptime.compiler

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.arguments.parseCommandLineArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Files

/** Compiles Kotlin sources in-process with the comptime plugin applied, and runs the result. */
object Harness {
    private fun prop(name: String) = System.getProperty(name) ?: error("system property $name not set; run through Gradle")

    val pluginJar = File(prop("comptime.pluginJar"))
    val runtimeJar = File(prop("comptime.runtimeJar"))
    val hostClasspath: String = prop("comptime.hostClasspath")
    val stdlibJar: File = System.getProperty("java.class.path").split(File.pathSeparator)
        .map(::File).first { it.name.matches(Regex("""kotlin-stdlib-\d[\w.\-]*\.jar""")) }

    class Message(val severity: CompilerMessageSeverity, val text: String, val line: Int?, val column: Int?, val file: String?) {
        override fun toString() = "$severity ${file?.let { File(it).name } ?: ""}:${line ?: ""}:${column ?: ""} $text"
    }

    class Result(val exitCode: ExitCode, val messages: List<Message>, val classes: File, val workDir: File, val classpath: List<File>) {
        val errors get() = messages.filter { it.severity.isError }
        val ok get() = exitCode == ExitCode.OK

        fun assertOk(): Result = apply {
            check(ok) { "compilation failed ($exitCode):\n" + messages.joinToString("\n") }
        }

        fun loader(): ClassLoader = URLClassLoader(
            (listOf(classes) + classpath).map { it.toURI().toURL() }.toTypedArray(),
            ClassLoader.getPlatformClassLoader(),
        )

        /** Calls a static no-arg method; for top-level functions in `Main.kt`, the class is `MainKt`. */
        fun call(method: String, className: String = "MainKt", loader: ClassLoader = loader()): Any? = try {
            loader.loadClass(className).getMethod(method).invoke(null)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }

        val jobDir get() = File(workDir, "comptime/job")
    }

    fun compile(
        sources: Map<String, String>,
        classpath: List<File> = emptyList(),
        options: Map<String, String> = emptyMap(),
        multiOptions: List<Pair<String, String>> = emptyList(),
        workDir: File = Files.createTempDirectory("comptime-test").toFile(),
        moduleName: String = "main",
    ): Result {
        val src = File(workDir, "src").apply { mkdirs() }
        val classes = File(workDir, "classes").apply { mkdirs() }
        val files = sources.map { (name, text) -> File(src, name).apply { parentFile.mkdirs(); writeText(text) } }
        val fullClasspath = listOf(stdlibJar, runtimeJar) + classpath

        val pluginOptions = linkedMapOf(
            "hostClasspath" to hostClasspath,
            "moduleDir" to workDir.absolutePath,
            "jobDir" to File(workDir, "comptime").absolutePath,
            "timeout" to "60",
        ) + options
        val args = mutableListOf(
            "-no-stdlib", "-no-reflect",
            "-jdk-home", System.getProperty("java.home"),
            "-classpath", fullClasspath.joinToString(File.pathSeparator),
            "-d", classes.absolutePath,
            "-module-name", moduleName,
            "-Xplugin=${pluginJar.absolutePath}",
        )
        for ((k, v) in pluginOptions) args += listOf("-P", "plugin:${ComptimeCommandLineProcessor.PLUGIN_ID}:$k=$v")
        for ((k, v) in multiOptions) args += listOf("-P", "plugin:${ComptimeCommandLineProcessor.PLUGIN_ID}:$k=$v")
        args += files.map { it.absolutePath }

        val messages = mutableListOf<Message>()
        val collector = object : MessageCollector {
            override fun clear() = messages.clear()
            override fun hasErrors() = messages.any { it.severity.isError }
            override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageSourceLocation?) {
                if (severity == CompilerMessageSeverity.LOGGING || severity == CompilerMessageSeverity.OUTPUT) return
                messages += Message(severity, message, location?.line, location?.column, location?.path)
            }
        }
        val arguments = K2JVMCompilerArguments()
        parseCommandLineArguments(args, arguments)
        val exitCode = K2JVMCompiler().exec(collector, Services.EMPTY, arguments)
        return Result(exitCode, messages, classes, workDir, fullClasspath)
    }
}
