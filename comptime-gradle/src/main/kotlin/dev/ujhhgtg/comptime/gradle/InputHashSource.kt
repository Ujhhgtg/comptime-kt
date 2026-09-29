package dev.ujhhgtg.comptime.gradle

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import java.security.MessageDigest

/**
 * SHA-256 over the declared `comptime { inputs }`: every file's path relative to the project and its bytes, in a
 * stable order, directories walked recursively. A [ValueSource], so the configuration cache re-checks it on every
 * build. The hash is passed to the compiler plugin as the `inputHash` option: a backstop that makes a changed input
 * change the compiler arguments, and a record in the job manifest and the result cache key.
 */
abstract class InputHashSource : ValueSource<String, InputHashSource.Parameters> {
    interface Parameters : ValueSourceParameters {
        val files: ConfigurableFileCollection
        val root: DirectoryProperty
    }

    override fun obtain(): String {
        val root = parameters.root.get().asFile
        val digest = MessageDigest.getInstance("SHA-256")
        val entries = parameters.files.files
            .flatMap { f -> if (f.isDirectory) f.walkTopDown().filter { it.isFile }.toList() else listOf(f) }
            .distinct()
            .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
        for (file in entries) {
            digest.update(file.relativeTo(root).invariantSeparatorsPath.toByteArray())
            digest.update(0)
            if (file.isFile) {
                digest.update(1)
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        digest.update(buffer, 0, n)
                    }
                }
            } else {
                digest.update(2) // declared but missing
            }
            digest.update(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
