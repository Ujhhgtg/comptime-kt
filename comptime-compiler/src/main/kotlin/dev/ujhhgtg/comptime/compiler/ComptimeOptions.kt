package dev.ujhhgtg.comptime.compiler

import org.jetbrains.kotlin.config.CompilerConfigurationKey
import java.io.File

/** Settings of one comptime run, assembled from plugin options and the compiler configuration. */
class ComptimeOptions(
    /** Classpath of the host: comptime-host plus kotlin-compiler-embeddable and its dependencies. */
    val hostClasspath: List<File>,
    /** `java` to launch the host with. */
    val javaExecutable: File,
    val timeoutSeconds: Long,
    /** Working directory of blocks. */
    val moduleDir: File,
    /** Parent of the job directory; the job itself lives in `<jobDir>/job`. */
    val jobDir: File,
    /** Declared env vars forced onto the host process; a `null` value means "remove". */
    val env: Map<String, String?>,
    /** The stdlib jar blocks compile and run against. */
    val stdlib: File?,
    /** Estimated bytecode size above which a baked collection is an error. */
    val sizeLimitBytes: Int,
    /** Extra arguments for the host's compile: language settings and opt-ins. */
    val hostCompilerArgs: List<String>,
    /** Hash of the declared inputs, from the Gradle plugin; part of the result cache key. */
    val inputHash: String?,
    /** Where results are cached across builds, or `null` for no cache. */
    val cacheDir: File?,
    /** Compile blocks inside this (warm) compiler process instead of in the host. */
    val inProcessCompile: Boolean,
) {
    /** The JDK the host runs on: the parent of `bin/java`. */
    val hostJdkHome: File get() = javaExecutable.canonicalFile.parentFile.parentFile
}

object ComptimeConfigurationKeys {
    val HOST_CLASSPATH = CompilerConfigurationKey.create<List<String>>("comptime host classpath")
    val JAVA_EXECUTABLE = CompilerConfigurationKey.create<String>("comptime java executable")
    val TIMEOUT = CompilerConfigurationKey.create<String>("comptime timeout seconds")
    val MODULE_DIR = CompilerConfigurationKey.create<String>("comptime module directory")
    val JOB_DIR = CompilerConfigurationKey.create<String>("comptime job directory")
    val ENV = CompilerConfigurationKey.create<List<String>>("comptime env NAME=VALUE")
    val ENV_UNSET = CompilerConfigurationKey.create<List<String>>("comptime unset env names")
    val STDLIB = CompilerConfigurationKey.create<String>("comptime stdlib jar")
    val SIZE_LIMIT = CompilerConfigurationKey.create<String>("comptime size limit bytes")
    val INPUT_HASH = CompilerConfigurationKey.create<String>("comptime input hash")
    val CACHE_DIR = CompilerConfigurationKey.create<String>("comptime result cache directory")
    val IN_PROCESS_COMPILE = CompilerConfigurationKey.create<String>("comptime in-process compile")
}
