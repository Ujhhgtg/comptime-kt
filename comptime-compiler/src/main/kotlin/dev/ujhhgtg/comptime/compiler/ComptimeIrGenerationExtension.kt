package dev.ujhhgtg.comptime.compiler

import dev.ujhhgtg.comptime.protocol.BlockCompiler
import dev.ujhhgtg.comptime.protocol.JobLayout
import dev.ujhhgtg.comptime.protocol.Json
import dev.ujhhgtg.comptime.protocol.asArray
import dev.ujhhgtg.comptime.protocol.asObject
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrCompositeImpl
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import java.io.File
import java.util.IdentityHashMap

/**
 * Finds every `comptime` call in the module, checks each block, evaluates all valid blocks in one host job, and
 * replaces each call with IR that produces its value. See docs/plan.md, "Compiler plugin".
 */
class ComptimeIrGenerationExtension(private val optionsProvider: () -> ComptimeOptions) : IrGenerationExtension {
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        val sites = moduleFragment.files.flatMap { file -> BlockCollector(file).also { file.acceptVoid(it) }.sites }
        if (sites.isEmpty()) return
        Run(pluginContext, optionsProvider()).execute(sites)
    }

    private class Block(
        val id: String,
        val site: CallSite,
        val source: SourceText,
        val type: ResultType,
        val synthetic: SyntheticSource.Result,
    )

    private class Run(private val context: IrPluginContext, private val options: ComptimeOptions) {
        private val sources = HashMap<IrFile, SourceText?>()
        private val imports = HashMap<IrFile, List<ImportDirective>>()

        fun execute(sites: List<CallSite>) {
            val stdlib = options.stdlib
            if (stdlib == null) {
                sites.forEach { error(it.file, it.call, "comptime: kotlin-stdlib isn't on the compile classpath; pass it with the 'stdlib' plugin option") }
                return
            }
            val blocks = sites.mapIndexedNotNull { i, site -> prepare(site, "b$i") }
            if (blocks.isEmpty()) return
            val values = evaluate(blocks, stdlib) ?: return
            replace(values)
        }

        // ---------------------------------------------------------------- collect and check

        private fun prepare(site: CallSite, id: String): Block? {
            val call = site.call
            val lambda = BlockCollector.lambdaOf(call)
                ?: return null.also { error(site.file, call, "comptime needs a lambda literal, like comptime { ... }") }

            val source = sourceOf(site.file)
                ?: return null.also { error(site.file, call, "comptime: can't read the source file ${site.file.fileEntry.name}") }

            val sliceStart = sliceStart(source, call, lambda)
                ?: return null.also {
                    error(site.file, call, "comptime must be called by its name or fully qualified name; import aliases aren't supported")
                }
            val sliceEnd = lambda.endOffset
            if (source.text.getOrNull(sliceEnd - 1) != '}') {
                return null.also { error(site.file, call, "comptime: unexpected lambda text; expected a lambda literal in braces") }
            }

            val type = when (val analysis = ResultType.of(call.type)) {
                is ResultType.Analysis.Supported -> analysis.type
                is ResultType.Analysis.Unsupported -> return null.also {
                    error(site.file, call, "comptime result type ${renderType(call)} is not supported: ${analysis.part}. " +
                        "Allowed: primitives, String, unsigned types, List, Set, Map, Array, primitive arrays, their nullable forms, and Unit")
                }
            }

            val analyzer = BlockAnalyzer(lambda).analyze()
            if (analyzer.problems.isNotEmpty()) {
                analyzer.problems.forEach { error(site.file, it.element, it.message) }
                return null
            }

            val synthetic = SyntheticSource.build(
                id, source, sliceStart, sliceEnd, analyzer.splices,
                imports.getOrPut(site.file) { SyntheticSource.parseImports(source) },
                analyzer.usedNames, type,
            )
            return Block(id, site, source, type, synthetic)
        }

        private fun renderType(call: IrCall): String = call.type.render()

        private fun sourceOf(file: IrFile): SourceText? = sources.getOrPut(file) {
            File(file.fileEntry.name).takeIf { it.isFile }?.let(SourceText::read)
        }

        /**
         * Start of the text to paste into the host: the lambda, plus its label if the label sits outside the lambda's
         * span. Returns null when the callee isn't spelled `comptime` (or fully qualified), e.g. through an alias.
         */
        private fun sliceStart(source: SourceText, call: IrCall, lambda: IrFunctionExpression): Int? {
            val prefix = source.slice(call.startOffset, lambda.startOffset)
            val m = CALL_PREFIX.matchEntire(prefix) ?: return null
            val label = m.groups["label"] ?: return lambda.startOffset
            return call.startOffset + label.range.first
        }

        // ---------------------------------------------------------------- evaluate

        private fun evaluate(blocks: List<Block>, stdlib: File): Map<Block, ResultValue>? {
            val cache = options.cacheDir?.let { ResultCache(it, options, stdlib, ENCODER_SOURCE) }
            val keys = blocks.associateWith { cache?.key(it.synthetic.text) }
            val values = LinkedHashMap<Block, ResultValue>()
            val misses = blocks.filter { block ->
                val cached = keys[block]?.let { cache?.get(it) }
                if (cached != null) values[block] = ResultValue.decode(cached)
                cached == null
            }
            if (misses.isEmpty()) return values

            val fresh = run(misses, stdlib) ?: return null
            for ((block, bytes) in fresh) {
                values[block] = ResultValue.decode(bytes)
                keys[block]?.let { cache?.put(it, bytes) }
            }
            return values
        }

        /** Runs [blocks] in one job and returns each block's encoded result, or `null` after reporting errors. */
        private fun run(blocks: List<Block>, stdlib: File): Map<Block, ByteArray>? {
            val layout = JobLayout(File(options.jobDir, "job"))
            layout.root.deleteRecursively()
            layout.src.mkdirs()
            layout.out.mkdirs()

            for (block in blocks) layout.source(block.id).writeText(block.synthetic.text)
            File(layout.src, "encoder.kt").writeText(ENCODER_SOURCE)

            // Compile in this process when we can: the Kotlin daemon is warm, a fresh host JVM isn't.
            val precompiled = options.inProcessCompile && compileInProcess(layout, stdlib)
            if (precompiled && Json.parse(layout.compileResult.readText()).asObject()["success"] != true) {
                reportCompileErrors(blocks, Json.parse(layout.compileResult.readText()).asObject())
                return null
            }

            layout.manifest.writeText(Json.write(linkedMapOf(
                "version" to 1L,
                "stdlib" to stdlib.absolutePath,
                "timeoutSeconds" to options.timeoutSeconds,
                "compilerArgs" to options.hostCompilerArgs,
                "precompiled" to precompiled,
                "inputHash" to options.inputHash,
                "env" to options.env.keys.sorted(),
                "blocks" to blocks.map { b ->
                    linkedMapOf(
                        "id" to b.id,
                        "file" to b.source.path,
                        "line" to b.source.line(b.site.call.startOffset).toLong(),
                        "column" to b.source.column(b.site.call.startOffset).toLong(),
                        "lineOffset" to b.synthetic.lineOffset.toLong(),
                        "descriptor" to b.type.descriptor,
                    )
                },
            )))

            val outcome = try {
                HostRunner(options).run(layout)
            } catch (e: Exception) {
                blocks.forEach { error(it, "comptime: couldn't start the host: ${e.message}") }
                return null
            }

            if (!layout.compileResult.isFile) {
                blocks.forEach { error(it, hostDiedMessage(outcome, layout, "before compiling the blocks")) }
                return null
            }
            val compile = Json.parse(layout.compileResult.readText()).asObject()
            if (compile["success"] != true) {
                reportCompileErrors(blocks, compile)
                return null
            }

            val results = LinkedHashMap<Block, ByteArray>()
            var running: Block? = null
            for (block in blocks) {
                when {
                    layout.result(block.id).isFile -> {
                        results[block] = layout.result(block.id).readBytes()
                        layout.output(block.id).takeIf { it.isFile }?.let { reportOutput(block, Json.parse(it.readText()).asObject()) }
                    }
                    layout.error(block.id).isFile -> reportBlockError(block, Json.parse(layout.error(block.id).readText()).asObject())
                    layout.started(block.id).isFile -> running = block
                    else -> error(block, when {
                        outcome.timedOut -> "comptime block never ran: the host timed out after ${options.timeoutSeconds} s while running an earlier block" +
                            (running?.let { " (${it.where()})" } ?: "")
                        else -> "comptime block never ran: " + hostDiedMessage(outcome, layout, "while running an earlier block")
                    })
                }
            }
            running?.let { block ->
                error(block, when {
                    outcome.timedOut -> "comptime timed out after ${options.timeoutSeconds} s while running this block"
                    outcome.exitCode == 0 -> "comptime: the host exited while running this block; did it call System.exit?"
                    else -> hostDiedMessage(outcome, layout, "while running this block")
                })
            }
            if (results.size != blocks.size) return null
            return results
        }

        /**
         * Compiles the job's sources with the compiler this plugin runs in. Returns false, leaving the compile to the
         * host, if that fails for any reason other than errors in the blocks themselves.
         */
        private fun compileInProcess(layout: JobLayout, stdlib: File): Boolean = try {
            BlockCompiler(layout, stdlib, options.hostJdkHome, options.hostCompilerArgs).compile()
            val result = Json.parse(layout.compileResult.readText()).asObject()
            val crashed = result["diagnostics"].asArray().any { it.asObject()["severity"] == "exception" }
            if (crashed) {
                layout.compileResult.delete()
                layout.classes.deleteRecursively()
            }
            !crashed
        } catch (e: Throwable) {
            layout.compileResult.delete()
            layout.classes.deleteRecursively()
            false
        }

        private fun reportOutput(block: Block, output: Map<String, Any?>) {
            val text = buildString {
                append("comptime block printed:")
                (output["stdout"] as? String)?.takeIf { it.isNotEmpty() }?.let { append("\n").append(it.trimEnd().prependIndent("    ")) }
                (output["stderr"] as? String)?.takeIf { it.isNotEmpty() }?.let { append("\n  stderr:\n").append(it.trimEnd().prependIndent("    ")) }
            }
            context.diagnosticReporter.at(block.site.call, block.site.file).report(ComptimeErrors.COMPTIME_OUTPUT, text)
        }

        private fun hostDiedMessage(outcome: HostOutcome, layout: JobLayout, what: String): String = when {
            outcome.timedOut -> "comptime timed out after ${options.timeoutSeconds} s $what"
            else -> "comptime host exited with code ${outcome.exitCode} $what" +
                (if (outcome.stderrTail.isNotBlank()) ":\n${outcome.stderrTail}" else "") +
                "\n(job directory: ${layout.root})"
        }

        private fun reportCompileErrors(blocks: List<Block>, compile: Map<String, Any?>) {
            val byFile = blocks.associateBy { "${it.id}.kt" }
            val diagnostics = compile["diagnostics"].asArray().map { it.asObject() }
            if (diagnostics.isEmpty()) {
                blocks.forEach { error(it, "comptime: the host compile failed (${compile["exitCode"]}) without diagnostics") }
                return
            }
            for (d in diagnostics) {
                val message = d["message"] as? String ?: "unknown error"
                val block = byFile[d["file"] as? String]
                val line = (d["line"] as? Long)?.toInt()
                val column = (d["column"] as? Long)?.toInt() ?: 1
                if (block == null) {
                    error(blocks.first(), "comptime: internal host compile error in ${d["file"]}:${line ?: "?"}: $message")
                    continue
                }
                val synthetic = block.synthetic
                if (line != null && line in synthetic.firstLambdaLine..synthetic.lastLambdaLine) {
                    val offset = block.source.offset(line + synthetic.lineOffset, column)
                    errorAt(block.site.file, offset, offset + 1, "comptime block doesn't compile against the JDK and kotlin-stdlib alone: $message")
                } else {
                    error(block, "comptime block doesn't compile on the host: $message (synthetic ${block.id}.kt:${line ?: "?"})")
                }
            }
        }

        private fun reportBlockError(block: Block, err: Map<String, Any?>) {
            val message = err["message"] as? String
            if (err["kind"] == "type-mismatch") {
                error(block, "comptime block's result doesn't match its declared type ${block.type.render()}: $message")
                return
            }
            val text = buildString {
                append("comptime block threw ").append(err["exception"])
                if (message != null) append(": ").append(message)
                val frames = err["stackTrace"].asArray().map { it.asObject() }
                val synthetic = block.synthetic
                var excerptShown = false
                for (frame in frames.take(30)) {
                    val cls = frame["cls"] as String
                    val file = frame["file"] as? String
                    val line = (frame["line"] as? Long)?.toInt() ?: -1
                    if (file == "${block.id}.kt") {
                        // Frames outside the lambda are the synthetic entry point and shadow `comptime`.
                        if (line !in synthetic.firstLambdaLine..synthetic.lastLambdaLine) continue
                        val originalLine = line + synthetic.lineOffset
                        append("\n    at ")
                        append(File(block.source.path).name).append(':').append(originalLine).append(" (comptime block)")
                        if (!excerptShown) {
                            // The line that threw, so the report reads without opening the file.
                            excerptShown = true
                            val text = block.source.text.lines().getOrNull(originalLine - 1)?.trim()
                            if (!text.isNullOrEmpty()) append("\n        > ").append(text)
                        }
                    } else {
                        append("\n    at ")
                        append(cls).append('.').append(frame["method"]).append('(').append(file ?: "Unknown Source")
                        if (line > 0) append(':').append(line)
                        append(')')
                    }
                }
                for (cause in err["causes"].asArray().map { it.asObject() }) {
                    append("\n  caused by ").append(cause["exception"])
                    (cause["message"] as? String)?.let { append(": ").append(it) }
                }
                (err["stdout"] as? String)?.takeIf { it.isNotBlank() }?.let { append("\n  stdout:\n").append(it.trimEnd().prependIndent("    ")) }
                (err["stderr"] as? String)?.takeIf { it.isNotBlank() }?.let { append("\n  stderr:\n").append(it.trimEnd().prependIndent("    ")) }
            }
            error(block, text)
        }

        // ---------------------------------------------------------------- replace

        private fun replace(values: Map<Block, ResultValue>) {
            val builder = ValueBuilder(context)
            val materializer = Materializer(context, builder, options.sizeLimitBytes.toLong())
            val replacements = IdentityHashMap<IrCall, IrExpression>()
            for ((block, value) in values) {
                if (block.type !is ResultType.Scalar && value !is ResultValue.Null) {
                    if (materializer.isInline(block.site, block.type, value)) {
                        // Built in place inside an inline function: it shares the caller's method, so it can't be split.
                        val size = ResultValue.estimateBytecode(value)
                        if (size > options.sizeLimitBytes) {
                            error(block, "comptime result is too large to build inside an inline function: about ${size / 1024} KB of bytecode, " +
                                "over the ${options.sizeLimitBytes / 1024} KB limit; move it to a property outside the inline function")
                            continue
                        }
                    } else {
                        val constants = ResultValue.constantPoolEntries(value)
                        if (constants > MAX_CONSTANTS) {
                            error(block, "comptime result is too large: it needs about $constants class-file constants, over the limit of $MAX_CONSTANTS for one class")
                            continue
                        }
                    }
                }
                replacements[block.site.call] = materializer.materialize(block.site, block.type, value).also {
                    it.type = block.site.call.type
                }
            }
            val transformer = object : IrElementTransformerVoid() {
                override fun visitCall(expression: IrCall): IrExpression =
                    replacements[expression] ?: super.visitCall(expression)
            }
            values.keys.map { it.site.file }.distinct().forEach { it.transformChildrenVoid(transformer) }
            materializer.flush()
        }

        // ---------------------------------------------------------------- diagnostics

        private fun Block.where(): String = "${File(source.path).name}:${source.line(site.call.startOffset)}"

        private fun error(block: Block, message: String) = error(block.site.file, block.site.call, message)

        private fun error(file: IrFile, element: IrElement, message: String) {
            context.diagnosticReporter.at(element, file).report(ComptimeErrors.COMPTIME_ERROR, message)
        }

        private fun errorAt(file: IrFile, start: Int, end: Int, message: String) {
            error(file, IrCompositeImpl(start, end, context.irBuiltIns.unitType), message)
        }
    }

    private companion object {
        /** Class-file constant pool entries a holder class may use; the JVM's hard limit is 65535. */
        const val MAX_CONSTANTS = 60_000

        val CALL_PREFIX = Regex(
            """\s*(?:dev\s*\.\s*ujhhgtg\s*\.\s*comptime\s*\.\s*)?comptime\s*(?:<[\s\S]*>)?\s*(?:\(\s*(?:block\s*=\s*)?)?(?<label>[\p{L}_][\p{L}\p{N}_]*@)?\s*"""
        )

        val ENCODER_SOURCE: String by lazy {
            ComptimeIrGenerationExtension::class.java.getResource("encoder.kt.txt")!!.readText()
        }
    }
}
