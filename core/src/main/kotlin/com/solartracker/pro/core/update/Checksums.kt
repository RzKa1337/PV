package com.solartracker.pro.core.update

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** SHA-256 checksums published with a release in the standard `sha256sum` format. */
object Checksums {

    const val FILE_NAME = "SHA256SUMS"

    private val LINE = Regex("""^([0-9a-fA-F]{64})\s+\*?(.+)$""")

    /** Parses "`<hex>  <file name>`" lines into file name → lowercase hex. Invalid lines are ignored. */
    fun parse(text: String): Map<String, String> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { LINE.matchEntire(it) }
            .associate { m -> m.groupValues[2].trim().substringAfterLast('/') to m.groupValues[1].lowercase() }

    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256(file: File): String = file.inputStream().use { sha256(it) }

    /** Constant-time comparison of two hex digests. */
    fun matches(expectedHex: String, actualHex: String): Boolean =
        MessageDigest.isEqual(expectedHex.lowercase().toByteArray(), actualHex.lowercase().toByteArray())
}
