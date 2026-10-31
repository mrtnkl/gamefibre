package com.gamefibre

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

internal object YouTubeQueries {
    const val PREFERENCE = "youtubeQueries"
    val defaults = listOf("longplay no commentary", "gameranx before you buy")
    fun lines(text: String) = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    fun resolve(query: String, game: String) = "$game $query"
    fun searchUrl(query: String) = "https://www.youtube.com/results?search_query=${URLEncoder.encode(query, "UTF-8")}&hl=en"
}

internal data class YouTubeVideo(val id: String, val title: String) {
    val watchUrl get() = "https://www.youtube.com/watch?v=$id"
}

/** Public search results, without an API key. Consent pages and markup changes are explicit failures. */
internal class YouTubeSearch {
    private data class Cached(val video: YouTubeVideo, val at: Long)
    private val cache = object : LinkedHashMap<String, Cached>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?) = size > 100
    }

    suspend fun find(query: String, game: String): YouTubeVideo? = withContext(Dispatchers.IO) {
        val cacheKey = "$game\n$query"
        synchronized(cache) { cache[cacheKey] }?.takeIf { System.currentTimeMillis() - it.at < 6 * 60 * 60 * 1000L }
            ?.let { return@withContext it.video }
        val connection = URI(YouTubeQueries.searchUrl(query)).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            if (connection.responseCode != 200) throw IOException("YouTube search is unavailable")
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    currentCoroutineContext().ensureActive()
                    if (output.size() + count > 8 * 1024 * 1024) throw IOException("YouTube response is too large")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val video = firstYouTubeVideo(bytes.toString(Charsets.UTF_8), game)
            if (video != null) synchronized(cache) { cache[cacheKey] = Cached(video, System.currentTimeMillis()) }
            video
        } finally { connection.disconnect() }
    }
}

/** Only organic videos in the primary results; never advertisements, sidebars, channels or playlists. */
internal fun firstYouTubeVideo(html: String, game: String = ""): YouTubeVideo? {
    val gameTitle = BeatIndex.normalize(game)
    val marker = Regex("(?:var\\s+ytInitialData|window\\[\"ytInitialData\"\\])\\s*=\\s*").find(html)
        ?: throw IOException("YouTube search could not be read")
    val data = JSONTokener(html.substring(marker.range.last + 1)).nextValue() as? JSONObject
        ?: throw IOException("YouTube search could not be read")
    val sections = data.optJSONObject("contents")?.optJSONObject("twoColumnSearchResultsRenderer")
        ?.optJSONObject("primaryContents")?.optJSONObject("sectionListRenderer")?.optJSONArray("contents")
        ?: throw IOException("YouTube search could not be read")
    for (section in 0 until sections.length()) {
        val items = sections.optJSONObject(section)?.optJSONObject("itemSectionRenderer")?.optJSONArray("contents") ?: continue
        for (index in 0 until items.length()) {
            val video = items.optJSONObject(index)?.optJSONObject("videoRenderer") ?: continue
            val id = video.optString("videoId")
            if (!Regex("[A-Za-z0-9_-]{11}").matches(id)) continue
            // Live streams have no duration; prefer a recorded video for these queries.
            if (!video.has("lengthText")) continue
            val title = video.optJSONObject("title") ?: continue
            val runs = title.optJSONArray("runs") ?: JSONArray()
            val label = title.optString("simpleText").ifBlank {
                (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }
            }
            if (label.isNotBlank() && (gameTitle.isBlank() || " ${BeatIndex.normalize(label)} ".contains(" $gameTitle ")))
                return YouTubeVideo(id, label)
        }
    }
    return null
}
