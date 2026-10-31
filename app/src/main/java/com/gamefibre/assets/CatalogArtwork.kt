package com.gamefibre.assets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.gamefibre.CatalogGame
import com.gamefibre.CustomArtwork
import com.gamefibre.StoreCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class CatalogCard(val game: CatalogGame, val bitmap: Bitmap)

/** Discovery shares persistent image bytes and Steam URL resolution without becoming owned inventory. */
internal class CatalogArtwork(context: Context) {
    private val transport = AssetTransport.get(context)
    private val store = transport.store
    private val custom = CustomArtwork(context)
    suspend fun resolve(games: List<CatalogGame>) = withContext(Dispatchers.IO) {
        val missing = games.map { it.appId }.filter { id ->
            store.steam(id)?.let { System.currentTimeMillis() - it.second < 24L * 60 * 60 * 1000 } != true
        }
        if (missing.isNotEmpty() && transport.networkAvailable()) {
            try { StoreCatalog().steamArtworks(missing).forEach { (id, urls) -> store.steam(id, urls) } }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { /* Keep cached usable URLs when Steam is unavailable. */ }
        }
    }
    suspend fun prepare(game: CatalogGame): CatalogCard = withContext(Dispatchers.IO) {
        val key = "STEAM:${game.appId}"
        val selected = custom.address(key)
        val address = selected ?: store.steam(game.appId)?.first?.landscape.orEmpty()
        val file = custom.fileForAddress(address) ?: transport.image(address, priority = 1)
            ?: error("Artwork is unavailable. Connect to the internet or select artwork.")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        check(bounds.outWidth > 3 && bounds.outHeight > 5) { "Artwork could not be decoded" }
        val options = BitmapFactory.Options().apply {
            while (bounds.outWidth / (inSampleSize.coerceAtLeast(1) * 2) >= 320) inSampleSize = inSampleSize.coerceAtLeast(1) * 2
        }
        val bitmap = BitmapFactory.decodeFile(file.path, options) ?: error("Artwork could not be decoded")
        CatalogCard(game, bitmap)
    }
}
