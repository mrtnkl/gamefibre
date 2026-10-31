package com.gamefibre

import android.content.SharedPreferences
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

internal fun populateSettings(content: LinearLayout, ui: NativeUi, preferences: SharedPreferences,
    theme: () -> Unit, youtube: () -> Unit, coverArt: () -> Unit,
    export: () -> Unit, importBackup: () -> Unit, storage: () -> Unit, attribution: () -> Unit) {
    fun section(title: String) {
        if (content.childCount > 0) ui.gap(content, 24)
        ui.add(content, ui.text(title, 14, ui.blue, medium = true).apply {
            isAccessibilityHeading = true
            setPadding(ui.dp(12), 0, ui.dp(12), ui.dp(8))
        })
    }
    fun row(title: String, summary: String, action: () -> Unit) {
        ui.add(content, ui.button(title, action = action).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            minimumHeight = ui.dp(72)
            contentDescription = "$title. $summary"
            removeAllViews()
            addView(ui.column().apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                addView(ui.text(title, 16))
                addView(ui.text(summary, 13, ui.secondary).apply {
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(-1, -2))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ui.icon(R.drawable.ic_arrow_right), LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)).apply {
                marginStart = ui.dp(12)
            })
        })
    }

    section("Appearance")
    row("Theme", preferences.getString("theme", "System")!!, theme)

    section("Game details")
    val searches = YouTubeQueries.lines(preferences.getString(YouTubeQueries.PREFERENCE,
        YouTubeQueries.defaults.joinToString("\n")).orEmpty()).size
    row("YouTube searches", when (searches) {
        0 -> "Videos hidden"
        1 -> "1 search per game"
        else -> "$searches searches per game"
    }, youtube)
    val hasKey = SteamGridDbKeyVault(content.context).read() != null
    row("Cover art", "SteamGridDB · ${if (hasKey) "API key saved" else "API key required"}", coverArt)

    section("Data and storage")
    row("Backup and export", "Collection backup (ZIP) or spreadsheet (CSV)", export)
    row("Import backup", "Restore collection data from a ZIP backup", importBackup)
    row("Storage", "Offline artwork and temporary game details", storage)

    section("About")
    row("Attribution", "Data sources and credits", attribution)
}
