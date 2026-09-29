package dev.ujhhgtg.comptime.protocol

/**
 * Minimal JSON codec for the job directory protocol. Values are `null`, [Boolean], [Long], [Double], [String],
 * [List] and [Map] with string keys. Shared as source between the compiler plugin and the host, which run in
 * different processes and must not depend on anything but the stdlib.
 */
internal object Json {
    fun write(value: Any?): String = StringBuilder().also { writeTo(it, value, 0) }.toString()

    private fun writeTo(out: StringBuilder, value: Any?, indent: Int) {
        when (value) {
            null -> out.append("null")
            is Boolean -> out.append(value)
            is Int, is Long -> out.append(value)
            is Double -> out.append(if (value.isFinite()) value.toString() else "null")
            is String -> writeString(out, value)
            is Map<*, *> -> {
                if (value.isEmpty()) return run { out.append("{}") }
                out.append("{\n")
                value.entries.forEachIndexed { i, (k, v) ->
                    out.append("  ".repeat(indent + 1))
                    writeString(out, k as String)
                    out.append(": ")
                    writeTo(out, v, indent + 1)
                    if (i < value.size - 1) out.append(',')
                    out.append('\n')
                }
                out.append("  ".repeat(indent)).append('}')
            }
            is List<*> -> {
                if (value.isEmpty()) return run { out.append("[]") }
                out.append("[\n")
                value.forEachIndexed { i, v ->
                    out.append("  ".repeat(indent + 1))
                    writeTo(out, v, indent + 1)
                    if (i < value.size - 1) out.append(',')
                    out.append('\n')
                }
                out.append("  ".repeat(indent)).append(']')
            }
            else -> throw IllegalArgumentException("Not a JSON value: ${value::class.java.name}")
        }
    }

    private fun writeString(out: StringBuilder, s: String) {
        out.append('"')
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
            }
        }
        out.append('"')
    }

    fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.value()
        p.skipWs()
        require(p.pos == text.length) { "Trailing characters at ${p.pos}" }
        return v
    }

    private class Parser(val s: String) {
        var pos = 0

        fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun value(): Any? {
            skipWs()
            require(pos < s.length) { "Unexpected end of JSON" }
            return when (val c = s[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) num() else error("Unexpected '$c' at $pos")
            }
        }

        private fun literal(word: String, v: Any?): Any? {
            require(s.startsWith(word, pos)) { "Expected $word at $pos" }
            pos += word.length
            return v
        }

        private fun obj(): Map<String, Any?> {
            pos++
            val m = LinkedHashMap<String, Any?>()
            skipWs()
            if (s[pos] == '}') return m.also { pos++ }
            while (true) {
                skipWs()
                val k = str()
                skipWs()
                require(s[pos] == ':') { "Expected ':' at $pos" }
                pos++
                m[k] = value()
                skipWs()
                when (s[pos++]) {
                    ',' -> continue
                    '}' -> return m
                    else -> error("Expected ',' or '}' at ${pos - 1}")
                }
            }
        }

        private fun arr(): List<Any?> {
            pos++
            val l = ArrayList<Any?>()
            skipWs()
            if (s[pos] == ']') return l.also { pos++ }
            while (true) {
                l += value()
                skipWs()
                when (s[pos++]) {
                    ',' -> continue
                    ']' -> return l
                    else -> error("Expected ',' or ']' at ${pos - 1}")
                }
            }
        }

        private fun str(): String {
            require(s[pos] == '"') { "Expected string at $pos" }
            pos++
            val sb = StringBuilder()
            while (true) {
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = s[pos++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            sb.append(s.substring(pos, pos + 4).toInt(16).toChar())
                            pos += 4
                        }
                        else -> error("Bad escape '\\$e' at ${pos - 1}")
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Any {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            val t = s.substring(start, pos)
            return if (t.any { it in ".eE" }) t.toDouble() else t.toLong()
        }
    }
}

@Suppress("UNCHECKED_CAST")
internal fun Any?.asObject(): Map<String, Any?> = this as Map<String, Any?>

@Suppress("UNCHECKED_CAST")
internal fun Any?.asArray(): List<Any?> = this as List<Any?>
