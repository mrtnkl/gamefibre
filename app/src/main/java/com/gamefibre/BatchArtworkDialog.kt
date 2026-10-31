package com.gamefibre

import android.app.Activity
import android.content.res.ColorStateList
import android.view.View
import android.widget.ProgressBar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Closing the progress window leaves the activity-owned queue running. */
internal fun showBatchArtworkDialog(activity: Activity, ui: NativeUi, scope: CoroutineScope,
    batch: BatchArtwork, hidden: () -> Unit): ContentDialog {
    val dialog = ContentDialog(activity, ui, "Auto-apply artwork", bodyHeightDp = 360)
    val summary = ui.text("", 16, medium = true).apply {
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
        progressTintList = ColorStateList.valueOf(ui.blue)
    }
    val current = ui.text("", 14, ui.secondary)
    val results = ui.column()
    ui.add(dialog.content, summary)
    ui.add(dialog.content, progress)
    ui.add(dialog.content, current)
    ui.gap(dialog.content)
    ui.add(dialog.content, results)
    var shown = 0
    val observation = scope.launch {
        batch.status.collect { state ->
            if (state == null) return@collect
            summary.text = "${state.results.size} of ${state.total} processed"
            progress.max = state.total; progress.progress = state.results.size
            current.text = if (state.running) {
                if (state.stopping) "Stopping after the current game…"
                else state.current?.let { "Searching and applying: $it" } ?: state.message
            } else state.message
            state.results.drop(shown).forEach { result ->
                ui.add(results, ui.text(result.title, 16, medium = true))
                ui.add(results, ui.text(result.message, 14, ui.secondary))
                ui.gap(results)
            }
            shown = state.results.size
            val close = ui.button(if (state.running) "Hide" else "Close", primary = true) { dialog.dismiss() }
            if (state.running) dialog.setActions(close, ui.button("Stop") { batch.stop() }.apply { isEnabled = !state.stopping })
            else dialog.setActions(close)
        }
    }
    dialog.setOnDismissListener { observation.cancel(); hidden() }
    dialog.show()
    return dialog
}
