package dev.ujhhgtg.comptime.compiler

import java.io.File

/**
 * A source file's text exactly as the compiler saw it: decoded as UTF-8 (a BOM stays, as one char), with every
 * CRLF or lone CR turned into LF. IR offsets are UTF-16 char offsets into this text (see
 * `readSourceFileWithMapping` in the compiler).
 */
class SourceText(val path: String, val text: String) {
    private val lineStarts: IntArray = buildList {
        add(0)
        text.forEachIndexed { i, c -> if (c == '\n') add(i + 1) }
    }.toIntArray()

    /** 1-based line of [offset]. */
    fun line(offset: Int): Int {
        val idx = lineStarts.binarySearch(offset.coerceIn(0, text.length))
        return (if (idx >= 0) idx else -idx - 2) + 1
    }

    /** 1-based column of [offset]. */
    fun column(offset: Int): Int = offset.coerceIn(0, text.length) - lineStarts[line(offset) - 1] + 1

    /** Offset of the 1-based [line] and [column], clamped to the text. */
    fun offset(line: Int, column: Int): Int {
        val l = line.coerceIn(1, lineStarts.size)
        return (lineStarts[l - 1] + column - 1).coerceIn(0, text.length)
    }

    fun slice(start: Int, end: Int): String = text.substring(start, end)

    companion object {
        fun read(file: File): SourceText = SourceText(file.path, normalize(file.readText(Charsets.UTF_8)))

        fun normalize(raw: String): String {
            if ('\r' !in raw) return raw
            val sb = StringBuilder(raw.length)
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                if (c == '\r') {
                    sb.append('\n')
                    if (i + 1 < raw.length && raw[i + 1] == '\n') i++
                } else {
                    sb.append(c)
                }
                i++
            }
            return sb.toString()
        }
    }
}
