package com.gamefibre

import android.content.Context
import android.content.SharedPreferences
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

internal fun showYouTubeSettings(context: Context, ui: NativeUi, preferences: SharedPreferences, saved: () -> Unit = {}) {
    val dialog = ContentDialog(context, ui, "YouTube searches")
    val body = dialog.content
    ui.add(body, ui.text("The game name is automatically added before each search. Remove all searches to hide videos.", 14, ui.secondary))
    ui.gap(body)
    val rows = ui.column()
    ui.add(body, rows)
    data class QueryField(val input: EditText, val label: TextView, val delete: View)
    val fields = mutableListOf<QueryField>()
    fun updateLabels() {
        fields.forEachIndexed { index, field ->
            val label = "Search query ${index + 1}"
            field.label.text = label
            field.input.contentDescription = label
            field.delete.contentDescription = "Delete query ${index + 1}"
            field.input.imeOptions = if (index == fields.lastIndex) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NEXT
        }
    }
    fun addQuery(value: String = "", focus: Boolean = false) {
        val container = ui.column()
        val label = ui.text("", 12, ui.secondary)
        ui.add(container, label)
        ui.gap(container, 8)
        val row = ui.row()
        val edit = ui.input("Search query").apply {
            id = View.generateViewId()
            hint = null
            label.labelFor = id
            setText(value)
        }
        row.addView(edit, LinearLayout.LayoutParams(0, -2, 1f))
        val delete = ui.iconButton(R.drawable.ic_delete, "Delete query") {
            val index = fields.indexOfFirst { it.input === edit }
            val hadFocus = edit.hasFocus()
            fields.removeAt(index); rows.removeView(container)
            updateLabels()
            if (hadFocus) {
                if (fields.isNotEmpty()) fields[index.coerceAtMost(fields.lastIndex)].input.requestFocus()
                else (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(rows.windowToken, 0)
            }
        }
        row.addView(delete)
        ui.add(container, row)
        fields.add(QueryField(edit, label, delete))
        rows.addView(container, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(12) })
        updateLabels()
        if (focus) {
            edit.requestFocus()
            edit.post {
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }
    YouTubeQueries.lines(preferences.getString(YouTubeQueries.PREFERENCE, YouTubeQueries.defaults.joinToString("\n")).orEmpty())
        .forEach { addQuery(it) }
    body.addView(ui.button("Add query", icon = R.drawable.ic_add) { addQuery(focus = true) })
    dialog.setActions(ui.button("Save", true) {
        val queries = fields.map { it.input.text.toString().trim() }.filter(String::isNotEmpty)
        preferences.edit().putString(YouTubeQueries.PREFERENCE, queries.joinToString("\n")).apply()
        dialog.dismiss()
        saved()
    }, ui.button("Cancel") { dialog.dismiss() })
    dialog.show()
}
