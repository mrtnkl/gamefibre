package com.gamefibre

import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Storage operations live outside the activity's navigation and rendering code. */
internal fun showStorageDialog(activity: AppCompatActivity, ui: NativeUi, scope: CoroutineScope,
    changed: () -> Unit) {
    val storage = LocalStorage(activity.applicationContext)
    val dialog = ContentDialog(activity, ui, "Storage")
    val content = dialog.content
    val usage = ui.text("Calculating storage…", 14)
    val status = ui.text("", 13, ui.secondary)
    ui.add(content, usage); ui.gap(content)
    ui.add(content, ui.text("Library artwork is kept for offline browsing. Clear temporary game details without removing library artwork, selected covers, accounts, or lists.", 13, ui.secondary))
    val clearCaches = ui.button("Clear temporary game details") {}
    ui.add(content, clearCaches); ui.gap(content)
    ui.add(content, status)
    dialog.setActions(ui.button("Close") { dialog.dismiss() })
    dialog.show()

    suspend fun updateUsage() {
        usage.text = withContext(Dispatchers.IO) {
            runCatching { "Offline library artwork: ${fileSize(storage.offlineArtworkBytes())}\nTemporary details: ${fileSize(storage.cacheBytes())}" }
                .getOrDefault("Storage usage is currently unavailable.")
        }
    }

    fun perform(message: String, operation: () -> Unit) {
        scope.launch {
            clearCaches.isEnabled = false
            status.text = "Clearing…"
            try {
                withContext(Dispatchers.IO) { DownloadGate.whileIdle(operation) }
                changed()
                status.text = message
                updateUsage()
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { status.text = "Could not finish cleanup. Wait for downloads and account cleanup to stop, then try again." }
            finally { clearCaches.isEnabled = true }
        }
    }
    clearCaches.setOnClickListener { if (clearCaches.isEnabled) perform("Temporary game details cleared.", storage::clearCaches) }
    scope.launch { updateUsage() }
}
