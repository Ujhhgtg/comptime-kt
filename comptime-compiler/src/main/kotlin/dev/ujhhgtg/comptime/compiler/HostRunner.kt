package dev.ujhhgtg.comptime.compiler

import dev.ujhhgtg.comptime.protocol.JobLayout
import java.io.File
import java.util.concurrent.TimeUnit

/** How the host process ended. */
internal class HostOutcome(val exitCode: Int?, val timedOut: Boolean, val stderrTail: String)

/**
 * Runs the host as a child process on the chosen JDK, in the module directory, with declared env vars forced to
 * their current values. The Kotlin daemon never refreshes its own environment, so inheriting it alone would bake
 * stale values. See docs/plan.md, "Launch".
 */
internal class HostRunner(private val options: ComptimeOptions) {
    fun run(layout: JobLayout): HostOutcome {
        require(options.hostClasspath.isNotEmpty()) {
            "comptime: no host classpath configured (plugin option 'hostClasspath'); apply the dev.ujhhgtg.comptime Gradle plugin"
        }
        val command = listOf(
            options.javaExecutable.path,
            "-Xss16m",
            "-cp", options.hostClasspath.joinToString(File.pathSeparator) { it.absolutePath },
            JobLayout.HOST_MAIN,
            layout.root.absolutePath,
        )
        val builder = ProcessBuilder(command)
            .directory(options.moduleDir)
            .redirectOutput(layout.hostStdout)
            .redirectError(layout.hostStderr)
        val env = builder.environment()
        for ((name, value) in options.env) {
            if (value == null) env.remove(name) else env[name] = value
        }

        val process = builder.start()
        val finished = process.waitFor(options.timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(10, TimeUnit.SECONDS)
        }
        return HostOutcome(
            exitCode = if (finished) process.exitValue() else null,
            timedOut = !finished,
            stderrTail = tail(layout.hostStderr),
        )
    }

    private fun tail(file: File, lines: Int = 30): String =
        if (file.isFile) file.readLines().takeLast(lines).joinToString("\n") else ""
}
