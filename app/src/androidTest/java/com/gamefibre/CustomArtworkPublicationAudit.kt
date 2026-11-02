package com.gamefibre

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import com.gamefibre.assets.*
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

/** Saved choices must be published and decoded without an online worker or a polling delay. */
internal object CustomArtworkPublicationAudit {
    fun run(context: Context): String = runBlocking {
        val name = "custom-artwork-audit-${UUID.randomUUID()}"
        val directory = File(context.cacheDir, name).apply { mkdirs() }
        val store = AssetStore(context, "$name.sqlite", File(directory, "assets"))
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val custom = CustomArtwork(preferences, File(directory, "custom"))
        val job = SupervisorJob()
        val repository = AssetRepository(context, store, custom, CoroutineScope(job + Dispatchers.IO))
        val transport = repository.transport
        val network = transport.networkAvailable
        transport.networkAvailable = { false }
        val requests = transport.requests.get()
        try {
            val games = listOf(
                LibraryEntry(LibraryStore.STEAM, "123456789", name),
                LibraryEntry(LibraryStore.GOG, "123456789", name),
                LibraryEntry(LibraryStore.EPIC, "custom-artwork-audit", name),
                LibraryEntry(LibraryStore.XBOX, "BNBWFH89FCF1", name)
            )
            val keys = games.map { it.key }
            withTimeout(5_000) { repository.synchronize(games, emptyMap(), keys.toSet()).join() }
            check(store.jobs() > 0) { "Offline games without a saved cover should wait in preparation" }
            var firstBlob: String? = null
            val times = mutableListOf<Long>()
            for ((index, color) in listOf(Color.RED, Color.GREEN).withIndex()) {
                val image = Bitmap.createBitmap(64, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
                val file = File(directory, "choice.jpg")
                file.outputStream().use { check(image.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
                image.recycle()
                val address = "https://cdn2.steamgriddb.com/grid/audit-$index.jpg"
                custom.apply(keys, address, file)
                val started = SystemClock.elapsedRealtime()
                withTimeout(5_000) { repository.refreshArtwork(games, emptyMap()).join() }
                times += SystemClock.elapsedRealtime() - started
                games.forEach { game ->
                    val published = checkNotNull(repository.published(game)) { "Saved cover was left in the background queue" }
                    check(published.asset.source == address) { "Panel still uses the previous cover" }
                    val decoded = checkNotNull(repository.peek(game)) { "Published cover was not decoded before layout" }
                    check(decoded.width > 3 && decoded.height > 5)
                    if (index == 0) firstBlob = published.asset.blob
                    else check(published.asset.blob != firstBlob) { "Replacement did not change the displayed handle" }
                }
                check(store.jobs() == 0) { "Saved choice still requires background preparation" }
            }
            check(transport.requests.get() == requests) { "Local publication made a network request" }
            "PASS: first and replacement covers published for all four stores offline, decoded before return; ${times}ms"
        } finally {
            job.cancelAndJoin()
            transport.networkAvailable = network
            store.close()
            context.deleteDatabase("$name.sqlite")
            preferences.edit().clear().commit()
            context.deleteSharedPreferences(name)
            directory.deleteRecursively()
        }
    }
}
