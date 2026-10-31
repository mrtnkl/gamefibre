package com.gamefibre

import com.gamefibre.stores.StoreGame
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Local catalog selections carry no account session and never grant ownership. */
internal object WishlistItems {
    const val LIST = "Wishlist"
    const val MAX_ITEMS = 5000

    fun imageUrl(value: String): String = runCatching {
        val uri = URI(value)
        value.takeIf { uri.scheme == "https" && uri.userInfo == null && uri.port == -1 &&
            listOf("steamstatic.com", "gog-statics.com", "gog.com", "epicgames.com", "unrealengine.com",
                "store-images.s-microsoft.com", "store-images.microsoft.com", "images-eds-ssl.xboxlive.com")
                .any { uri.host == it || uri.host.orEmpty().endsWith(".$it") } }.orEmpty()
    }.getOrDefault("")

    fun json(game: LibraryEntry) = JSONObject().put("store", game.store.name).put("id", game.id)
        .put("name", game.name).put("artwork", imageUrl(game.artwork)).put("details", game.details.json())

    fun read(rows: JSONArray): List<LibraryEntry> {
        require(rows.length() <= MAX_ITEMS) { "The wishlist can contain up to $MAX_ITEMS games." }
        val games = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val store = LibraryStore.valueOf(row.getString("store"))
            val id = row.getString("id")
            require(when (store) {
                LibraryStore.STEAM -> id.toIntOrNull()?.let { it > 0 } == true
                LibraryStore.GOG -> id.matches(Regex("[0-9]{1,20}"))
                LibraryStore.XBOX -> com.gamefibre.stores.xboxProductId(id)
                LibraryStore.EPIC -> id.matches(Regex("[A-Za-z0-9_-]{1,128}"))
            }) { "Invalid wishlist game identity." }
            val name = row.getString("name")
            require(name.isNotBlank() && name.length <= 512 && name.none(Char::isISOControl)) { "Invalid wishlist title." }
            LibraryEntry(store, id, name, remote = StoreGame(id, name,
                imageUrl = imageUrl(row.getString("artwork")), details = GameDetails.read(row.getJSONObject("details"))))
        }
        require(games.map { it.key }.distinct().size == games.size) { "Duplicate wishlist game." }
        return games
    }
}
