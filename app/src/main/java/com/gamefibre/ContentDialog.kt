package com.gamefibre

import android.app.Dialog
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView

/** A constrained scrolling body with actions that remain visible, including above the keyboard. */
internal class ContentDialog(context: Context, private val ui: NativeUi, title: String? = null,
    bodyHeightDp: Int? = null) : Dialog(context) {
    val content = ui.column(28)
    private val actions = DialogActions(context, ui)

    init {
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        val panel = ui.column().apply {
            background = ui.shape(ui.background, 28)
            clipToOutline = true
        }
        title?.let {
            val heading = ui.row().apply { setPadding(ui.dp(28), ui.dp(16), ui.dp(16), 0) }
            heading.addView(ui.text(it, 24, medium = true), LinearLayout.LayoutParams(0, -2, 1f))
            heading.addView(ui.iconButton(R.drawable.ic_x, "Close $it") { dismiss() })
            ui.add(panel, heading)
        }
        panel.addView(ScrollView(context).apply { addView(content) },
            LinearLayout.LayoutParams(-1, bodyHeightDp?.let(ui::dp) ?: -2, 1f))
        panel.addView(actions, LinearLayout.LayoutParams(-1, -2))
        setContentView(panel)
    }

    fun setActions(vararg buttons: View) = actions.setActions(buttons.toList())

    override fun show() {
        super.show()
        window?.setLayout(ui.dp(520).coerceAtMost(context.resources.displayMetrics.widthPixels - ui.dp(32)), -2)
    }
}

internal fun showChoiceDialog(context: Context, ui: NativeUi, title: String, options: List<String>,
    selected: Int? = null, choose: (Int) -> Unit) {
    val dialog = ContentDialog(context, ui, title)
    options.forEachIndexed { index, label ->
        val option = if (selected == null) ui.button(label) { dialog.dismiss(); choose(index) }
        else android.widget.RadioButton(context).apply {
            text = label; textSize = 16f; typeface = ui.font; setTextColor(ui.ink)
            buttonTintList = android.content.res.ColorStateList.valueOf(ui.blue)
            minimumHeight = ui.dp(48); isChecked = index == selected
            setOnClickListener { dialog.dismiss(); choose(index) }
        }
        ui.add(dialog.content, option)
    }
    dialog.setActions(ui.button("Cancel") { dialog.dismiss() })
    dialog.show()
}

/** Trailing actions sit side by side when they fit, and stack at narrow widths or large font sizes. */
private class DialogActions(context: Context, private val ui: NativeUi) : LinearLayout(context) {
    private var buttons = emptyList<View>()

    init {
        gravity = Gravity.END
        setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(16))
        visibility = GONE
    }

    fun setActions(value: List<View>) {
        buttons = value
        visibility = if (buttons.isEmpty()) GONE else VISIBLE
        arrange(false)
    }

    private fun arrange(stacked: Boolean) {
        orientation = if (stacked) VERTICAL else HORIZONTAL
        removeAllViews()
        // Positive action is trailing in a row, and first in a stack.
        (if (stacked) buttons else buttons.reversed()).forEach {
            addView(it, LayoutParams(-2, -2).apply { gravity = Gravity.END })
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val naturalWidth = buttons.sumOf {
            it.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            it.measuredWidth
        }
        val stacked = MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED && naturalWidth > available
        if ((orientation == VERTICAL) != stacked) arrange(stacked)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
