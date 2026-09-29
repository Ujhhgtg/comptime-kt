package dev.ujhhgtg.comptime.compiler

import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.config.CompilerConfiguration
import java.io.File

class ComptimeCommandLineProcessor : CommandLineProcessor {
    override val pluginId: String = PLUGIN_ID

    override val pluginOptions: Collection<AbstractCliOption> = listOf(
        CliOption("hostClasspath", "<path>", "Classpath of the comptime host", required = false),
        CliOption("javaExecutable", "<file>", "java executable for the host", required = false),
        CliOption("timeout", "<seconds>", "Timeout for the whole host process", required = false),
        CliOption("moduleDir", "<dir>", "Working directory of blocks", required = false),
        CliOption("jobDir", "<dir>", "Directory for the comptime job", required = false),
        CliOption("env", "<NAME=VALUE>", "Declared env var forced onto the host", required = false, allowMultipleOccurrences = true),
        CliOption("envUnset", "<NAME>", "Declared env var that is unset", required = false, allowMultipleOccurrences = true),
        CliOption("stdlib", "<jar>", "kotlin-stdlib jar for blocks (default: found on the classpath)", required = false),
        CliOption("sizeLimit", "<bytes>", "Estimated bytecode limit per baked value", required = false),
        CliOption("inputHash", "<hash>", "Hash of declared inputs; changing it forces a full rebuild", required = false),
        CliOption("cacheDir", "<dir>", "Result cache directory; omit to disable the cache", required = false),
        CliOption("inProcessCompile", "true|false", "Compile blocks in the compiler process (default true)", required = false),
    )

    override fun processOption(option: AbstractCliOption, value: String, configuration: CompilerConfiguration) =
        apply(option.optionName, decode(value), configuration)

    private fun apply(name: String, value: String, configuration: CompilerConfiguration) {
        val keys = ComptimeConfigurationKeys
        when (name) {
            "hostClasspath" -> configuration.put(keys.HOST_CLASSPATH, value.split(File.pathSeparatorChar).filter { it.isNotEmpty() })
            "javaExecutable" -> configuration.put(keys.JAVA_EXECUTABLE, value)
            "timeout" -> configuration.put(keys.TIMEOUT, value)
            "moduleDir" -> configuration.put(keys.MODULE_DIR, value)
            "jobDir" -> configuration.put(keys.JOB_DIR, value)
            "env" -> configuration.appendList(keys.ENV, value)
            "envUnset" -> configuration.appendList(keys.ENV_UNSET, value)
            "stdlib" -> configuration.put(keys.STDLIB, value)
            "sizeLimit" -> configuration.put(keys.SIZE_LIMIT, value)
            "inputHash" -> configuration.put(keys.INPUT_HASH, value)
            "cacheDir" -> configuration.put(keys.CACHE_DIR, value)
            "inProcessCompile" -> configuration.put(keys.IN_PROCESS_COMPILE, value)
            else -> error("Unknown comptime option $name")
        }
    }

    companion object {
        const val PLUGIN_ID = "dev.ujhhgtg.comptime"

        /**
         * `-P` splits its value on commas, so the Gradle plugin percent-encodes `%` and `,` in option values (env
         * values and paths may contain either).
         */
        fun decode(value: String): String = value.replace("%2C", ",").replace("%25", "%")
    }
}
