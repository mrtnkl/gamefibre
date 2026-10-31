package com.gamefibre

import android.app.Dialog
import android.content.Context
import android.view.MotionEvent
import kotlin.math.abs

/** A leftward drag closes the menu without stealing vertical scrolling or activating a row. */
internal class SwipeDismissMenu(context: Context) : Dialog(context) {
    private val distance = 48 * context.resources.displayMetrics.density
    private var startX = 0f
    private var startY = 0f
    private var tracking = false
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { startX = event.x; startY = event.y; tracking = true }
            MotionEvent.ACTION_MOVE -> if (tracking) {
                val dx = event.x - startX; val dy = abs(event.y - startY)
                if (dy >= distance && dy >= abs(dx)) tracking = false
                else if (dx <= -distance && -dx > dy * 1.5f) {
                    tracking = false
                    val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                    try { super.dispatchTouchEvent(cancel) } finally { cancel.recycle() }
                    dismiss(); return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> tracking = false
        }
        return super.dispatchTouchEvent(event)
    }
}
