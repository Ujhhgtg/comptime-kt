package dev.ujhhgtg.comptime.gradle

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.ClasspathNormalizer
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption
import org.jetbrains.kotlin.gradle.plugin.InternalSubpluginOption
import java.time.Duration

/** The `comptime { }` block in a build script. Phase 3 adds `inputs` and `env`, phase 4 `jdk`. */
abstract class ComptimeExtension {
    /** Timeout for the whole host process, host compile included. Default 60 s. */
    abstract val timeout: Property<Duration>
}

/**
 * Wires the comptime compiler plugin into every Kotlin/JVM compilation: adds `comptime-runtime` as `compileOnly`,
 * resolves the host classpath, and passes the plugin its options. Registers no tasks. See docs/plan.md,
 * "Gradle plugin and DSL".
 */
class ComptimeGradlePlugin : KotlinCompilerPluginSupportPlugin {
    override fun apply(target: Project) {
        val extension = target.extensions.create("comptime", ComptimeExtension::class.java)
        extension.timeout.convention(Duration.ofSeconds(60))

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
            kotlinCompilation.project.logger.warn(
                "comptime: '${kotlinCompilation.project.path}' compilation '${kotlinCompilation.name}' targets $platform; " +
                    "comptime only supports Kotlin/JVM, so it is not applied there"
            )
        }
        return false
    }

    override fun getCompilerPluginId(): String = PLUGIN_ID

    override fun getPluginArtifact(): SubpluginArtifact = SubpluginArtifact(GROUP, "comptime-compiler", COMPTIME_VERSION)

    override fun applyToCompilation(kotlinCompilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> {
        val project = kotlinCompilation.project
        val extension = project.extensions.getByType(ComptimeExtension::class.java)
        val hostClasspath: Configuration = project.configurations.getByName(HOST_CONFIGURATION)

        project.dependencies.add(
            kotlinCompilation.defaultSourceSet.compileOnlyConfigurationName,
            "$GROUP:comptime-runtime:$COMPTIME_VERSION",
        )

        // Paths go as internal options (absolute paths in the cache key would break relocation); the host classpath
        // is tracked as a normalized classpath input instead.
        kotlinCompilation.compileTaskProvider.configure { task ->
            task.inputs.files(hostClasspath).withPropertyName("comptimeHostClasspath").withNormalizer(ClasspathNormalizer::class.java)
        }

        val moduleDir = project.projectDir
        val jobDir = project.layout.buildDirectory.dir("comptime/${kotlinCompilation.target.name}/${kotlinCompilation.name}")
        return project.provider {
            listOf(
                InternalSubpluginOption("hostClasspath", hostClasspath.asPath),
                InternalSubpluginOption("moduleDir", moduleDir.absolutePath),
                InternalSubpluginOption("jobDir", jobDir.get().asFile.absolutePath),
                SubpluginOption("timeout", extension.timeout.get().seconds.toString()),
            )
        }
    }

    private companion object {
        const val PLUGIN_ID = "dev.ujhhgtg.comptime"
        const val GROUP = "dev.ujhhgtg.comptime"
        const val HOST_CONFIGURATION = "comptimeHost"
    }
}
