package com.gamefibre

import android.content.Context
import android.view.View
import android.view.ViewGroup

/** Wrap chips at the available width and center each row, including the final row. */
internal class CenteredChipGroup(context: Context) : ViewGroup(context) {
    private val gap = (8 * resources.displayMetrics.density + .5f).toInt()
    private var rows = emptyList<List<View>>()

    override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE
            else (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
        val measuredRows = mutableListOf<List<View>>()
        var row = mutableListOf<View>()
        var rowWidth = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == GONE) continue
            child.measure(MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST),
                getChildMeasureSpec(heightMeasureSpec, paddingTop + paddingBottom, child.layoutParams.height))
            val spacing = if (row.isEmpty()) 0 else gap
            if (row.isNotEmpty() && rowWidth.toLong() + spacing + child.measuredWidth > available) {
                measuredRows.add(row)
                row = mutableListOf()
                rowWidth = 0
            }
            if (row.isNotEmpty()) rowWidth += gap
            row.add(child)
            rowWidth += child.measuredWidth
        }
        if (row.isNotEmpty()) measuredRows.add(row)
        rows = measuredRows
        val width = rows.maxOfOrNull { chips -> chips.sumOf { it.measuredWidth } + gap * (chips.size - 1) } ?: 0
        val height = rows.sumOf { chips -> chips.maxOf { it.measuredHeight } } + gap * (rows.size - 1).coerceAtLeast(0)
        setMeasuredDimension(resolveSize(width + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(height + paddingTop + paddingBottom, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        var y = paddingTop
        for (row in rows) {
            val rowWidth = row.sumOf { it.measuredWidth } + gap * (row.size - 1)
            val rowHeight = row.maxOf { it.measuredHeight }
            var x = paddingLeft + (width - paddingLeft - paddingRight - rowWidth) / 2
            val ordered = if (layoutDirection == LAYOUT_DIRECTION_RTL) row.asReversed() else row
            for (child in ordered) {
                val childTop = y + (rowHeight - child.measuredHeight) / 2
                child.layout(x, childTop, x + child.measuredWidth, childTop + child.measuredHeight)
                x += child.measuredWidth + gap
            }
            y += rowHeight + gap
        }
    }
}
