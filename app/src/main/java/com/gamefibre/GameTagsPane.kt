package com.gamefibre

import android.widget.LinearLayout

internal fun gameTagsPane(ui: NativeUi, tags: List<String>, selected: MutableSet<String>, loading: Boolean,
    search: (Set<String>) -> Unit): LinearLayout = ui.column(16).apply {
    background = ui.shape(ui.surface)
    ui.add(this, ui.text("Tags", 18, medium = true))
    ui.gap(this, 12)
    val available = (tags + selected).distinct()
    if (available.isEmpty()) {
        ui.add(this, ui.text(if (loading) "Loading Steam community tags…" else "No Steam community tags available.", 13, ui.secondary))
    } else {
        val pills = CenteredChipGroup(ui.context)
        lateinit var button: LinearLayout
        fun update() {
            button.isEnabled = selected.isNotEmpty()
            button.alpha = if (button.isEnabled) 1f else .45f
        }
        available.forEach { tag ->
            pills.addView(ui.listChip(tag, tag in selected) { checked ->
                if (checked) selected.add(tag) else selected.remove(tag)
                update()
            })
        }
        ui.add(this, pills)
        ui.gap(this, 8)
        button = ui.button(ui.context.getString(R.string.search_selected_tags), true, R.drawable.ic_search) { search(selected.toSet()) }
        button.contentDescription = "Search games with all selected tags"
        update()
        ui.add(this, button)
    }
}
