package com.gamefibre

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/** Decide once per gesture: a vertical drag cannot turn into a page swipe halfway through. */
internal class LibraryGestureHost(context:Context,private val vertical:(Boolean)->Unit):FrameLayout(context) {
    private val slop=ViewConfiguration.get(context).scaledTouchSlop
    private var startX=0f
    private var startY=0f
    private var decided=false
    override fun dispatchTouchEvent(event:MotionEvent):Boolean {
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX=event.x;startY=event.y;decided=false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> if(!decided) {
                val dx=abs(event.x-startX);val dy=abs(event.y-startY)
                if(maxOf(dx,dy)>slop) {
                    decided=true
                    vertical(dy>dx)
                    // RecyclerView, SwipeRefreshLayout and the app bar keep their native handling.
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
        }
        return try {super.dispatchTouchEvent(event)} finally {
            if(event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_CANCEL) {
                vertical(false);parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
    }
    override fun onDetachedFromWindow(){vertical(false);super.onDetachedFromWindow()}
}
