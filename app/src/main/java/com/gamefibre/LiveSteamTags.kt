package com.gamefibre

import android.util.AtomicFile
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Mutable enrichment only. Never writes the bundled Steam tag table. Load before requesting. */
internal class LiveSteamTags(
    private val directory: File,
    private val fetch: (Int) -> List<String> = ::fetchSteamTags,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class Record(val tags: List<String>, val checkedAt: Long)
    private val records = ConcurrentHashMap<Int, Record>()
    private val attempted = ConcurrentHashMap<Int, Long>()
    private val mutableRevision = MutableStateFlow(0L)
    companion object {
        private val diskLocks = Array(32) { Any() }
    }
    private fun lock(appId: Int) = diskLocks[appId % diskLocks.size]
    val revision = mutableRevision.asStateFlow()
    fun tags(appId: Int): List<String>? = records[appId]?.tags
    fun contains(appId: Int) = records.containsKey(appId)

    suspend fun load() = withContext(Dispatchers.IO) {
        directory.listFiles()?.map { it.name.removeSuffix(".bak").removeSuffix(".new") }
            ?.filter { it.endsWith(".json") }?.distinct()?.forEach { name ->
            val id = name.removeSuffix(".json").toIntOrNull()?.takeIf { it > 0 } ?: return@forEach
            synchronized(lock(id)) { runCatching {
                val data = JSONObject(AtomicFile(File(directory, name)).openRead().bufferedReader().use { it.readText() })
                val tags = GameDetails.strings(data.getJSONArray("tags")).distinct()
                require(tags.isNotEmpty())
                records[id] = Record(tags, data.getLong("checkedAt"))
            } }
        }
        mutableRevision.value++
    }

    /** Failed/empty requests preserve all previous usable tags. */
    suspend fun refresh(appId: Int, force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        require(appId > 0)
        synchronized(lock(appId)) {
        val time = now()
        if (attempted[appId]?.let { time - it < 15 * 60_000L } == true) return@withContext false
        if (!force && records[appId]?.let { time - it.checkedAt < 7 * 86_400_000L } == true) return@withContext false
        attempted[appId] = time
        try {
            val tags = fetch(appId).map(String::trim).filter(String::isNotEmpty).distinct().take(100)
            check(tags.isNotEmpty()) { "Steam community tags are unavailable" }
            directory.mkdirs()
            val file = AtomicFile(File(directory, "$appId.json"))
            val data = JSONObject().put("checkedAt", time).put("tags", JSONArray(tags)).toString().toByteArray()
            val output = file.startWrite()
            try { output.write(data); file.finishWrite(output) }
            catch (error: Exception) { file.failWrite(output); throw error }
            val previous = records.put(appId, Record(tags, time))
            if (previous?.tags != tags) synchronized(mutableRevision) { mutableRevision.value++ }
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
        }
    }
}

/** Steam supplies ranked community tag names and IDs together in its store-page modal data. */
internal fun parseSteamTags(appId: Int, html: String): List<String> {
    val start = Regex("InitAppTagModal\\s*\\(\\s*$appId\\s*,\\s*").find(html)
        ?: error("Steam did not return tags for the requested game")
    val offset = start.range.last + 1
    require(html.getOrNull(offset) == '[')
    var quoted = false
    var escaped = false
    var depth = 0
    for (index in offset until html.length) {
        val char = html[index]
        if (quoted) {
            if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
        } else when (char) {
            '"' -> quoted = true
            '[' -> depth++
            ']' -> if (--depth == 0) {
                val tags = JSONArray(html.substring(offset, index + 1))
                require(tags.length() <= 1000)
                return (0 until tags.length()).mapNotNull {
                    val tag = tags.getJSONObject(it)
                    tag.getString("name").trim().takeIf { name ->
                        tag.optInt("tagid") > 0 && name.isNotBlank() && name.length <= 200
                    }
                }.distinct().take(100)
            }
        }
    }
    error("Incomplete Steam community tag response")
}

private fun fetchSteamTags(appId: Int): List<String> {
    val uri = Http.validate("https://store.steampowered.com/app/$appId/?l=english", setOf("store.steampowered.com"))
    val response = Http.exchange(uri, accept = "text/html", connectTimeout = 10_000, readTimeout = 15_000,
        maxBytes = 4 * 1024 * 1024)
    check(response.status == 200) { "Steam tags are unavailable" }
    return parseSteamTags(appId, response.text)
}
