package com.gamefibre

import com.gamefibre.assets.AssetTransport

import android.content.Context
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** Rotation may recreate the activity; retain the queue without retaining any views or activity. */
internal class BatchArtworkModel : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var batch: BatchArtwork? = null
        private set

    fun start(context: Context, key: String, targets: List<ArtworkTarget>): BatchArtwork {
        batch?.takeIf { it.status.value?.running == true }?.let { return it }
        val app = context.applicationContext
        val artwork = AssetTransport.get(app)
        val customArtwork = CustomArtwork(app)
        val api = SteamGridDb(key)
        return BatchArtwork(
            search = { withContext(Dispatchers.IO) { api.search(it) } },
            grids = { withContext(Dispatchers.IO) { api.grids(it, false, 0) } },
            save = { target, cover -> saveGridArtwork(artwork, customArtwork, target.keys, cover) }
        ).also { started ->
            batch = started
            started.start(scope, targets)
            scope.launch {
                started.status.first { it?.running == false }
                if (batch === started) batch = null
            }
        }
    }

    override fun onCleared() { scope.cancel() }
}

/** Download completely before committing; cancellation never interrupts the local transaction. */
internal suspend fun saveGridArtwork(artwork: AssetTransport, customArtwork: CustomArtwork,
    keys: List<String>, choice: GridArtwork, saved: suspend () -> Unit = {}) {
    val file = withContext(Dispatchers.IO) {
        artwork.image(choice.url, portrait = true, fullscreen = true)
            ?: error("Could not download this cover. Try again later.")
    }
    withContext(NonCancellable) {
        withContext(Dispatchers.IO) { customArtwork.apply(keys, choice.url, file) }
        saved()
    }
}
