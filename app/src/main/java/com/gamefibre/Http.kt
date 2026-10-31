package com.gamefibre

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI

internal class HttpResponse(val status: Int, private val headers: Map<String, List<String>>, val body: ByteArray) {
    val ok: Boolean get() = status in 200..299
    val text: String get() = String(body, Charsets.UTF_8)
    fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()
}

/**
 * One blocking HTTPS exchange for JSON-style provider APIs: host allow-list, no redirects, bounded body.
 * Callers keep their own timeouts, limits, agents and status policy; streaming transports do not use this.
 */
internal object Http {
    const val DEFAULT_AGENT = "GameFibre/1.0"
    private const val ERROR_BODY_LIMIT = 64 * 1024

    fun validate(address: String, hosts: Set<String>): URI {
        val uri = URI(address)
        require(uri.scheme == "https" && uri.userInfo == null && uri.port == -1 && uri.host in hosts) { "Unexpected provider address" }
        return uri
    }

    fun exchange(
        uri: URI,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        contentType: String? = "application/x-www-form-urlencoded",
        connectTimeout: Int = 15_000,
        readTimeout: Int = 30_000,
        maxBytes: Int = 8 * 1024 * 1024,
        userAgent: String? = DEFAULT_AGENT,
        accept: String? = "application/json",
        tooLarge: String = "The response is too large.",
    ): HttpResponse {
        val connection = uri.toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = connectTimeout; connection.readTimeout = readTimeout
            connection.instanceFollowRedirects = false; connection.useCaches = false
            userAgent?.let { connection.setRequestProperty("User-Agent", it) }
            accept?.let { connection.setRequestProperty("Accept", it) }
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            body?.let {
                connection.requestMethod = "POST"; connection.doOutput = true
                contentType?.let { type -> connection.setRequestProperty("Content-Type", type) }
                connection.outputStream.use { stream -> stream.write(it) }
            }
            val status = connection.responseCode
            val received = connection.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase() }
            val bytes = if (status in 200..299) connection.inputStream.use { read(it, maxBytes, tooLarge) }
                else connection.errorStream?.use { read(it, ERROR_BODY_LIMIT, null) } ?: ByteArray(0)
            return HttpResponse(status, received, bytes)
        } finally { connection.disconnect() }
    }

    /** With a message the limit is an error; without one the body is truncated (error bodies are diagnostic only). */
    internal fun read(input: InputStream, limit: Int, tooLarge: String?): ByteArray {
        val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer); if (count < 0) break
            if (output.size() + count > limit) {
                if (tooLarge != null) throw IllegalStateException(tooLarge)
                output.write(buffer, 0, limit - output.size()); break
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
