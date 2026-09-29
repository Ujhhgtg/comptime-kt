package dev.ujhhgtg.comptime.host

import dev.ujhhgtg.comptime.protocol.BlockCompiler
import dev.ujhhgtg.comptime.protocol.JobLayout
import dev.ujhhgtg.comptime.protocol.Json
import dev.ujhhgtg.comptime.protocol.asArray
import dev.ujhhgtg.comptime.protocol.asObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import kotlin.system.exitProcess

/**
 * The comptime host: compiles the synthetic block sources of one job and runs each block in its own classloader.
 *
 * Usage: `HostMain <job-dir>`. Reads `manifest.json`, writes `classes/` and `out/`. When the manifest says the job is
 * `precompiled`, the compiler plugin already compiled the blocks in-process and the host only runs them. See
 * docs/plan.md, "Host process and protocol". Always exits through [Runtime.halt] so threads or shutdown hooks left
 * behind by blocks can't keep the process alive.
 */
object HostMain {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size != 1) {
            System.err.println("usage: HostMain <job-dir>")
            exitProcess(2)
        }
        val code = try {
            run(JobLayout(File(args[0])))
            0
        } catch (t: Throwable) {
            t.printStackTrace()
            1
        }
        System.out.flush()
        System.err.flush()
        Runtime.getRuntime().halt(code)
    }

    private fun run(layout: JobLayout) {
        val manifest = Json.parse(layout.manifest.readText()).asObject()
        val stdlib = File(manifest["stdlib"] as String)
        @Suppress("UNCHECKED_CAST")
        val compilerArgs = manifest["compilerArgs"].asArray() as List<String>
        val blockIds = manifest["blocks"].asArray().map { it.asObject()["id"] as String }

        layout.out.mkdirs()
        layout.classes.mkdirs()

        if (manifest["precompiled"] != true) {
            val compiled = BlockCompiler(layout, stdlib, File(System.getProperty("java.home")), compilerArgs).compile()
            if (!compiled) return
        }

        val platformLoader = platformClassLoader()
        for (id in blockIds) {
            layout.started(id).writeText("")
            runBlock(layout, id, stdlib, platformLoader)
        }
    }

    private fun runBlock(layout: JobLayout, id: String, stdlib: File, parent: ClassLoader?) {
        val loader = URLClassLoader(arrayOf(stdlib.toURI().toURL(), layout.classes.toURI().toURL()), parent)
        val stdout = CappedBuffer()
        val stderr = CappedBuffer()
        val originalOut = System.out
        val originalErr = System.err
        var result: ByteArray? = null
        var failure: Throwable? = null

        val worker = Thread(null, {
            try {
                val entry = Class.forName(JobLayout.entryClass(id), true, loader).getMethod(JobLayout.ENTRY_FUNCTION)
                result = entry.invoke(null) as ByteArray
            } catch (e: InvocationTargetException) {
                failure = e.targetException
            } catch (t: Throwable) {
                failure = t
            }
        }, "comptime-$id", 512L * 1024 * 1024)
        worker.contextClassLoader = loader
        System.setOut(PrintStream(stdout, true, "UTF-8"))
        System.setErr(PrintStream(stderr, true, "UTF-8"))
        try {
            worker.start()
            worker.join()
        } finally {
            System.out.flush()
            System.err.flush()
            System.setOut(originalOut)
            System.setErr(originalErr)
        }

        val bytes = result
        if (bytes != null) {
            val out = stdout.text()
            val err = stderr.text()
            if (out.isNotEmpty() || err.isNotEmpty()) {
                layout.output(id).writeText(Json.write(linkedMapOf("stdout" to out, "stderr" to err)))
            }
            layout.result(id).writeBytes(bytes)
        } else {
            layout.error(id).writeText(Json.write(describeFailure(id, failure, stdout, stderr)))
        }
    }

    private fun describeFailure(id: String, failure: Throwable?, stdout: CappedBuffer, stderr: CappedBuffer): Map<String, Any?> {
        val t = failure ?: IllegalStateException("block produced no result")
        val kind = if (t.javaClass.name == JobLayout.TYPE_MISMATCH_CLASS) "type-mismatch" else "exception"
        val blockPrefix = "${JobLayout.GEN_PACKAGE}.$id."
        // Keep frames up to the last one belonging to the block; below that are reflection and host frames.
        val frames = t.stackTrace.toList()
        val lastBlockFrame = frames.indexOfLast { it.className.startsWith(blockPrefix) }
        val kept = if (lastBlockFrame >= 0) frames.subList(0, lastBlockFrame + 1) else frames
        val causes = generateSequence(t.cause) { it.cause }.take(10).map {
            mapOf("exception" to it.javaClass.name, "message" to it.message)
        }.toList()
        return linkedMapOf(
            "kind" to kind,
            "exception" to t.javaClass.name,
            "message" to t.message,
            "stackTrace" to kept.map {
                mapOf("cls" to it.className, "method" to it.methodName, "file" to it.fileName, "line" to it.lineNumber.toLong())
            },
            "causes" to causes,
            "stdout" to stdout.text(),
            "stderr" to stderr.text(),
        )
    }

    private fun platformClassLoader(): ClassLoader? =
        try {
            ClassLoader::class.java.getMethod("getPlatformClassLoader").invoke(null) as ClassLoader
        } catch (_: NoSuchMethodException) {
            ClassLoader.getSystemClassLoader().parent // JDK 8: the extension class loader
        }
}

/** Collects captured output, keeping at most [limit] bytes. */
private class CappedBuffer(private val limit: Int = 64 * 1024) : OutputStream() {
    private val buffer = ByteArrayOutputStream()
    private var truncated = false

    @Synchronized
    override fun write(b: Int) {
        if (buffer.size() < limit) buffer.write(b) else truncated = true
    }

    @Synchronized
    override fun write(b: ByteArray, off: Int, len: Int) {
        val room = limit - buffer.size()
        if (len > room) truncated = true
        if (room > 0) buffer.write(b, off, minOf(len, room))
    }

    @Synchronized
    fun text(): String = buffer.toString("UTF-8") + if (truncated) "\n[output truncated]" else ""
}
