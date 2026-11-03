package com.gamefibre

import android.content.Context
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import com.gamefibre.stores.XboxAccess

/** Store selection stays beside the action it controls, in pages and swipe previews. */
@android.annotation.SuppressLint("ViewConstructor") // Built in code with the game's sources and actions.
internal class GameStoreActions(context: Context, private val ui: NativeUi,
    private val sources: List<LibraryEntry>, private val current: () -> LibraryEntry,
    private val select: (LibraryEntry) -> Unit, private val owned: (LibraryEntry) -> Boolean,
    private val downloaded: (LibraryEntry) -> Boolean, action: (LibraryEntry) -> Unit,
) : LinearLayout(context) {
    private fun storeLabel(source: LibraryEntry) = if (source.remote?.xboxAccess == XboxAccess.GAME_PASS) "Game Pass" else source.store.label
    private val selector = if (sources.size > 1) ui.dropdownChip(storeLabel(current()), false, "Choose store") {
        showStores()
    } else null
    private val button = ui.button("Download", true, R.drawable.ic_download) { action(current()) }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        selector?.let { control ->
            (control.getChildAt(0) as TextView).apply {
                isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LayoutParams(0, -2, 1f)
            }
            addView(control, LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(12) })
        }
        addView(button, LayoutParams(if (selector == null) -1 else 0, -2, if (selector == null) 0f else 1f))
        update()
    }

    private fun showStores() {
        PopupMenu(context, selector!!).apply {
            sources.forEachIndexed { index, source ->
                menu.add(0, index, index, storeLabel(source)).apply {
                    isCheckable = true; isChecked = source.key == current().key
                }
            }
            menu.setGroupCheckable(0, true, true)
            setOnMenuItemClickListener { item -> select(sources[item.itemId]); update(); true }
            show()
        }
    }

    fun update() {
        val source = current()
        val viewOnly = !owned(source) || !source.store.downloads
        val ready = downloaded(source)
        val label = when {
            viewOnly -> if (selector == null) "View on ${source.store.label}" else "View"
            ready -> "Open Downloads"
            selector == null -> "Download from ${source.store.label}"
            else -> "Download"
        }
        selector?.let {
            (it.getChildAt(0) as TextView).text = storeLabel(source)
            it.contentDescription = "Store: ${storeLabel(source)}. Choose store"
        }
        (button.getChildAt(0) as ImageView).setImageResource(when {
            viewOnly -> R.drawable.ic_gamepad_2
            ready -> R.drawable.ic_folder_open
            else -> R.drawable.ic_download
        })
        (button.getChildAt(button.childCount - 1) as TextView).text = label
        button.contentDescription = if (selector == null) label else "$label. ${storeLabel(source)}"
    }
}
