package com.gamefibre

import android.content.Context
import android.util.AtomicFile
import com.gamefibre.stores.StoreApi
import com.gamefibre.stores.StoreGame
import com.gamefibre.stores.StoreSession
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Public game details, requested only for an opened game page and retained across restarts. */
internal class PageDetailsRepository(context: Context) {
    private val directory = File(context.filesDir, "game-page-details-v1")
    private val preferences = context.getSharedPreferences("game-page-details-cache", Context.MODE_PRIVATE)
    private val retained = RetainedDetailsCache()
    companion object {
        private val locks = Array(16) { Any() }
        private val diskLocks = Array(16) { Any() }
    }
    private fun diskLock(key: String) = diskLocks[(key.hashCode() and Int.MAX_VALUE) % diskLocks.size]
    fun invalidate() = synchronized(locks) {
        preferences.edit().putLong("generation", preferences.getLong("generation", 0L) + 1L).apply()
        retained.clearFailures()
    }
    private fun file(key: String) = AtomicFile(File(directory, com.gamefibre.stores.digest(key.toByteArray()) + ".json"))
    private fun read(key: String): Pair<Long, GameDetails>? = synchronized(diskLock(key)) {
        runCatching {
            val target = file(key)
            val cached = JSONObject(target.openRead().bufferedReader().use { it.readText() })
            DiskCacheBudget.touch(target.baseFile)
            cached.getLong("cacheGeneration") to GameDetails.read(cached)
        }.getOrNull()
    }
    fun cachedSteam(id: Int) = read("steam:$id")?.second
    fun cachedGog(id: String) = read("gog:$id")?.second
    fun cachedEpic(game: StoreGame) = read("epic:${game.namespace}:${game.catalogId}")?.second
    fun currentEpic(game: StoreGame): GameDetails? = read("epic:${game.namespace}:${game.catalogId}")
        ?.takeIf { it.first == preferences.getLong("generation", 0L) }?.second

    private fun load(key: String, fetch: () -> GameDetails): GameDetails? = synchronized(locks[(key.hashCode() and Int.MAX_VALUE) % locks.size]) {
        val generation = preferences.getLong("generation", 0L)
        retained.load(key, generation, read(key), fetch) { details ->
            synchronized(diskLock(key)) {
                directory.mkdirs()
                val target = file(key)
                val output = target.startWrite()
                // A fetch begun before refresh stays stale even if it finishes afterward.
                try { output.write(details.json().put("cacheGeneration", generation).toString().toByteArray()); target.finishWrite(output) }
                catch (error: Exception) { target.failWrite(output); throw error }
                DiskCacheBudget.trim(directory, DiskCacheBudget.DETAILS_BYTES, keep = target.baseFile)
            }
        }
    }
    fun steam(id: Int): GameDetails? = load("steam:$id") {
        require(id > 0)
        val response = get("https://store.steampowered.com/api/appdetails?appids=$id&l=english").getJSONObject(id.toString())
        if (response.optBoolean("success")) PageDetailsParser.steam(response.getJSONObject("data")) else error("Steam game details are unavailable")
    }
    fun gog(id: String): GameDetails? = load("gog:$id") {
        require(id.matches(Regex("[0-9]+")))
        PageDetailsParser.gog(get("https://api.gog.com/v2/games/$id"))
    }
    fun epic(game: StoreGame, session: StoreSession): GameDetails? = load("epic:${game.namespace}:${game.catalogId}") {
        val url = "https://catalog-public-service-prod06.ol.epicgames.com/catalog/api/shared/namespace/${android.net.Uri.encode(game.namespace)}/bulk/items?id=${android.net.Uri.encode(game.catalogId)}&locale=en-US&includeMainGameDetails=true"
        PageDetailsParser.epic(StoreApi.authorizedJson(url, session).getJSONObject(game.catalogId))
    }
    private fun get(address: String): JSONObject {
        val uri = Http.validate(address, setOf("store.steampowered.com", "api.gog.com"))
        val response = Http.exchange(uri, connectTimeout = 10_000, readTimeout = 15_000, maxBytes = 4 * 1024 * 1024)
        check(response.status == 200)
        return JSONObject(response.text)
    }
}

internal object PageDetailsParser {
    private fun objects(array: JSONArray?) = if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    private fun text(data: JSONObject?, name: String) = (data?.opt(name) as? String).orEmpty()
    private fun names(array: JSONArray?) = objects(array).map { text(it, "name") }.filter(String::isNotBlank)
    private fun releaseDate(date: String): String {
        for (pattern in listOf("d MMM, yyyy", "MMM d, yyyy", "d MMM yyyy")) {
            runCatching { java.time.LocalDate.parse(date, java.time.format.DateTimeFormatter.ofPattern(pattern, java.util.Locale.ENGLISH)).toString() }
                .getOrNull()?.let { return it }
        }
        return date
    }
    fun steam(data: JSONObject) = GameDetails(
        description = text(data, "about_the_game").ifBlank { text(data, "detailed_description") }.ifBlank { text(data, "short_description") },
        developer = GameDetails.strings(data.optJSONArray("developers")).joinToString(" · "),
        publisher = GameDetails.strings(data.optJSONArray("publishers")).joinToString(" · "),
        release = releaseDate(text(data.optJSONObject("release_date"), "date")),
        category = objects(data.optJSONArray("genres")).map { text(it, "description") }.filter(String::isNotBlank).joinToString(" · "),
        screenshots = objects(data.optJSONArray("screenshots")).map { text(it, "path_thumbnail").ifBlank { text(it, "path_full") } }.filter { it.startsWith("https://") }.distinct(),
        tags = objects(data.optJSONArray("categories")).map { text(it, "description") }.filter(String::isNotBlank).distinct(),
        metascore = data.optJSONObject("metacritic")?.optInt("score")?.takeIf { it in 1..100 },
        steamStorageBytes = data.optJSONObject("pc_requirements")?.let {
            steamStorageBytes(text(it, "minimum")) ?: steamStorageBytes(text(it, "recommended"))
        })
    fun gog(data: JSONObject): GameDetails {
        val embedded = data.optJSONObject("_embedded")
        val shots = objects(embedded?.optJSONArray("screenshots")).mapNotNull {
            it.optJSONObject("_links")?.optJSONObject("self")?.let { link ->
                text(link, "href").replace("{formatter}", "product_card_screenshot_748").takeIf { url -> url.startsWith("https://") }
            }
        }
        val description = data.optJSONObject("description")
        return GameDetails(description = text(description, "full").ifBlank { text(description, "lead") }.ifBlank { text(data, "description") },
            developer = names(embedded?.optJSONArray("developers")).joinToString(" · "),
            publisher = names(embedded?.optJSONArray("publishers")).joinToString(" · ").ifBlank { text(embedded?.optJSONObject("publisher"), "name") },
            screenshots = shots.distinct(), tags = (names(embedded?.optJSONArray("tags")) + names(embedded?.optJSONArray("features"))).distinct(),
            storeLinks = storePageUrl("GOG", text(data.optJSONObject("_links")?.optJSONObject("store"), "href"))?.let { mapOf("GOG" to it) }.orEmpty())
    }
    fun epic(data: JSONObject): GameDetails {
        val images = objects(data.optJSONArray("keyImages"))
        return GameDetails(description = text(data, "longDescription").ifBlank { text(data, "description") },
            developer = text(data, "developerDisplayName").ifBlank { text(data, "developer") },
            publisher = text(data, "publisherDisplayName").ifBlank { text(data, "publisher") },
            release = text(data, "pcReleaseDate").ifBlank { text(data, "releaseDate") }.take(10),
            screenshots = images.filter { text(it, "type") in setOf("Screenshot", "featuredMedia") }.map { text(it, "url") }.filter { it.startsWith("https://") }.distinct(),
            tags = names(data.optJSONArray("tags")), storeLinks = epicStoreLinks(data))
    }
    fun epicStoreLinks(data: JSONObject): Map<String, String> {
        val slug = text(data.optJSONObject("customAttributes")?.optJSONObject("com.epicgames.app.productSlug"), "value")
            .removeSuffix("/home").removeSuffix("/buy")
        return if (slug.matches(Regex("[A-Za-z0-9_-]+"))) mapOf("Epic Games" to "https://store.epicgames.com/en-US/p/$slug") else emptyMap()
    }
}
