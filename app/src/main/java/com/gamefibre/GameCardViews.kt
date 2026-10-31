package com.gamefibre

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** One layout for library cards, download rows, and store results. */
internal data class GameCardViews(val card: LinearLayout, val art: ImageView, val title: TextView,
    val subtitle: TextView, val coverTitle: TextView, val progress: GameProgressView,
    val backdrop: ImageView, val backdropShade: View, val selectionMark: ImageView, val metric: TextView)

/** Native click/long-click handling, with a drag cancelled once it crosses Android's touch slop. */
private class GameCardLayout(context: Context) : LinearLayout(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragged = false
    @android.annotation.SuppressLint("ClickableViewAccessibility") // super handles accessible taps; only drags are cancelled.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; dragged = false }
            MotionEvent.ACTION_MOVE -> if (!dragged && maxOf(abs(event.x - downX), abs(event.y - downY)) > slop) {
                dragged = true
                val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                try { super.onTouchEvent(cancel) } finally { cancel.recycle() }
            }
            MotionEvent.ACTION_UP -> if (dragged) { dragged = false; return true }
            MotionEvent.ACTION_CANCEL -> dragged = false
        }
        return if (dragged) true else super.onTouchEvent(event)
    }
}

internal fun createGameCardViews(context: Context, ui: NativeUi, viewType: Int, columns: Int): GameCardViews {
    val card = GameCardLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        isFocusable = true; isClickable = true
        layoutParams = RecyclerView.LayoutParams(-1, -2).apply { setMargins(ui.dp(5), ui.dp(5), ui.dp(5), ui.dp(12)) }
    }
    val compact = viewType == 2
    val listView = viewType != 0
    val content = if (listView) ui.row().also { ui.add(card, it) } else card
    val artFrame = object : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            if (listView) super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            else super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(View.MeasureSpec.getSize(widthMeasureSpec) * 3 / 2, View.MeasureSpec.EXACTLY))
        }
    }.apply { background = ui.shape(ui.surface, 12); clipToOutline = true }
    val art = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    val backdrop = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
    val shade = View(context).apply { setBackgroundColor(0x88000000.toInt()); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
    artFrame.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
    artFrame.addView(shade, FrameLayout.LayoutParams(-1, -1))
    artFrame.addView(art, FrameLayout.LayoutParams(-1, -1))
    val coverTitle = ui.text("", 14, Color.WHITE, medium = true).apply {
        maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END
        gravity = Gravity.BOTTOM or Gravity.START
        setPadding(ui.dp(12), ui.dp(28), ui.dp(12), ui.dp(12))
        background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.TRANSPARENT, Color.argb(220, 0, 0, 0)))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    artFrame.addView(coverTitle, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
    val selectionMark = ui.icon(R.drawable.ic_check, color = ui.onPrimary).apply {
        setPadding(ui.dp(5), ui.dp(5), ui.dp(5), ui.dp(5))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    artFrame.addView(selectionMark, FrameLayout.LayoutParams(ui.dp(28), ui.dp(28), Gravity.TOP or Gravity.START).apply {
        topMargin = ui.dp(8); marginStart = ui.dp(8)
    })
    val metascoreBadge = ui.text("", 14, Color.WHITE, medium = true).apply {
        gravity = Gravity.CENTER
        isSingleLine = true
        minHeight = ui.dp(28)
        minWidth = ui.dp(36)
        setPadding(ui.dp(8), ui.dp(2), ui.dp(8), ui.dp(2))
        // Keep the score legible over both light and dark artwork in either theme.
        background = ui.shape(Color.rgb(24, 28, 34), 14, Color.WHITE)
        elevation = ui.dp(4).toFloat()
        visibility = View.GONE
        // Informational badge: the card remains the only tap/accessibility target.
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    val title = ui.text("", 14, medium = true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
    val subtitle = ui.text("", 11, ui.secondary)
    val progress = GameProgressView(context)
    if (listView) {
        content.addView(artFrame, LinearLayout.LayoutParams(ui.dp(112), ui.dp(60)).apply { marginEnd = ui.dp(12) })
        val words = ui.column(); ui.add(words, title); ui.add(words, subtitle)
        if (compact) ui.add(words, progress)
        content.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(metascoreBadge, LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(8) })
    } else {
        artFrame.addView(metascoreBadge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            topMargin = ui.dp(8); marginEnd = ui.dp(8)
        })
        val width = (context.resources.configuration.screenWidthDp - 32) / columns - 10
        ui.add(content, artFrame, width * 3 / 2)
        ui.add(content, title); ui.add(content, subtitle)
    }
    return GameCardViews(card, art, title, subtitle, coverTitle, progress, backdrop, shade, selectionMark, metascoreBadge)
}

internal fun bindCardMetric(view: TextView, value: String?, description: String?) {
    view.text = value.orEmpty()
    view.visibility = if (value != null) View.VISIBLE else View.GONE
    view.tooltipText = description
}
