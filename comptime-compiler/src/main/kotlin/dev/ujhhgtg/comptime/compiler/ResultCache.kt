package dev.ujhhgtg.comptime.compiler

import org.jetbrains.kotlin.config.KotlinCompilerVersion
import java.io.File
import java.security.MessageDigest

/**
 * Results of earlier builds, keyed on everything that determines a block's value as far as the build knows: the
 * synthetic source (block text with constants spliced in, imports, result type), the encoder, the declared input
 * hash and env values, the host JDK, the stdlib and the language settings. Undeclared inputs aren't in the key,
 * the same rule as everywhere else: a value is only as fresh as what the build knows about.
 */
class ResultCache(private val dir: File, options: ComptimeOptions, stdlib: File, encoderSource: String) {
    private val base: String = sha256(buildString {
        append("comptime-cache-v1\n")
        append(KotlinCompilerVersion.VERSION).append('\n')
        append(sha256(encoderSource)).append('\n')
        append(options.inputHash ?: "").append('\n')
        options.env.toSortedMap().forEach { (k, v) -> append(k).append('=').append(v ?: "<unset>").append('\n') }
        append(jdkIdentity(options.hostJdkHome)).append('\n')
        append(stdlib.name).append(':').append(stdlib.length()).append(':').append(fileHash(stdlib)).append('\n')
        options.hostCompilerArgs.forEach { append(it).append('\n') }
    })

    fun key(syntheticSource: String): String = sha256(base + "\n" + syntheticSource)

    fun get(key: String): ByteArray? = File(dir, "$key.bin").takeIf { it.isFile }?.readBytes()

    fun put(key: String, bytes: ByteArray) {
        dir.mkdirs()
        val tmp = File.createTempFile("$key.", ".tmp", dir)
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(File(dir, "$key.bin"))) tmp.delete()
    }

    private companion object {
        fun sha256(text: String): String = hex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))

        fun fileHash(file: File): String {
            if (!file.isFile) return "missing"
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return hex(digest.digest())
        }

        /** The JDK's `release` file names its version and vendor; fall back to its path. */
        fun jdkIdentity(home: File): String {
            val release = File(home, "release")
            return if (release.isFile) release.readText() else home.canonicalPath
        }

        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    }
}
