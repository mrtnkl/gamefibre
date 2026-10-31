package com.gamefibre

import com.gamefibre.stores.StoreGame
import com.gamefibre.stores.XboxAccess
import org.json.JSONArray
import org.json.JSONObject

/** Portable library metadata. Contains no account tokens, local paths, or game assets. */
internal object LibraryRecords {
    fun json(game: LibraryEntry): JSONObject = WishlistItems.json(game)
        .put("steam", game.steam?.let {
            JSONObject().put("minutes", it.minutes).put("platforms", JSONArray(it.platforms))
                .put("sizeBytes", it.sizeBytes ?: JSONObject.NULL)
        } ?: JSONObject.NULL)
        .put("namespace", game.remote?.namespace.orEmpty())
        .put("catalogId", game.remote?.catalogId.orEmpty())
        .put("xboxAccess", game.remote?.xboxAccess?.name ?: JSONObject.NULL)

    fun read(rows: JSONArray): List<LibraryEntry> {
        require(rows.length() <= 100_000) { "Too many library games." }
        val games = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val base = WishlistItems.read(JSONArray().put(row)).single()
            row.get("steam"); row.get("xboxAccess")
            val steam = if (row.isNull("steam")) null else row.getJSONObject("steam").let {
                require(base.store == LibraryStore.STEAM && it.getInt("minutes") >= 0)
                val size = if (it.isNull("sizeBytes")) null else it.getLong("sizeBytes").also { bytes -> require(bytes >= 0) }
                LibraryGame(base.id.toInt(), base.name, it.getInt("minutes"),
                    CollectionArchive.strings(it.getJSONArray("platforms")), size, base.details)
            }
            val access = if (row.isNull("xboxAccess")) null else XboxAccess.valueOf(row.getString("xboxAccess"))
            require(access == null || base.store == LibraryStore.XBOX) { "Invalid Xbox access." }
            base.copy(steam = steam, remote = if (steam != null) null else StoreGame(base.id, base.name,
                row.getString("namespace"), row.getString("catalogId"), base.artwork, base.details, access))
        }
        require(games.map { it.key }.distinct().size == games.size) { "Duplicate library game." }
        return games
    }
}

internal object CollectionSnapshot {
    fun create(collection: CollectionData, games: List<LibraryEntry>, covers: Map<String, String>): JSONObject {
        val prefs = collection.preferences
        val progress = JSONObject()
        prefs.all.forEach { (key, value) ->
            if (key.startsWith("progress:") && value is String && GameProgress.fromId(value) != null)
                progress.put(key.removePrefix("progress:"), value)
        }
        val library = games.flatMap { it.copies.ifEmpty { listOf(it) } }.distinctBy { it.key }
        val lists = collection.lists.filter { GameProgress.fromLabel(it) == null && it !in setOf("Downloads", WishlistItems.LIST) }
        return JSONObject().put("format", "gamefibre-collection").put("version", 2)
            .put("createdAt", java.time.Instant.now().toString())
            .put("lists", JSONArray(lists.map { name ->
                JSONObject().put("name", name).put("automatic", collection.isAutomaticList(name))
                    .put("custom", name in collection.customLists).put("games", JSONArray(collection.members(name).sorted()))
            }))
            .put("progress", progress)
            .put("wishlist", JSONArray(collection.wishlistGames().map(WishlistItems::json)))
            .put("removedBuiltIns", JSONArray(prefs.getStringSet("removed-built-in-lists", emptySet()).orEmpty().sorted()))
            .put("settings", JSONObject().put("sort", collection.sort).put("tileSize", collection.tileSize)
                .put("listOrder", JSONArray(collection.lists)).put("selectedList", collection.list)
                .put("fitCoverArt", collection.fitCoverArt).put("showHiddenGames", collection.showHiddenGames)
                .put("showGamePass", collection.showGamePass).put("randomSeed", collection.randomSeed)
                .put("theme", prefs.getString("theme", "System"))
                .put("youtubeQueries", prefs.getString(YouTubeQueries.PREFERENCE, YouTubeQueries.defaults.joinToString("\n")))
                .put("showTitles", collection.showTitles).put("descending", prefs.getBoolean("descending", false)))
            .put("games", JSONArray(library.map(LibraryRecords::json)))
            .put("artwork", JSONArray(covers.map { (key, url) -> JSONObject().put("game", key).put("url", url) }))
    }
}
