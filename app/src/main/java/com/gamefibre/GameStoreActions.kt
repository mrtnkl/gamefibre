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
    redownload: ((LibraryEntry) -> Unit)? = null,
) : LinearLayout(context) {
    private fun storeLabel(source: LibraryEntry) = if (source.remote?.xboxAccess == XboxAccess.GAME_PASS) "Game Pass" else source.store.label
    private val selector = if (sources.size > 1) ui.dropdownChip(storeLabel(current()), false, "Choose store") {
        showStores()
    } else null
    private val button = ui.button("Download", true, R.drawable.ic_download) { action(current()) }
    private val openButton = ui.button(OPEN_DOWNLOADS, true, R.drawable.ic_folder_open) { action(current()) }
    private val againButton = ui.button("Download again", false, R.drawable.ic_download) { redownload?.invoke(current()) }
    // Downloaded games offer the folder and a fresh download side by side.
    private val pair = ui.row().apply {
        listOf(openButton, againButton).forEach { control ->
            control.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
            (control.getChildAt(control.childCount - 1) as TextView).apply {
                isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END
            }
        }
        addView(openButton, LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(8) })
        addView(againButton, LayoutParams(0, -2, 1f))
        visibility = GONE
    }

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
        val buttons = ui.column()
        buttons.addView(button, LayoutParams(-1, -2))
        buttons.addView(pair, LayoutParams(-1, -2))
        addView(buttons, LayoutParams(if (selector == null) -1 else 0, -2, if (selector == null) 0f else 1f))
        update()
    }

    /** The folder button keeps its icon and drops to "Downloads" only when the full label cannot fit. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (pair.visibility != VISIBLE) return
        val label = openButton.getChildAt(openButton.childCount - 1) as TextView
        val icon = openButton.getChildAt(0)
        val available = openButton.measuredWidth - openButton.paddingLeft - openButton.paddingRight -
            icon.layoutParams.width - (icon.layoutParams as MarginLayoutParams).marginEnd
        val wanted = if (label.paint.measureText(OPEN_DOWNLOADS) <= available) OPEN_DOWNLOADS else OPEN_DOWNLOADS_SHORT
        if (label.text.toString() != wanted) {
            label.text = wanted
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
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
            selector == null -> "Download from ${source.store.label}"
            else -> "Download"
        }
        selector?.let {
            (it.getChildAt(0) as TextView).text = storeLabel(source)
            it.contentDescription = "Store: ${storeLabel(source)}. Choose store"
        }
        button.visibility = if (ready && !viewOnly) GONE else VISIBLE
        pair.visibility = if (ready && !viewOnly) VISIBLE else GONE
        (button.getChildAt(0) as ImageView).setImageResource(if (viewOnly) R.drawable.ic_gamepad_2 else R.drawable.ic_download)
        (button.getChildAt(button.childCount - 1) as TextView).text = label
        button.contentDescription = if (selector == null) label else "$label. ${storeLabel(source)}"
        openButton.contentDescription = if (selector == null) OPEN_DOWNLOADS else "$OPEN_DOWNLOADS. ${storeLabel(source)}"
        againButton.contentDescription = if (selector == null) "Download again" else "Download again. ${storeLabel(source)}"
    }

    companion object {
        const val OPEN_DOWNLOADS = "Open Downloads"
        const val OPEN_DOWNLOADS_SHORT = "Downloads"
    }
}
