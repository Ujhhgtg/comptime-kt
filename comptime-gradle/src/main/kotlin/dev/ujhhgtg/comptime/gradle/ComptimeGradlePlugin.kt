package dev.ujhhgtg.comptime.gradle

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.ClasspathNormalizer
import org.gradle.api.tasks.PathSensitivity
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.jvm.toolchain.JavaToolchainService
import org.jetbrains.kotlin.gradle.plugin.InternalSubpluginOption
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption
import java.time.Duration

/** The `comptime { }` block in a build script. See docs/plan.md, "Gradle plugin and DSL". */
abstract class ComptimeExtension {
    /** JDK to run blocks on. Default: the compile task's JDK, so blocks run on the JDK they were checked against. */
    abstract val jdk: Property<JavaLanguageVersion>

    /** Files and directories blocks read. A change reruns every block in the module. */
    abstract val inputs: ConfigurableFileCollection

    /** Env vars blocks read. Their current values are forced onto the host, and a change reruns every block. */
    abstract val env: SetProperty<String>

    /** Timeout for the whole host process, host compile included. Default 60 s. */
    abstract val timeout: Property<Duration>

    /**
     * Reuse results across builds when a block's text, constants, declared inputs, env values and JDK are unchanged.
     * Default true. The cache lives under `build/comptime`, so `clean` empties it.
     */
    abstract val cache: Property<Boolean>
}

/**
 * Wires the comptime compiler plugin into every Kotlin/JVM compilation: adds `comptime-runtime` as `compileOnly`,
 * resolves the host classpath and JDK, registers declared inputs on the compile task, and passes the plugin its
 * options. Registers no tasks.
 */
class ComptimeGradlePlugin : KotlinCompilerPluginSupportPlugin {
    override fun apply(target: Project) {
        val extension = target.extensions.create("comptime", ComptimeExtension::class.java)
        extension.timeout.convention(Duration.ofSeconds(60))
        extension.cache.convention(true)
        extension.env.convention(emptySet())

        target.configurations.register(HOST_CONFIGURATION) {
            it.isCanBeConsumed = false
            it.isCanBeResolved = true
            it.description = "Classpath of the comptime host: comptime-host and kotlin-compiler-embeddable"
            it.defaultDependencies { deps -> deps.add(target.dependencies.create("$GROUP:comptime-host:$COMPTIME_VERSION")) }
        }
    }

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean {
        val platform = kotlinCompilation.platformType
        if (platform == KotlinPlatformType.jvm || platform == KotlinPlatformType.androidJvm) return true
        if (platform != KotlinPlatformType.common) {
            kotlinCompilation.target.project.logger.warn(
                "comptime: '${kotlinCompilation.target.project.path}' compilation '${kotlinCompilation.name}' targets $platform; " +
                    "comptime only supports Kotlin/JVM, so it is not applied there"
            )
        }
        return false
    }

    override fun getCompilerPluginId(): String = PLUGIN_ID

    override fun getPluginArtifact(): SubpluginArtifact = SubpluginArtifact(GROUP, "comptime-compiler", COMPTIME_VERSION)

    override fun applyToCompilation(kotlinCompilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> {
        val project = kotlinCompilation.target.project
        val extension = project.extensions.getByType(ComptimeExtension::class.java)
        val hostClasspath: Configuration = project.configurations.getByName(HOST_CONFIGURATION)
        val providers = project.providers

        project.dependencies.add(
            kotlinCompilation.defaultSourceSet.compileOnlyConfigurationName,
            "$GROUP:comptime-runtime:$COMPTIME_VERSION",
        )

        val launcher: Provider<JavaLauncher> = extension.jdk.flatMap { version ->
            project.extensions.getByType(JavaToolchainService::class.java).launcherFor { it.languageVersion.set(version) }
        }

        // Paths go as internal options: absolute paths in the cache key would break relocation. What they point at
        // is tracked as normalized task inputs instead.
        kotlinCompilation.compileTaskProvider.configure { task ->
            task.inputs.files(hostClasspath).withPropertyName("comptimeHostClasspath").withNormalizer(ClasspathNormalizer::class.java)
            task.inputs.files(extension.inputs).withPropertyName("comptimeInputs").withPathSensitivity(PathSensitivity.RELATIVE)
            task.inputs.property("comptimeHostJdk", launcher.map { it.describe() }).optional(true)
        }

        val moduleDir = project.projectDir
        val compilationDir = "comptime/${kotlinCompilation.target.name}/${kotlinCompilation.name}"
        val jobDir = project.layout.buildDirectory.dir(compilationDir)
        val cacheDir = project.layout.buildDirectory.dir("$compilationDir/cache")
        val inputHash = providers.of(InputHashSource::class.java) {
            it.parameters.files.from(extension.inputs)
            it.parameters.root.set(project.layout.projectDirectory)
        }

        return project.provider {
            buildList<SubpluginOption> {
                add(InternalSubpluginOption("hostClasspath", hostClasspath.asPath))
                add(InternalSubpluginOption("moduleDir", moduleDir.absolutePath))
                add(InternalSubpluginOption("jobDir", jobDir.get().asFile.absolutePath))
                launcher.orNull?.let { add(InternalSubpluginOption("javaExecutable", it.executablePath.asFile.absolutePath)) }
                if (extension.cache.get()) add(InternalSubpluginOption("cacheDir", cacheDir.get().asFile.absolutePath))
                add(SubpluginOption("timeout", extension.timeout.get().seconds.toString()))
                add(SubpluginOption("inputHash", inputHash.get()))
                // Env values are plain options, so they are task inputs: a changed value reruns the compile.
                for (name in extension.env.get().sorted()) {
                    val value = providers.environmentVariable(name).orNull
                    add(if (value != null) SubpluginOption("env", "$name=$value") else SubpluginOption("envUnset", name))
                }
            }.map { it.encoded() }
        }
    }

    /** `-P` splits values on commas; the compiler plugin decodes this (see `ComptimeCommandLineProcessor.decode`). */
    private fun SubpluginOption.encoded(): SubpluginOption {
        val encoded = value.replace("%", "%25").replace(",", "%2C")
        return when (this) {
            is InternalSubpluginOption -> InternalSubpluginOption(key, encoded)
            else -> SubpluginOption(key, encoded)
        }
    }

    private fun JavaLauncher.describe(): String =
        "${metadata.languageVersion} ${metadata.vendor} ${metadata.javaRuntimeVersion}"

    private companion object {
        const val PLUGIN_ID = "dev.ujhhgtg.comptime"
        const val GROUP = "dev.ujhhgtg.comptime"
        const val HOST_CONFIGURATION = "comptimeHost"
    }
}
