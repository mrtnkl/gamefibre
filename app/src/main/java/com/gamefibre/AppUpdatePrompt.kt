package com.gamefibre

import android.content.pm.ApplicationInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** GitHub downloads use the browser and Android's installer, without an app-owned installer. */
internal class AppUpdatePrompt(private val activity: AppCompatActivity, private val ui: NativeUi,
    private val scope: CoroutineScope, private val openUrl: (String) -> Unit) {
    val enabled = activity.packageName == "com.gamefibre" && activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0
    private val updates by lazy {
        AppUpdates(activity.getSharedPreferences("app-updates", 0),
            activity.packageManager.getPackageInfo(activity.packageName, 0).versionName.orEmpty())
    }
    private var job: Job? = null
    private var manualRequested = false
    private var dialog: ContentDialog? = null
    private val resumed get() = !activity.isFinishing && !activity.isDestroyed &&
        activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    val menuLabel get() = updates.available?.let { "Update to ${it.version}" } ?: "Check for updates"

    fun onResume() {
        if (!enabled) return
        if (updates.shouldCheck(System.currentTimeMillis())) check(manual = false) else offerIfNeeded()
    }

    fun fromMenu() {
        if (!enabled) return
        updates.available?.let { showUpdate(it); return }
        android.widget.Toast.makeText(activity, "Checking for updates…", android.widget.Toast.LENGTH_SHORT).show()
        check(manual = true)
    }

    private fun offerIfNeeded() {
        val update = updates.available ?: return
        if (resumed && updates.shouldPrompt(update)) showUpdate(update)
    }

    private fun check(manual: Boolean) {
        if (manual) manualRequested = true
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                withContext(Dispatchers.IO) { updates.check(System.currentTimeMillis()) }
                if (!resumed) return@launch
                if (manualRequested) {
                    val update = updates.available
                    if (update != null) showUpdate(update) else showMessage("You’re using the latest version of GameFibre.")
                } else offerIfNeeded()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (manualRequested && resumed) showMessage("Could not check for updates. Try again when you have an internet connection.")
                else offerIfNeeded()
            } finally {
                manualRequested = false
            }
        }
    }

    private fun showUpdate(update: AppUpdate) {
        if (dialog?.isShowing == true || !resumed) return
        updates.markPrompted(update)
        showDialog("Update available") { notice ->
            ui.add(notice.content, ui.text("GameFibre ${update.version} is available. Download the APK, then open it to install the update.", 16))
            notice.setActions(ui.button("Download update", primary = true) { notice.dismiss(); openUrl(update.downloadUrl) },
                ui.button("Later") { notice.dismiss() })
        }
    }

    private fun showMessage(message: String) = showDialog("App updates") { notice ->
        ui.add(notice.content, ui.text(message, 16))
        notice.setActions(ui.button("OK") { notice.dismiss() })
    }

    private fun showDialog(title: String, content: (ContentDialog) -> Unit) {
        if (dialog?.isShowing == true) return
        val notice = ContentDialog(activity, ui, title)
        dialog = notice
        notice.setOnDismissListener { if (dialog === notice) dialog = null }
        content(notice)
        notice.show()
    }

    fun close() { job?.cancel(); dialog?.dismiss() }
}
