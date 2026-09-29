package dev.ujhhgtg.comptime.compiler

import dev.ujhhgtg.comptime.protocol.JobLayout
import org.jetbrains.kotlin.name.FqName

/** One import directive from a source file's header. */
data class ImportDirective(val text: String, val fqName: FqName, val isStar: Boolean)

/**
 * Builds the synthetic host source of one block: filtered imports, the shadow `comptime`, and the original lambda
 * text with folded constants written back in. See docs/plan.md, "Synthetic file shape".
 */
object SyntheticSource {
    class Result(
        val text: String,
        /** Add to a synthetic line inside the lambda to get the original line. */
        val lineOffset: Int,
        /** First and last synthetic lines that belong to the lambda. */
        val firstLambdaLine: Int,
        val lastLambdaLine: Int,
    )

    fun build(
        id: String,
        source: SourceText,
        sliceStart: Int,
        sliceEnd: Int,
        splices: List<Splice>,
        imports: List<ImportDirective>,
        usedNames: Set<FqName>,
        resultType: ResultType,
    ): Result {
        val body = splice(source, sliceStart, sliceEnd, splices)
        val kept = filterImports(imports, usedNames)

        val header = buildString {
            append("package ${JobLayout.GEN_PACKAGE}.$id\n\n")
            kept.forEach { append(it.text).append('\n') }
            append('\n')
            append("private fun <T> comptime(block: () -> T): T = block()\n\n")
            append("fun ${JobLayout.ENTRY_FUNCTION}(): kotlin.ByteArray = ${JobLayout.ENCODER_PACKAGE}.encode(\n")
            append("    ").append(LiteralRenderer.string(resultType.descriptor)).append(",\n")
            append("    comptime<${resultType.render()}>(\n")
        }
        val firstLambdaLine = header.count { it == '\n' } + 1
        // Pad the lambda's first line to its original column, so columns on that line map back exactly.
        val padding = " ".repeat(source.column(sliceStart) - 1)
        val text = header + padding + body + "\n    )\n)\n"
        val originalLine = source.line(sliceStart)
        return Result(
            text = text,
            lineOffset = originalLine - firstLambdaLine,
            firstLambdaLine = firstLambdaLine,
            lastLambdaLine = firstLambdaLine + body.count { it == '\n' },
        )
    }

    /** The lambda text with every folded constant replaced by its literal, from the last offset to the first. */
    fun splice(source: SourceText, sliceStart: Int, sliceEnd: Int, splices: List<Splice>): String {
        val text = StringBuilder(source.slice(sliceStart, sliceEnd))
        val ordered = splices
            .filter { it.start >= sliceStart && it.end <= sliceEnd }
            .sortedByDescending { it.start }
        var lastStart = Int.MAX_VALUE
        for (splice in ordered) {
            if (splice.end > lastStart) continue // nested in a span already replaced
            // A folded read keeps only the selector's offsets (`MIN_VALUE` in `Int.MIN_VALUE`); take the qualifier too.
            var start = qualifierStart(source.text, splice.start, sliceStart)
            lastStart = start
            val literal = LiteralRenderer.render(splice.const)
            // `$FOO` (or `$$FOO` in a multi-dollar string) inside a template: keep the dollars, add braces.
            var dollars = 0
            while (start - dollars - 1 >= sliceStart && source.text[start - dollars - 1] == '$') dollars++
            val replacement = if (dollars > 0) {
                start -= dollars
                "$".repeat(dollars) + "{" + literal + "}"
            } else {
                literal
            }
            text.replace(start - sliceStart, splice.end - sliceStart, replacement)
        }
        return text.toString()
    }

    /**
     * Walks back from [start] over a qualifier chain like `kotlin.Int.` or `Outer.Companion.`. Only plain
     * identifiers qualify: a receiver with side effects becomes an `IrComposite`, which the analyzer rejects.
     */
    fun qualifierStart(text: String, start: Int, limit: Int): Int {
        var s = start
        while (true) {
            var i = s - 1
            while (i >= limit && text[i].isWhitespace()) i--
            if (i < limit || text[i] != '.' || (i > limit && text[i - 1] == '.')) return s
            i--
            while (i >= limit && text[i].isWhitespace()) i--
            if (i < limit) return s
            val identEnd = i
            if (text[i] == '`') {
                i--
                while (i >= limit && text[i] != '`') i--
                if (i < limit) return s
                i--
            } else {
                while (i >= limit && (text[i].isLetterOrDigit() || text[i] == '_')) i--
            }
            val identStart = i + 1
            if (identStart > identEnd || text[identStart].isDigit()) return s
            if (i >= limit && text[i] == '$') return s // `$Obj.FOO` in a template: the `.FOO` part is plain text
            s = identStart
        }
    }

    private val IMPORT =Regex("""^\s*import\s+([\p{L}\p{N}_`.]+?)(\.\*)?(\s+as\s+[\p{L}\p{N}_`]+)?\s*;?\s*$""")

    /** Import directives of [source], parsed from its header text. */
    fun parseImports(source: SourceText): List<ImportDirective> =
        source.text.lineSequence()
            .map { it.removePrefix("﻿") }
            .mapNotNull { line ->
                val m = IMPORT.matchEntire(line) ?: return@mapNotNull null
                val name = m.groupValues[1].replace("`", "")
                ImportDirective(line.trim(), FqName(name), isStar = m.groupValues[2].isNotEmpty())
            }
            .toList()

    /**
     * Keeps only imports that name something the block uses (or, for star imports, a package it uses): copying an
     * unused import that doesn't resolve on the host would break every block in the job.
     */
    fun filterImports(imports: List<ImportDirective>, usedNames: Set<FqName>): List<ImportDirective> =
        imports.filter { import ->
            if (!BlockAnalyzer.isAllowedPackage(import.fqName)) return@filter false
            usedNames.any { used -> if (import.isStar) !used.isRoot && used.parent().startsWith(import.fqName) else used.startsWith(import.fqName) }
        }
}
