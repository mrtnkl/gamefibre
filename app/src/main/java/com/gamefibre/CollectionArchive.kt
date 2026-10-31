package com.gamefibre

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Portable, credential-free schema. ZIP paths are generated/allowlisted, never resolved from arbitrary input. */
internal object CollectionArchive {
    const val MAX_JSON = 16 * 1024 * 1024
    private val gameKey = Regex("(STEAM|GOG|EPIC|XBOX):[^\\p{Cntrl}]{1,512}")
    data class Contents(val manifest: JSONObject)

    fun strings(array: JSONArray): List<String> {
        require(array.length() <= 100_000) { "Too many collection entries." }
        return List(array.length()) { array.getString(it) }
    }

    fun validate(data: JSONObject) {
        require(data.getString("format") == "gamefibre-collection" && data.getInt("version") == 2) {
            "This is not a supported collection backup."
        }
        val lists = data.getJSONArray("lists")
        require(lists.length() <= 1000) { "Too many lists." }
        val names = mutableSetOf<String>()
        repeat(lists.length()) { index ->
            val list = lists.getJSONObject(index)
            val name = list.getString("name")
            require(name.isNotBlank() && name == name.trim() && name.length <= 200 && !name.any(Char::isISOControl) && names.add(name.lowercase(java.util.Locale.ROOT))) { "Invalid or duplicate list name." }
            require(GameProgress.fromLabel(name) == null && listOf("Downloads", WishlistItems.LIST).none { it.equals(name, true) }) { "A list uses a reserved name." }
            val custom = list.getBoolean("custom")
            val automatic = list.getBoolean("automatic")
            require(!custom || !name.equals(CollectionData.HIDDEN_LIST, true)) { "Hidden is a built-in list." }
            require(!automatic || (!custom && name in setOf("All games", "Steam", "GOG", "Epic", "Xbox", "Game Pass"))) { "Invalid automatic list." }
            require(strings(list.getJSONArray("games")).all { gameKey.matches(it) }) { "Invalid game identity." }
        }
        val progress = data.getJSONObject("progress")
        require(progress.length() <= 100_000)
        progress.keys().forEach { key ->
            require(gameKey.matches(key) && GameProgress.fromId(progress.getString(key)) != null) { "Invalid game progress." }
        }
        val art = data.getJSONArray("artwork")
        require(art.length() <= 100_000)
        val artKeys = mutableSetOf<String>()
        repeat(art.length()) { index ->
            val item = art.getJSONObject(index)
            val address = item.getString("url")
            require(gameKey.matches(item.getString("game")) && artKeys.add(item.getString("game")) &&
                address.length <= 4096 && SteamGridDb.imageUrl(address)) { "Invalid custom artwork entry." }
        }
        val removed = strings(data.getJSONArray("removedBuiltIns"))
        require(removed.all { it in CollectionData.BUILT_IN_LISTS && it !in setOf("Downloads", WishlistItems.LIST, CollectionData.HIDDEN_LIST) })
        val settings = data.getJSONObject("settings")
        run {
            val order = strings(settings.getJSONArray("listOrder"))
            require(order.size <= 1100 && order.distinct().size == order.size && order.all {
                it in CollectionData.BUILT_IN_LISTS || GameProgress.fromLabel(it) != null || it.lowercase(java.util.Locale.ROOT) in names
            }) { "Invalid list order." }
        }
        require(settings.getString("sort") in listOf("Name", "Time to beat", "Playtime", "Metascore", "Size", "Random"))
        require(settings.getString("tileSize") in listOf("Small", "Medium", "Large", "List"))
        settings.getBoolean("showTitles"); settings.getBoolean("descending")
        settings.getBoolean("fitCoverArt")
        settings.getBoolean("showHiddenGames")
        settings.getBoolean("showGamePass")
        settings.getLong("randomSeed")
        require(settings.getString("theme") in listOf("System", "Light", "Dark"))
        settings.getString("youtubeQueries")
        val selected = settings.getString("selectedList")
        require(selected.isEmpty() || selected in strings(settings.getJSONArray("listOrder"))) { "Invalid selected list." }
        WishlistItems.read(data.getJSONArray("wishlist"))
        val games = data.getJSONArray("games")
        require(games.length() <= 100_000)
        LibraryRecords.read(games)
    }

    fun write(output: OutputStream, contents: Contents) {
        validate(contents.manifest)
        val json = contents.manifest.toString(2).toByteArray(Charsets.UTF_8)
        require(json.size <= MAX_JSON) { "Collection backup exceeds the size limit." }
        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("collection.json"))
            zip.write(json)
            zip.closeEntry()
        }
    }

    /** Read and validate the entire metadata-only archive before changing preferences. */
    fun read(input: InputStream): Contents {
        ZipInputStream(input.buffered()).use { zip ->
            val entry = zip.nextEntry ?: error("The ZIP does not contain collection.json.")
            require(!entry.isDirectory && entry.name == "collection.json") { "Unexpected file in backup." }
            val bytes = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val size = zip.read(buffer)
                if (size < 0) break
                require(bytes.size() + size <= MAX_JSON) { "Collection backup exceeds the size limit." }
                bytes.write(buffer, 0, size)
            }
            require(zip.nextEntry == null) { "Unexpected or duplicate file in backup." }
            val data = JSONObject(bytes.toString(Charsets.UTF_8.name()))
            validate(data)
            return Contents(data)
        }
    }
}

internal fun csvRow(values: List<String>): String = values.joinToString(",") { value ->
    // Keep spreadsheet programs from interpreting user-controlled titles/list names as formulas.
    val safe = if (value.trimStart().firstOrNull() in listOf('=', '+', '-', '@') || value.startsWith('\t') || value.startsWith('\r')) "'$value" else value
    "\"${safe.replace("\"", "\"\"")}\""
} + "\r\n"
