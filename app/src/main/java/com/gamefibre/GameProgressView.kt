package com.gamefibre

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import java.util.Locale

internal data class TransferCard(val key: String, val store: String, val name: String, val phase: String,
    val message: String, val progress: Double?, val label: String, val bytes: String,
    val running: Boolean, val canPause: Boolean, val canResume: Boolean, val output: String)

/** A small transfer strip on a collection card, updated without rebuilding its controls. */
internal class GameProgressView(context: Context) : LinearLayout(context) {
    private val ui = NativeUi(context)
    private val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000; progressTintList = ColorStateList.valueOf(ui.blue); indeterminateTintList = progressTintList
    }
    private val status = ui.text("", 11, ui.secondary)
    private val actions = ui.row()
    private var signature = ""
    init { orientation = VERTICAL; ui.add(this, bar, 4); ui.add(this, status); ui.add(this, actions) }
    fun render(t: TransferCard, pause: () -> Unit, resume: () -> Unit, cancel: () -> Unit, open: () -> Unit, remove: () -> Unit) {
        visibility = View.VISIBLE
        bar.isIndeterminate = t.running && t.progress == null
        bar.progress = ((t.progress ?: 0.0).coerceIn(0.0, 1.0) * 1000).toInt()
        status.text = if (t.phase == "failed") t.message else t.phase.replaceFirstChar { it.uppercase() } +
            t.progress?.let { String.format(Locale.getDefault(), " · %.0f%%", it * 100) }.orEmpty()
        status.contentDescription = "${t.name}: ${status.text}"
        val next = "${t.key}:${t.phase}:${t.canPause}:${t.canResume}:${t.running}:${t.output}"
        if (signature == next) return
        signature = next; actions.removeAllViews()
        fun action(icon: Int, label: String, block: () -> Unit) {
            actions.addView(ui.iconButton(icon, label, block), LayoutParams(0, ui.dp(48), 1f))
        }
        if (t.canPause) action(R.drawable.ic_pause, "Pause ${t.name}", pause)
        if (t.canResume) action(R.drawable.ic_play, "Resume ${t.name}", resume)
        if (t.running || t.canResume) action(R.drawable.ic_x, "Cancel ${t.name}", cancel)
        if (t.phase == "complete") action(R.drawable.ic_folder_open, "Open Downloads", open)
        if (!t.running && t.phase in listOf("complete", "cancelled")) {
            actions.addView(ui.button("Clear from list", action = remove).apply {
                contentDescription = "Clear ${t.name} from list. Files kept on device."
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
    }
}
