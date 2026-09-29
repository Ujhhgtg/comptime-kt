package dev.ujhhgtg.comptime.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.cli.jvm.config.jvmClasspathRoots
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.AnalysisFlags
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.config.languageVersionSettings
import java.io.File
import java.util.zip.ZipFile

class ComptimeCompilerPluginRegistrar : CompilerPluginRegistrar() {
    override val pluginId: String = ComptimeCommandLineProcessor.PLUGIN_ID
    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        IrGenerationExtension.registerExtension(ComptimeIrGenerationExtension { optionsFrom(configuration) })
    }

    private fun optionsFrom(configuration: CompilerConfiguration): ComptimeOptions {
        val keys = ComptimeConfigurationKeys
        val moduleDir = configuration.get(keys.MODULE_DIR)?.let(::File) ?: File(".").absoluteFile
        val env = LinkedHashMap<String, String?>()
        configuration.getList(keys.ENV).forEach { entry ->
            val eq = entry.indexOf('=')
            require(eq > 0) { "comptime: env option must be NAME=VALUE, got '$entry'" }
            env[entry.substring(0, eq)] = entry.substring(eq + 1)
        }
        configuration.getList(keys.ENV_UNSET).forEach { env[it] = null }

        return ComptimeOptions(
            hostClasspath = configuration.getList(keys.HOST_CLASSPATH).map(::File),
            javaExecutable = configuration.get(keys.JAVA_EXECUTABLE)?.let(::File) ?: defaultJava(configuration),
            timeoutSeconds = configuration.get(keys.TIMEOUT)?.toLong() ?: 60,
            moduleDir = moduleDir,
            jobDir = configuration.get(keys.JOB_DIR)?.let(::File) ?: File(moduleDir, "build/comptime/main"),
            env = env,
            stdlib = configuration.get(keys.STDLIB)?.let(::File) ?: findStdlib(configuration.jvmClasspathRoots),
            sizeLimitBytes = configuration.get(keys.SIZE_LIMIT)?.toInt() ?: 48 * 1024,
            hostCompilerArgs = hostCompilerArgs(configuration),
        )
    }

    /** The compile's `-jdk-home`, so blocks run on the JDK they were type-checked against; else our own JDK. */
    private fun defaultJava(configuration: CompilerConfiguration): File {
        val jdkHome = configuration.get(JVMConfigurationKeys.JDK_HOME)
            ?.takeUnless { configuration.getBoolean(JVMConfigurationKeys.NO_JDK) }
            ?: File(System.getProperty("java.home"))
        val exe = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        return File(jdkHome, "bin/$exe")
    }

    private fun findStdlib(classpath: List<File>): File? =
        classpath.firstOrNull { it.isFile && it.name.matches(STDLIB_JAR) && it.hasEntry("kotlin/Unit.class") }
            ?: classpath.firstOrNull { it.hasEntry("kotlin/Unit.class") && it.hasEntry("kotlin/collections/CollectionsKt.class") }

    private fun File.hasEntry(name: String): Boolean = when {
        isDirectory -> File(this, name).isFile
        isFile -> runCatching { ZipFile(this).use { it.getEntry(name) != null } }.getOrDefault(false)
        else -> false
    }

    /**
     * Language settings forwarded to the host compile: the module's language version and customized features,
     * plus opt-in to every stdlib marker (the main compile already enforced opt-ins).
     */
    private fun hostCompilerArgs(configuration: CompilerConfiguration): List<String> {
        val settings = configuration.languageVersionSettings
        val args = mutableListOf("-language-version", settings.languageVersion.versionString)
        for ((feature, state) in settings.getCustomizedLanguageFeatures()) {
            val sign = if (state == LanguageFeature.State.ENABLED) "+" else "-"
            args += "-XXLanguage:$sign${feature.name}"
        }
        val optIns = (STDLIB_OPT_IN_MARKERS + settings.getFlag(AnalysisFlags.optIn)).distinct()
        optIns.forEach { args += "-opt-in=$it" }
        return args
    }

    private companion object {
        val STDLIB_JAR = Regex("""kotlin-stdlib(-\d[\w.\-]*)?\.jar""")

        val STDLIB_OPT_IN_MARKERS = listOf(
            "kotlin.ExperimentalStdlibApi",
            "kotlin.ExperimentalUnsignedTypes",
            "kotlin.ExperimentalSubclassOptIn",
            "kotlin.contracts.ExperimentalContracts",
            "kotlin.experimental.ExperimentalTypeInference",
            "kotlin.time.ExperimentalTime",
            "kotlin.uuid.ExperimentalUuidApi",
            "kotlin.io.encoding.ExperimentalEncodingApi",
            "kotlin.io.path.ExperimentalPathApi",
            "kotlin.concurrent.atomics.ExperimentalAtomicApi",
        )
    }
}
