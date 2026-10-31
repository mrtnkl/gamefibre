package com.gamefibre

import org.json.JSONArray
import java.util.Locale

data class LibraryGame(val appId: Int, val name: String, val minutes: Int, val platforms: List<String>, val sizeBytes: Long?, val details: GameDetails = GameDetails())

fun readGames(array: JSONArray): List<LibraryGame> = (0 until array.length()).map { index ->
    val game = array.getJSONObject(index)
    val platforms = game.optJSONArray("platforms") ?: JSONArray()
    LibraryGame(game.getInt("appId"), game.getString("name"), game.optInt("minutes"),
        (0 until platforms.length()).map { platforms.getString(it) },
        if (game.has("sizeBytes") && !game.isNull("sizeBytes")) game.getLong("sizeBytes") else null, GameDetails.read(game.optJSONObject("details")))
}

fun fileSize(bytes: Long): String = if (bytes < 1024L * 1024 * 1024)
    String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024))
else String.format(Locale.getDefault(), "%.2f GB", bytes / (1024.0 * 1024 * 1024))
