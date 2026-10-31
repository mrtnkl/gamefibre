package com.gamefibre

import android.content.Context
import java.io.InputStream
import java.io.OutputStream

internal class CollectionBackup(private val context: Context) {
    fun exportZip(output: OutputStream, games: List<LibraryEntry>) {
        val contents = synchronized(CollectionData) {
            CollectionArchive.Contents(CollectionSnapshot.create(CollectionData(context), games, CustomArtwork(context).backup()))
        }
        CollectionArchive.write(output, contents)
    }

    fun importZip(input: InputStream): String {
        val data = CollectionArchive.read(input).manifest
        val rows = data.getJSONArray("artwork")
        val covers = (0 until rows.length()).associate { index ->
            val item = rows.getJSONObject(index)
            item.getString("game") to item.getString("url")
        }
        synchronized(CollectionData) {
            CustomArtwork(context).mergeBackup(covers) { CollectionData(context).mergeBackup(data) }
        }
        return "Collection imported. Library metadata and settings restored. Cover images load online; sign in to download games."
    }

    fun exportCsv(output: OutputStream, games: List<LibraryEntry>, beat: BeatIndex?) {
        val collection = CollectionData(context)
        val artwork = CustomArtwork(context)
        val wishlisted = collection.wishlistGames().map { it.key }.toSet()
        output.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write("\uFEFF")
            writer.write(csvRow(listOf("Title", "Stores", "Store IDs", "Progress", "Hidden", "Favorite", "Lists", "Steam playtime (minutes)", "Main story (hours)", "Custom cover URLs")))
            mergeLibraryCopies(games).sortedBy { it.sortName }.forEach { game ->
                val copies = game.copies.ifEmpty { listOf(game) }
                val keys = copies.map { it.key }
                val lists = collection.editableLists.filter { list -> keys.any { collection.contains(list, it) } } +
                    if (keys.any { it in wishlisted }) listOf(WishlistItems.LIST) else emptyList()
                writer.write(csvRow(listOf(game.name, copies.joinToString(" | ") { it.store.label }, keys.joinToString(" | "),
                    collection.progress(keys).label, if (keys.any { it in collection.members(CollectionData.HIDDEN_LIST) }) "Yes" else "No", if ("Favorites" in lists) "Yes" else "No", lists.joinToString(" | "),
                    copies.firstNotNullOfOrNull { it.steam?.minutes }?.toString().orEmpty(),
                    beat?.find(game)?.main?.takeIf { it > 0 }?.let { java.math.BigDecimal(it).divide(java.math.BigDecimal(3600), 2, java.math.RoundingMode.HALF_UP).toPlainString() }.orEmpty(),
                    keys.mapNotNull(artwork::address).distinct().joinToString(" | "))))
            }
        }
    }
}
