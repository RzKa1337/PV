package com.solartracker.pro.core.update

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Download progress; [totalBytes] is -1 when unknown. */
data class DownloadProgress(val downloadedBytes: Long, val totalBytes: Long, val attempt: Int) {
    val fraction: Float? get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null
}

/**
 * Downloads a file to disk with resume (HTTP Range) and retries with exponential back-off.
 * Data is written to "<target>.part" and only renamed to [target] when complete.
 */
class Downloader(
    private val http: HttpClient,
    private val retry: RetryPolicy = RetryPolicy(),
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val log: UpdateLog? = null,
) {
    fun download(
        url: String,
        headers: Map<String, String>,
        target: File,
        expectedSize: Long = -1,
        isCancelled: () -> Boolean = { false },
        onProgress: (DownloadProgress) -> Unit = {},
    ): File {
        val part = File(target.parentFile, target.name + ".part")
        target.parentFile?.mkdirs()
        var lastError: Exception? = null
        for (attempt in 1..retry.maxAttempts) {
            if (attempt > 1) {
                val wait = retry.delayBefore(attempt - 1).toMillis()
                log?.warn("Ponawiam pobieranie ${target.name} (próba $attempt/${retry.maxAttempts}) za ${wait} ms")
                sleep(wait)
            }
            try {
                downloadOnce(url, headers, part, expectedSize, attempt, isCancelled, onProgress)
                if (expectedSize > 0 && part.length() != expectedSize) {
                    val size = part.length()
                    // Too short: keep the part file and resume. Too long: it cannot be valid.
                    if (size > expectedSize) part.delete()
                    throw IOException("Nieprawidłowy rozmiar pliku: $size zamiast $expectedSize B")
                }
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) throw IOException("Nie można zapisać ${target.name}")
                return target
            } catch (e: CancelledException) {
                throw e
            } catch (e: UpdateException) {
                throw e // client errors (4xx, bad redirects) are not retried
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw UpdateException("Pobieranie nie powiodło się po ${retry.maxAttempts} próbach: ${lastError?.message}", lastError)
    }

    private fun downloadOnce(
        url: String,
        headers: Map<String, String>,
        part: File,
        expectedSize: Long,
        attempt: Int,
        isCancelled: () -> Boolean,
        onProgress: (DownloadProgress) -> Unit,
    ) {
        val existing = if (part.exists()) part.length() else 0L
        val requestHeaders = if (existing > 0) headers + ("Range" to "bytes=$existing-") else headers
        http.getFollowingRedirects(url, requestHeaders).use { response ->
            val append = when (response.code) {
                206 -> true
                200 -> false
                416 -> {
                    // Range not satisfiable: the part file is complete or invalid – start over.
                    part.delete()
                    throw IOException("Serwer odrzucił wznowienie pobierania")
                }
                in 400..499 -> throw UpdateException("Błąd pobierania (HTTP ${response.code})")
                else -> throw IOException("Błąd serwera (HTTP ${response.code})")
            }
            var downloaded = if (append) existing else 0L
            val total = if (expectedSize > 0) expectedSize else {
                val length = response.header("Content-Length")?.toLongOrNull() ?: -1
                if (length >= 0) length + downloaded else -1
            }
            FileOutputStream(part, append).use { out ->
                val buffer = ByteArray(64 * 1024)
                onProgress(DownloadProgress(downloaded, total, attempt))
                while (true) {
                    if (isCancelled()) throw CancelledException()
                    val n = response.body.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    downloaded += n
                    onProgress(DownloadProgress(downloaded, total, attempt))
                }
            }
        }
    }
}

class CancelledException : IOException("Pobieranie anulowane")
