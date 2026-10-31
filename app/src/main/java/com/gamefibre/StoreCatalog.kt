package com.gamefibre

import com.gamefibre.stores.StoreGame
import com.gamefibre.stores.XboxAccess
import com.gamefibre.stores.xboxCatalogGame
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.net.URI
import java.util.Locale

/** Public storefront catalog requests; account credentials are never sent to search. */
internal class StoreCatalog(
    private val xboxTitles: (String) -> JSONObject = ::requestXboxTitleSearch,
    private val request: (String) -> JSONObject = ::requestCatalogJson,
) {
    companion object {
        val SEARCH_STORES = listOf(LibraryStore.STEAM, LibraryStore.GOG, LibraryStore.XBOX)
        private fun encoded(value: String) = URLEncoder.encode(value, "UTF-8")
        private fun objects(rows: JSONArray) = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }

        fun steamSearch(data: JSONObject): List<LibraryEntry> = objects(data.getJSONArray("items")).mapNotNull { row ->
            val id = row.optInt("id")
            val name = row.optString("name")
            if (row.optString("type") != "app" || id <= 0 || name.isBlank()) null else LibraryEntry(LibraryStore.STEAM,
                id.toString(), name, remote = StoreGame(id.toString(), name, imageUrl = WishlistItems.imageUrl(row.optString("tiny_image")),
                    details = GameDetails(storeLinks = mapOf("Steam" to "https://store.steampowered.com/app/$id/"))))
        }.distinctBy { it.key }.take(20)

        fun gogSearch(data: JSONObject): List<LibraryEntry> = objects(data.getJSONArray("products")).mapNotNull { row ->
            val id = row.optString("id"); val name = row.optString("title")
            if (row.optString("productType") != "game" || !id.matches(Regex("[0-9]{1,20}")) || name.isBlank()) null
            else {
                val portrait = WishlistItems.imageUrl(row.optString("coverVertical"))
                LibraryEntry(LibraryStore.GOG, id, name, remote = StoreGame(id, name, imageUrl = portrait,
                    details = GameDetails(portrait = portrait, developer = GameDetails.strings(row.optJSONArray("developers")).joinToString(" · "),
                        publisher = GameDetails.strings(row.optJSONArray("publishers")).joinToString(" · "),
                        release = row.optString("releaseDate").takeIf { it.matches(Regex("[0-9]{4}\\.[0-9]{2}\\.[0-9]{2}")) }?.replace('.', '-').orEmpty(),
                        storeLinks = storePageUrl("GOG", row.optString("storeLink"))?.let { mapOf("GOG" to it) }.orEmpty())))
            }
        }.distinctBy { it.key }.take(20)

        fun xboxSearch(data: JSONObject): List<LibraryEntry> = objects(data.getJSONArray("Products")).mapNotNull {
            xboxCatalogGame(it, XboxAccess.GAME_PASS)?.copy(xboxAccess = null)?.let { game ->
                LibraryEntry(LibraryStore.XBOX, game.id, game.name, remote = game)
            }
        }.distinctBy { it.key }.take(20)

        fun steamItems(data: JSONObject): List<LibraryEntry> = objects(data.getJSONObject("response").getJSONArray("store_items")).mapNotNull { row ->
            val id = row.optInt("appid"); val name = row.optString("name")
            // Store item type 0 is a game. DLC, demos, software and unavailable records are omitted.
            if (row.optInt("success") != 1 || row.optInt("type", -1) != 0 || id <= 0 || name.isBlank()) null else {
                val artwork = SteamArtworkUrls.read(row.optJSONObject("assets"))
                val portrait = artwork.portrait
                val landscape = artwork.address(portrait = false)
                val basic = row.optJSONObject("basic_info")
                fun names(key: String) = basic?.optJSONArray(key)?.let(::objects)?.mapNotNull {
                    (it.opt("name") as? String)?.takeIf(String::isNotBlank)
                }?.joinToString(" · ").orEmpty()
                LibraryEntry(LibraryStore.STEAM, id.toString(), name, remote = StoreGame(id.toString(), name, imageUrl = landscape,
                    details = GameDetails(description = (basic?.opt("short_description") as? String).orEmpty(),
                        developer = names("developers"), publisher = names("publishers"), portrait = portrait,
                        storeLinks = mapOf("Steam" to "https://store.steampowered.com/app/$id/"))))
            }
        }.distinctBy { it.key }
    }

    fun search(store: LibraryStore, query: String): List<LibraryEntry> {
        val title = query.trim()
        if (store == LibraryStore.XBOX) {
            val productId = xboxSearchProductId(title)
            if (productId != null) return xboxSearch(request(
                "https://displaycatalog.mp.microsoft.com/v7.0/products?bigIds=$productId&market=US&languages=en-US"))
        }
        require(title.length in 2..200) { "Enter a title of 2–200 characters, or a valid Xbox store link." }
        return when (store) {
            LibraryStore.STEAM -> steamSearch(request("https://store.steampowered.com/api/storesearch/?term=${encoded(title)}&l=english&cc=US"))
            LibraryStore.GOG -> gogSearch(request("https://catalog.gog.com/v1/catalog?query=${encoded(title)}&limit=20&locale=en-US"))
            LibraryStore.XBOX -> {
                val rows = xboxTitles(title).getJSONObject("channels").getJSONObject("games").getJSONArray("products")
                val ids = objects(rows).map { it.getString("productId") }
                    .filter { com.gamefibre.stores.xboxProductId(it) }.distinct().take(20)
                if (ids.isEmpty()) emptyList() else {
                    val games = xboxSearch(request("https://displaycatalog.mp.microsoft.com/v7.0/products?bigIds=${ids.joinToString(",")}&market=US&languages=en-US"))
                        .associateBy { it.id }
                    ids.mapNotNull(games::get)
                }
            }
            LibraryStore.EPIC -> error("Epic Games catalog search is unavailable.")
        }
    }

    private fun xboxSearchProductId(value: String): String? {
        val id = value.uppercase(Locale.ROOT)
        if (com.gamefibre.stores.xboxProductId(id)) return id
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host?.lowercase(Locale.ROOT) !in setOf("xbox.com", "www.xbox.com") ||
            uri.userInfo != null || uri.port != -1) return null
        val path = uri.path.orEmpty().trimEnd('/')
        if (!path.contains("/games/store/", ignoreCase = true)) return null
        return path.substringAfterLast('/').uppercase(Locale.ROOT)
            .takeIf { com.gamefibre.stores.xboxProductId(it) }
    }

    fun retrieve(game: LibraryEntry): LibraryEntry = if (game.store == LibraryStore.STEAM)
        steamDetails(listOf(game.id.toInt())).singleOrNull() ?: error("This Steam item is not an available game.") else game

    fun steamArtwork(id: Int): SteamArtworkUrls {
        require(id > 0)
        return steamArtworks(listOf(id))[id] ?: error("Steam artwork is unavailable.")
    }

    fun steamArtworks(ids: List<Int>): Map<Int, SteamArtworkUrls> {
        require(ids.isNotEmpty() && ids.size <= 50 && ids.all { it > 0 })
        return objects(steamResponse(ids).getJSONObject("response").getJSONArray("store_items"))
            .filter { it.optInt("success") == 1 && it.optInt("appid") in ids }
            .associate { it.getInt("appid") to SteamArtworkUrls.read(it.optJSONObject("assets")) }
    }

    private fun steamDetails(ids: List<Int>): List<LibraryEntry> = steamItems(steamResponse(ids))

    private fun steamResponse(ids: List<Int>): JSONObject {
        val input = JSONObject().put("ids", JSONArray(ids.map { JSONObject().put("appid", it) }))
            .put("context", JSONObject().put("language", "english").put("country_code", "US"))
            .put("data_request", JSONObject().put("include_assets", true).put("include_basic_info", true))
        return request("https://api.steampowered.com/IStoreBrowseService/GetItems/v1/?input_json=${encoded(input.toString())}")
    }

    data class Import(val games: List<LibraryEntry>, val unavailable: Int)
    suspend fun steamWishlist(account: String): Import {
        require(account.matches(Regex("[0-9]{17}"))) { "Connect Steam before importing its wishlist." }
        val data = request("https://api.steampowered.com/IWishlistService/GetWishlist/v1/?steamid=$account").getJSONObject("response")
        val rows = data.optJSONArray("items") ?: JSONArray()
        check(rows.length() <= WishlistItems.MAX_ITEMS) { "The Steam wishlist is too large to import." }
        val ids = objects(rows).map { it.getInt("appid").also { id -> check(id > 0) } }.distinct()
        val games = mutableListOf<LibraryEntry>()
        for (batch in ids.chunked(50)) {
            currentCoroutineContext().ensureActive()
            games += steamDetails(batch)
        }
        return Import(games, ids.size - games.size)
    }
}

private fun requestCatalogJson(address: String): JSONObject {
    val uri = Http.validate(address, setOf("store.steampowered.com", "api.steampowered.com", "catalog.gog.com", "displaycatalog.mp.microsoft.com"))
    val response = Http.exchange(uri, connectTimeout = 10_000, readTimeout = 20_000, tooLarge = "The store returned too much data.")
    if (response.status == 429) error("The store is busy. Try again shortly.")
    if (uri.path.contains("IWishlistService") && (response.status in listOf(401, 403) || response.header("x-eresult")?.let { it != "1" } == true))
        error("Steam could not share this wishlist. Set your Steam wishlist to public, then try again.")
    check(response.status == 200) { "Could not reach the store (${response.status}). Try again." }
    return JSONObject(response.text)
}

/** Xbox website title search includes older games omitted by Display Catalog's storefront search. */
private fun requestXboxTitleSearch(query: String): JSONObject {
    val uri = Http.validate("https://emerald.xboxservices.com/xboxcomfd/search/games?locale=en-US",
        setOf("emerald.xboxservices.com"))
    val payload = JSONObject().put("Query", query).put("ChannelId", "games")
        .put("ChannelKeyToBeUsedInResponse", "games").put("ReturnFilters", false)
    val correlation = java.util.Base64.getEncoder().withoutPadding().encodeToString(
        ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }) + ".0"
    val response = Http.exchange(uri, headers = mapOf("X-MS-API-Version" to "1.1", "MS-CV" to correlation),
        body = payload.toString().toByteArray(Charsets.UTF_8), contentType = "application/json",
        connectTimeout = 10_000, readTimeout = 20_000, tooLarge = "The store returned too much data.")
    if (response.status == 429) error("The store is busy. Try again shortly.")
    check(response.status == 200) { "Could not search Xbox (${response.status}). Try again." }
    return JSONObject(response.text)
}
