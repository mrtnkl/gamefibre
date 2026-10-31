package com.gamefibre

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout

/** Draws one pill behind the labels, following the pager's fractional position. */
internal class LibraryListBar(context: Context, color: Int, private val radius: Float) : LinearLayout(context) {
    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private val indicatorBounds = RectF()
    private val edgeSpacing = (8 * resources.displayMetrics.density).toInt()
    var pagePosition = 0f
        set(value) {
            if (field == value) return
            field = value
            revealPosition()
            invalidate()
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        setWillNotDraw(false)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        post { revealPosition() }
    }

    /** Follow the animated pill without starting a separate scroll animation on every frame. */
    fun revealPosition() {
        val scroll = parent as? HorizontalScrollView ?: return
        if (isLayoutRequested || scroll.width == 0 || !updateIndicatorBounds()) return
        val viewportWidth = scroll.width - scroll.paddingLeft - scroll.paddingRight
        val pillLeft = left - scroll.paddingLeft + indicatorBounds.left.toInt() - edgeSpacing
        val pillRight = left - scroll.paddingLeft + kotlin.math.ceil(indicatorBounds.right).toInt() + edgeSpacing
        val target = when {
            pillLeft < scroll.scrollX -> pillLeft
            pillRight > scroll.scrollX + viewportWidth ->
                minOf(pillLeft, pillRight - viewportWidth)
            else -> return
        }
        val maxScroll = (width + scroll.paddingLeft + scroll.paddingRight - scroll.width).coerceAtLeast(0)
        scroll.scrollTo(target.coerceIn(0, maxScroll), 0)
    }

    private fun updateIndicatorBounds(): Boolean {
        if (childCount == 0) return false
        val position = pagePosition.coerceIn(0f, (childCount - 1).toFloat())
        val index = position.toInt()
        val fraction = position - index
        val from = getChildAt(index)
        val to = getChildAt((index + 1).coerceAtMost(childCount - 1))
        // Interpolate both edges so differently sized labels share the same progress.
        indicatorBounds.set(
            from.left + (to.left - from.left) * fraction,
            from.top + (to.top - from.top) * fraction,
            from.right + (to.right - from.right) * fraction,
            from.bottom + (to.bottom - from.bottom) * fraction
        )
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (updateIndicatorBounds()) canvas.drawRoundRect(indicatorBounds, radius, radius, indicatorPaint)
    }
}
