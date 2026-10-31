package com.gamefibre.stores

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.zip.InflaterInputStream

internal class DownloadIo(private val received: (Long) -> Unit) {
    @Volatile private var connection: HttpURLConnection? = null
    fun interrupt() { connection?.disconnect() }
    private fun safeUrl(url: String): URI {
        val original = URI(url)
        val uri = if (original.scheme == "http") URI("https" + url.removePrefix("http")) else original
        val host = uri.host.orEmpty().lowercase()
        val allowed = listOf("gog.com", "gcdn.co", "gogcdn.net", "footprint.net", "epicgames.com", "epicgamescdn.com", "akamaized.net", "cloudfront.net", "fastly.net")
        require(uri.scheme == "https" && uri.userInfo == null && uri.port in listOf(-1, 443) && allowed.any { host == it || host.endsWith(".$it") }) { "The store returned an unsupported download address." }
        return uri
    }
    private suspend fun open(url: String, range: Pair<Long, Long>? = null, strictRange:Boolean=true): HttpURLConnection {
        var address = safeUrl(url)
        repeat(6) {
            currentCoroutineContext().ensureActive()
            val c = address.toURL().openConnection() as HttpURLConnection
            connection = c; c.connectTimeout = 15_000; c.readTimeout = 30_000; c.instanceFollowRedirects = false
            c.setRequestProperty("Accept-Encoding", "identity"); c.setRequestProperty("User-Agent", "GameFibre/1.0")
            range?.let { c.setRequestProperty("Range", "bytes=${it.first}-${it.first + it.second - 1}") }
            val status = c.responseCode
            if (status in listOf(301,302,303,307,308)) {
                val location = c.getHeaderField("Location") ?: error("The download redirect was empty.")
                c.disconnect(); address = safeUrl(address.resolve(location).toString())
            } else {
                if (status !in 200..299) { c.disconnect(); error(if (status in listOf(401,403)) "The download link expired. Resume to obtain a fresh link." else "The download server returned HTTP $status. Resume to retry.") }
                if (range != null && strictRange) check(status == 206 && c.getHeaderField("Content-Range").orEmpty().startsWith("bytes ${range.first}-")) { c.disconnect(); "The download server did not honor the requested file range." }
                return c
            }
        }
        error("Too many download redirects.")
    }
    suspend fun bytes(urls: List<String>, limit: Int = 64 * 1024 * 1024, range: Pair<Long, Long>? = null): ByteArray {
        require(urls.isNotEmpty())
        var failure: Exception? = null
        repeat(3) { attempt ->
            currentCoroutineContext().ensureActive()
            try {
                val c = open(urls[attempt % urls.size], range)
                try {
                    return c.inputStream.use { input ->
                        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(65536)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer); if (n < 0) break
                            check(out.size().toLong() + n <= limit) { "The downloaded item is too large." }
                            out.write(buffer, 0, n); received(n.toLong())
                        }
                        out.toByteArray().also { if (range != null) check(it.size.toLong() == range.second) { "The download ended before the file range was complete." } }
                    }
                } finally { c.disconnect(); connection = null }
            } catch (e: Exception) { currentCoroutineContext().ensureActive(); failure = e; if (attempt < 2) delay(500L * (attempt + 1)) }
        }
        throw failure ?: IOException("Could not download game data.")
    }
    /** Linux installers can be large; stream them to disk and resume only against the same ETag. */
    suspend fun installer(url: String, target: File, expectedSize: Long, progress: (Long, Long) -> Unit) {
        target.parentFile?.mkdirs()
        val tagFile = File(target.path + ".etag")
        val complete=File(target.path+".complete")
        if(complete.isFile && target.isFile && complete.readText().toLongOrNull()==target.length() && (expectedSize<=0 || expectedSize==target.length()))return
        var offset = if (expectedSize>0 && target.isFile && tagFile.isFile && target.length()<=expectedSize) target.length() else 0L
        if (offset == expectedSize && expectedSize > 0) return
        var c = open(url, if (offset > 0 && expectedSize > offset) offset to (expectedSize-offset) else null,strictRange=false)
        try {
            var tag = c.getHeaderField("ETag").orEmpty()
            if (offset > 0 && (c.responseCode!=206 || !c.getHeaderField("Content-Range").orEmpty().startsWith("bytes $offset-") || tag.isBlank() || tag != tagFile.readText())) {
                c.disconnect(); offset = 0; c = open(url); tag=c.getHeaderField("ETag").orEmpty()
            }
            if (offset == 0L) target.outputStream().close()
            if (tag.isNotBlank()) tagFile.writeText(tag) else tagFile.delete()
            val total = if (expectedSize > 0) expectedSize else c.contentLengthLong + offset
            if (total > 0) check(target.parentFile!!.usableSpace >= total - offset) { "Not enough free space for the GOG Linux installer." }
            var done = offset
            c.inputStream.use { input -> java.io.FileOutputStream(target, offset > 0).use { output ->
                val buffer = ByteArray(65536)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer); if (n < 0) break
                    done += n; check(total <= 0 || done <= total) { "The installer is larger than expected." }
                    output.write(buffer, 0, n); received(n.toLong()); progress(done, total)
                }
                output.fd.sync()
            } }
            check(total <= 0 || target.length() == total) { "The installer download was interrupted." }
            complete.writeText(target.length().toString())
        } finally { c.disconnect(); connection = null }
    }
}

internal fun digest(bytes: ByteArray, algorithm: String = "SHA-256"): String = MessageDigest.getInstance(algorithm).digest(bytes).joinToString("") { "%02x".format(it) }
internal suspend fun fileDigest(file: File, algorithm: String): String {
    val hash = MessageDigest.getInstance(algorithm)
    file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) {
        currentCoroutineContext().ensureActive(); val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n)
    } }
    return hash.digest().joinToString("") { "%02x".format(it) }
}
internal const val MAX_EXPANDED_STORE_BYTES = 128 * 1024 * 1024

internal fun inflate(bytes: ByteArray, limit: Int): ByteArray {
    require(limit in 0..MAX_EXPANDED_STORE_BYTES) { "Invalid expanded store data size." }
    return InflaterInputStream(bytes.inputStream()).use { input ->
        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(65536)
        while (true) { val n = input.read(buffer); if (n < 0) break; check(output.size().toLong()+n <= limit) { "Expanded store data exceeds its declared size." }; output.write(buffer,0,n) }
        output.toByteArray()
    }
}
internal fun gamePath(root: File, path: String): File {
    val normalized = path.replace('\\','/')
    require(normalized.isNotBlank() && normalized.length <= 4096 && !normalized.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(normalized) && '\u0000' !in normalized)
    require(normalized.split('/').none { it == ".." }) { "A store manifest contains an unsafe file path." }
    val result = File(root, normalized).canonicalFile
    check(result.path.startsWith(root.canonicalPath + File.separator)) { "A store file points outside the game folder." }
    return File(root, normalized).absoluteFile
}
