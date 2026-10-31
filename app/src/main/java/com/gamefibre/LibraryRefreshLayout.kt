package com.gamefibre

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlin.math.abs

/** Leave horizontal drags to the list pager, even when the finger also moves down. */
internal class LibraryRefreshLayout(context: Context) : SwipeRefreshLayout(context) {
    private val dismissIndicator=Runnable { isRefreshing=false }

    /** Acknowledge the gesture; background work owns its own lifetime. */
    fun acknowledgeRefresh() {
        removeCallbacks(dismissIndicator)
        isRefreshing=true
        postDelayed(dismissIndicator,750)
    }
    fun dismissRefreshIndicator() {
        removeCallbacks(dismissIndicator)
        isRefreshing=false
    }
    override fun onRestoreInstanceState(state:android.os.Parcelable?) {
        super.onRestoreInstanceState(state)
        dismissRefreshIndicator()
    }
    override fun onDetachedFromWindow() {
        dismissRefreshIndicator()
        super.onDetachedFromWindow()
    }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var startX = 0f
    private var startY = 0f
    private var horizontal = false

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x; startY = event.y; horizontal = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(event.x - startX)
                val dy = abs(event.y - startY)
                if (dx > slop && dx > dy) horizontal = true
            }
        }
        return !horizontal && super.onInterceptTouchEvent(event)
    }
}
