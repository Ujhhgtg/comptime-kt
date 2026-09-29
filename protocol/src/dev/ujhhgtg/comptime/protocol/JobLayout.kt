package dev.ujhhgtg.comptime.protocol

import java.io.File

/**
 * File layout of a comptime job directory. The compiler plugin writes `manifest.json` and `src/`; the host
 * writes `classes/` and `out/`. See docs/plan.md, "Job directory".
 */
internal class JobLayout(val root: File) {
    val manifest = File(root, "manifest.json")
    val src = File(root, "src")
    val classes = File(root, "classes")
    val out = File(root, "out")
    val compileResult = File(out, "compile.json")
    val hostStdout = File(root, "host.stdout")
    val hostStderr = File(root, "host.stderr")

    fun source(id: String) = File(src, "$id.kt")
    fun started(id: String) = File(out, "$id.started")
    fun result(id: String) = File(out, "$id.bin")
    fun error(id: String) = File(out, "$id.err")

    companion object {
        const val HOST_MAIN = "dev.ujhhgtg.comptime.host.HostMain"
        const val ENTRY_FUNCTION = "comptimeEntry"
        const val GEN_PACKAGE = "comptimegen"
        const val ENCODER_PACKAGE = "comptimegen.enc"
        const val TYPE_MISMATCH_CLASS = "comptimegen.enc.ComptimeTypeMismatch"

        /** JVM class holding the entry function of block [id]. */
        fun entryClass(id: String) = "$GEN_PACKAGE.$id.${id.replaceFirstChar { it.uppercaseChar() }}Kt"
    }
}
