package com.gamefibre

import android.content.Context
import android.util.AtomicFile
import com.gamefibre.stores.digest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.URLEncoder

internal data class GridGame(val id: Int, val name: String)
internal data class GridArtwork(val id: Int, val url: String, val thumb: String, val author: String)
internal class GridRequestRejected(message: String) : java.io.IOException(message)

/** Only API calls carry the user's key; redirects never receive it. */
internal class SteamGridDb(private val key: String) {
    private fun request(path: String): JSONArray {
        require(key.matches(Regex("[A-Za-z0-9]{16,128}"))) { "Enter a valid SteamGridDB API key." }
        val uri = Http.validate("https://www.steamgriddb.com/api/v2/$path", setOf("www.steamgriddb.com"))
        val response = Http.exchange(uri, headers = mapOf("Authorization" to "Bearer $key"), connectTimeout = 10_000, readTimeout = 15_000,
            maxBytes = 2 * 1024 * 1024, userAgent = null, tooLarge = "SteamGridDB response is too large.")
        when (response.status) {
            401, 403 -> throw GridRequestRejected("SteamGridDB rejected the API key. Update it in Settings → Cover art.")
            404 -> return JSONArray()
            429 -> throw GridRequestRejected("SteamGridDB is rate limiting requests. Try again later.")
            200 -> Unit
            else -> error("SteamGridDB is unavailable. Please try again later.")
        }
        val payload = JSONObject(response.text)
        check(payload.optBoolean("success")) { "SteamGridDB could not complete the request." }
        return payload.optJSONArray("data") ?: JSONArray()
    }
    fun search(title: String): List<GridGame> = parseGames(request("search/autocomplete/" +
        URLEncoder.encode(title.trim().take(200), "UTF-8").replace("+", "%20")))
    fun grids(id: Int, steam: Boolean, page: Int): List<GridArtwork> {
        require(id > 0 && page >= 0)
        return parseGrids(request("grids/${if (steam) "steam" else "game"}/$id?types=static&dimensions=600x900,342x482,660x930&nsfw=false&humor=false&page=$page"))
    }
    companion object {
        fun imageUrl(value: String): Boolean = runCatching {
            val uri = URI(value)
            uri.scheme == "https" && uri.host == "cdn2.steamgriddb.com" && uri.userInfo == null && uri.port == -1
        }.getOrDefault(false)
        fun parseGames(rows: JSONArray): List<GridGame> = (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val id = row.optInt("id"); val name = row.optString("name")
            if (id > 0 && name.isNotBlank()) GridGame(id, name) else null
        }
        fun parseGrids(rows: JSONArray): List<GridArtwork> = (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val url = row.optString("url"); val thumb = row.optString("thumb")
            if (row.optBoolean("nsfw") || row.optBoolean("humor") || !imageUrl(url)) null
            else GridArtwork(row.optInt("id"), url, thumb.takeIf(::imageUrl) ?: url,
                row.optJSONObject("author")?.optString("name").orEmpty())
        }.distinctBy { it.url }
    }
}

/** Selected covers are user data, retained independently of disposable artwork caches. */
internal class CustomArtwork(private val prefs: android.content.SharedPreferences, private val directory: File) {
    constructor(context: Context) : this(context.getSharedPreferences("custom_artwork_v1", Context.MODE_PRIVATE),
        File(context.filesDir, "custom-artwork-v1"))
    fun address(key: String): String? = prefs.getString(key, null)?.takeIf(SteamGridDb::imageUrl)
    fun cacheKey(key: String): String = address(key)?.let { "$key:custom:${digest(it.toByteArray())}" } ?: key
    fun file(key: String): File? = address(key)?.let(::fileForAddress)
    fun fileForAddress(address: String): File? = address.takeIf(SteamGridDb::imageUrl)
        ?.let { File(directory, digest(it.toByteArray()) + ".jpg") }?.takeIf { it.isFile }
    fun backup(): Map<String, String> = synchronized(lock) {
        prefs.all.mapNotNull { (key, value) ->
            (value as? String)?.takeIf(SteamGridDb::imageUrl)?.let { key to it }
        }.toMap()
    }
    fun mergeBackup(covers: Map<String, String>, saveCollection: () -> Unit) = synchronized(lock) {
        val old = prefs.all
        val editor = prefs.edit()
        covers.forEach { (key, address) -> editor.putString(key, address) }
        check(editor.commit()) { "Could not restore custom artwork." }
        try { saveCollection() }
        catch (error: Exception) {
            val undo = prefs.edit().clear()
            old.forEach { (key, value) -> undo.putString(key, value as String) }
            undo.commit()
            throw error
        }
        prune()
    }
    fun clearStore(store: String) = reset(prefs.all.keys.filter { it.startsWith("$store:") })
    fun apply(keys: List<String>, address: String, source: File) = synchronized(lock) {
        require(SteamGridDb.imageUrl(address) && source.isFile)
        directory.mkdirs()
        val target = AtomicFile(File(directory, digest(address.toByteArray()) + ".jpg"))
        val output = target.startWrite()
        try { source.inputStream().use { it.copyTo(output) }; target.finishWrite(output) }
        catch (error: Exception) { target.failWrite(output); throw error }
        val edit = prefs.edit(); keys.forEach { edit.putString(it, address) }
        check(edit.commit()) { "Could not save custom artwork." }
        prune()
    }
    fun reset(keys: List<String>) = synchronized(lock) {
        val edit = prefs.edit(); keys.forEach(edit::remove)
        check(edit.commit()) { "Could not reset custom artwork." }
        prune()
    }
    private companion object { val lock = Any() }
    private fun prune() {
        val retained = prefs.all.values.filterIsInstance<String>().map { digest(it.toByteArray()) + ".jpg" }.toSet()
        directory.listFiles()?.filter { it.name !in retained }?.forEach { it.delete() }
    }
}
