package com.solartracker.pro.core.update

import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI

/** A streamed HTTP response; close it when done. */
class HttpResponse(
    val code: Int,
    val headers: Map<String, List<String>>,
    val body: InputStream,
    private val onClose: () -> Unit = {},
) : Closeable {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
    override fun close() {
        runCatching { body.close() }
        onClose()
    }
}

fun interface HttpClient {
    /** Performs a GET without following redirects. */
    @Throws(IOException::class)
    fun get(url: String, headers: Map<String, String>): HttpResponse
}

/** [HttpClient] on top of [HttpURLConnection] (works on the JVM and on Android). */
class UrlConnectionHttpClient(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : HttpClient {
    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
        val code = connection.responseCode
        val body = (if (code >= 400) connection.errorStream else connection.inputStream) ?: ByteArrayInputStream(ByteArray(0))
        val responseHeaders = connection.headerFields.filterKeys { it != null }
        return HttpResponse(code, responseHeaders, body) { connection.disconnect() }
    }
}

class UpdateException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * GET that follows redirects manually. The Authorization header is only sent to the original host:
 * GitHub redirects asset downloads to a storage host with a signed URL, and the user's token must
 * never leak there.
 */
fun HttpClient.getFollowingRedirects(url: String, headers: Map<String, String>, maxRedirects: Int = 5): HttpResponse {
    var current = url
    val originalHost = URI(url).host
    repeat(maxRedirects + 1) {
        val sameHost = URI(current).host.equals(originalHost, ignoreCase = true)
        val requestHeaders = if (sameHost) headers else headers.filterKeys { !it.equals("Authorization", ignoreCase = true) }
        val response = get(current, requestHeaders)
        if (response.code in 300..399) {
            val location = response.header("Location")
            response.close()
            if (location.isNullOrBlank()) throw UpdateException("Przekierowanie bez adresu (HTTP ${response.code})")
            val next = URI(current).resolve(location)
            if (next.scheme != "https" && URI(current).scheme == "https") {
                throw UpdateException("Odrzucono przekierowanie z HTTPS na niezabezpieczony adres")
            }
            current = next.toString()
        } else {
            return response
        }
    }
    throw UpdateException("Zbyt wiele przekierowań")
}
