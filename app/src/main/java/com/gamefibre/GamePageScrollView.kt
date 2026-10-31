package com.gamefibre

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs

/** Page swipes leave gestures originating in horizontal content entirely with that content. */
internal class GamePageScrollView(context: Context, private val closePage: () -> Unit,
    private val swipeGame: ((Int) -> Unit)? = null) : ViewportScrollView(context) {
    private val gesture = GamePageGesture(ViewConfiguration.get(context).scaledTouchSlop.toFloat(),
        72 * resources.displayMetrics.density)

    var adjacentPage: ((Int) -> View?)? = null
    private var startX = 0f
    private var startY = 0f
    private var incoming: View? = null
    private var incomingDirection = 0
    private var settling: ValueAnimator? = null
    private var cancelled = false

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (settling != null) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX; startY = event.rawY; cancelled = false
                gesture.start(startX, startY,
                    atTop = scrollY == 0 && !canScrollVertically(-1),
                    horizontalAllowed = swipeGame != null && !hitsHorizontalContent(event))
            }
            MotionEvent.ACTION_MOVE -> {
                val wasClaimed = gesture.claimed
                // Screen coordinates stay stable while this view moves beneath the finger.
                gesture.move(event.rawX, event.rawY)
                if (!wasClaimed && gesture.claimed) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                    try { super.dispatchTouchEvent(cancel) } finally { cancel.recycle() }
                }
                if (gesture.claimed && gesture.horizontal && !cancelled) {
                    drag(event.rawX - startX)
                } else if (gesture.claimed && !cancelled) {
                    translationY = (event.rawY - startY).coerceIn(0f, height.toFloat())
                }
            }
            MotionEvent.ACTION_UP -> {
                val action = gesture.finish(event.rawX, event.rawY)
                if (incomingDirection != 0 || translationX != 0f) {
                    val direction = when (action) {
                        GamePageGesture.Action.NEXT -> 1
                        GamePageGesture.Action.PREVIOUS -> -1
                        else -> 0
                    }
                    settle(direction != 0 && direction == incomingDirection && incoming != null)
                } else if (gesture.claimed && !gesture.horizontal) {
                    settleClose(action == GamePageGesture.Action.CLOSE)
                }
                if (gesture.claimed) return true
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                gesture.cancel(); cancelled = true
                if (incomingDirection != 0 || translationX != 0f) settle(false)
                else if (translationY != 0f) settleClose(false)
            }
        }
        return gesture.claimed || super.dispatchTouchEvent(event)
    }

    private fun drag(distance: Float) {
        val direction = if (distance < 0) 1 else -1
        if (incomingDirection != direction) {
            removeIncoming()
            incomingDirection = direction
            incoming = adjacentPage?.invoke(direction)?.also { page ->
                page.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                (parent as? FrameLayout)?.addView(page, FrameLayout.LayoutParams(-1, -1))
                page.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
                page.layout(left, top, right, bottom)
            }
        }
        // A little resistance signals the beginning/end of the source list.
        translationX = if (incoming == null) distance * 0.2f else distance.coerceIn(-width.toFloat(), width.toFloat())
        incoming?.translationX = translationX + direction * width
    }

    private fun settle(commit: Boolean) {
        val direction = incomingDirection
        val from = translationX
        val to = if (commit) -direction * width.toFloat() else 0f
        val animator = ValueAnimator.ofFloat(from, to).apply {
            duration = (240 * (abs(to - from) / width.coerceAtLeast(1))).toLong().coerceIn(100L, 240L)
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                translationX = it.animatedValue as Float
                incoming?.translationX = translationX + direction * width
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    settling = null
                    translationX = 0f
                    removeIncoming()
                    if (commit) swipeGame?.invoke(direction)
                }
            })
        }
        settling = animator
        animator.start()
    }

    private fun removeIncoming() {
        incoming?.let { (it.parent as? ViewGroup)?.removeView(it) }
        incoming = null; incomingDirection = 0
    }

    private fun settleClose(commit: Boolean) {
        val from = translationY
        val to = if (commit) height.toFloat() else 0f
        val animator = ValueAnimator.ofFloat(from, to).apply {
            duration = (240 * (abs(to - from) / height.coerceAtLeast(1))).toLong().coerceIn(100L, 240L)
            interpolator = DecelerateInterpolator()
            addUpdateListener { translationY = it.animatedValue as Float }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    settling = null
                    if (commit) closePage() else translationY = 0f
                }
            })
        }
        settling = animator
        animator.start()
    }

    override fun onDetachedFromWindow() {
        settling?.removeAllListeners()
        settling?.cancel(); settling = null
        // A render may be removing all siblings already; avoid changing that child list mid-detach.
        incoming?.let { page ->
            val owner = page.parent as? ViewGroup
            owner?.post { if (page.parent === owner) owner.removeView(page) }
        }
        incoming = null; incomingDirection = 0; translationX = 0f; translationY = 0f
        super.onDetachedFromWindow()
    }

    private fun hitsHorizontalContent(event: MotionEvent): Boolean {
        // Visible rectangles are relative to the root; raw events include the window offset.
        val origin = IntArray(2)
        rootView.getLocationOnScreen(origin)
        return hitsHorizontalContent(this, event.rawX.toInt() - origin[0], event.rawY.toInt() - origin[1])
    }

    private fun hitsHorizontalContent(view: View, x: Int, y: Int): Boolean {
        val rect = Rect()
        if (!view.isShown || !view.getGlobalVisibleRect(rect) || !rect.contains(x, y)) return false
        // Protect both ends and single-image galleries, even if the drag leaves their bounds.
        if (view is ScreenshotCarousel || view is android.webkit.WebView || view is ViewPager2 || view is HorizontalScrollView ||
            (view is RecyclerView && view.layoutManager?.canScrollHorizontally() == true)) return true
        return view is ViewGroup && (0 until view.childCount).any { hitsHorizontalContent(view.getChildAt(it), x, y) }
    }
}
