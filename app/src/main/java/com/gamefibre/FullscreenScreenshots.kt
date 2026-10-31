package com.gamefibre

import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.FrameLayout
import android.view.View
import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.*

/** Zoom belongs to the current page; paging resumes when the image returns to fit. */
internal fun showFullscreenScreenshots(context: Context, parent: CoroutineScope, urls: List<String>,
    title: String, initial: Int, changed: (Int) -> Unit, closed: () -> Unit, imageLabel: String = "Screenshot",
    load: suspend (String) -> Bitmap?): Dialog {
    val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    val ui = NativeUi(context)
    val root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
    val pager = ViewPager2(context).apply { offscreenPageLimit = 1 }
    val dots = ScreenshotDots(context, urls.size, imageLabel)
    root.addView(pager, FrameLayout.LayoutParams(-1, -1))
    root.addView(dots, FrameLayout.LayoutParams(-1, ui.dp(36), Gravity.BOTTOM).apply { bottomMargin = ui.dp(8) })
    val close = ui.iconButton(R.drawable.ic_x, "Close fullscreen ${imageLabel.lowercase()} viewer") { dialog.dismiss() }.apply {
        (getChildAt(0) as ImageView).imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        background = ui.shape(0x66000000, 24)
    }
    root.addView(close, FrameLayout.LayoutParams(ui.dp(48), ui.dp(48), Gravity.TOP or Gravity.END).apply { topMargin = ui.dp(12); marginEnd = ui.dp(12) })
    class Holder(val image: ZoomScreenshotView) : RecyclerView.ViewHolder(image) { var job: Job? = null; var url = "" }
    pager.adapter = object : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = urls.size
        override fun onCreateViewHolder(parent: ViewGroup, type: Int) = Holder(ZoomScreenshotView(context, zoomChanged = { zoomed -> pager.isUserInputEnabled = !zoomed }, tapped = { right ->
            pager.setCurrentItem((pager.currentItem + if (right) 1 else -1).coerceIn(0, urls.lastIndex), true)
        }, dismissed = { dialog.dismiss() }).apply {
            layoutParams = RecyclerView.LayoutParams(-1, -1)
        })
        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.job?.cancel(); holder.url = urls[position]; holder.image.setImageDrawable(null)
            holder.image.contentDescription = "Fullscreen ${imageLabel.lowercase()} ${position + 1} of ${urls.size} for $title"
        }
        override fun onViewAttachedToWindow(holder: Holder) {
            val url = holder.url
            holder.job = scope.launch { val bitmap = load(url); if (holder.url == url) holder.image.setImageBitmap(bitmap) }
        }
        override fun onViewDetachedFromWindow(holder: Holder) { holder.job?.cancel(); holder.image.resetZoom(false) }
        override fun onViewRecycled(holder: Holder) { holder.job?.cancel(); holder.url = ""; holder.image.setImageDrawable(null) }
    }
    pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(index: Int) {
            pager.isUserInputEnabled = true
            dots.selected = index
            changed(index)
            val recycler = pager.getChildAt(0) as RecyclerView
            for (i in 0 until recycler.childCount) (recycler.getChildViewHolder(recycler.getChildAt(i)) as? Holder)?.image?.resetZoom(false)
        }
    })
    pager.setCurrentItem(initial, false)
    dialog.setContentView(root)
    dialog.setOnDismissListener { closed(); scope.cancel() }
    scope.coroutineContext[Job]?.invokeOnCompletion { root.post { if (dialog.isShowing) dialog.dismiss() } }
    dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    dialog.show()
    dialog.window?.apply {
        setLayout(-1, -1)
        WindowCompat.setDecorFitsSystemWindows(this, false)
        WindowCompat.getInsetsController(this, decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
    return dialog
}

internal class ScreenshotDots(context: Context, private val count: Int, private val imageLabel: String = "Screenshot",
    private val indicatorColor: Int = Color.WHITE, private val drawShadow: Boolean = true) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    var selected = 0
        set(value) { field = value; contentDescription = "$imageLabel ${value + 1} of $count"; invalidate() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val spacing = minOf(12f * resources.displayMetrics.density, width.toFloat() / (count + 2))
        val radius = minOf(3f * resources.displayMetrics.density, spacing / 3)
        val start = (width - (count - 1) * spacing) / 2
        repeat(count) { index ->
            if (drawShadow) {
                paint.color = Color.BLACK; paint.alpha = 120
                canvas.drawCircle(start + index * spacing, height / 2f, radius + resources.displayMetrics.density, paint)
            }
            paint.color = indicatorColor; paint.alpha = if (index == selected) 255 else 110
            canvas.drawCircle(start + index * spacing, height / 2f, radius, paint)
        }
    }
}

internal class ZoomScreenshotView(context: Context, private val zoomChanged: (Boolean) -> Unit, private val tapped: (Boolean) -> Unit,
    private val dismissed: () -> Unit) : androidx.appcompat.widget.AppCompatImageView(context) {
    private val transform = Matrix()
    private var zoom = 1f
    private var lastX = 0f
    private var lastY = 0f
    private var pinching = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var canSwipeClose = false
    private var swipingDown = false
    private var settling: ValueAnimator? = null
    private val scaling = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean { pinching = true; zoomChanged(true); parent.requestDisallowInterceptTouchEvent(true); return true }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val target = (zoom * detector.scaleFactor).coerceIn(1f, 5f)
            transform.postScale(target / zoom, target / zoom, detector.focusX, detector.focusY); zoom = target
            constrain(); return true
        }
    })
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true
        override fun onSingleTapConfirmed(event: MotionEvent): Boolean { tapped(event.x >= width / 2f); performClick(); return true }
        override fun onDoubleTap(event: MotionEvent): Boolean {
            if (zoom > 1f) resetZoom() else { transform.postScale(2.5f, 2.5f, event.x, event.y); zoom = 2.5f; constrain(); zoomChanged(true) }
            return true
        }
    })
    init { scaleType = ScaleType.MATRIX; isClickable = true }
    fun resetZoom(notify: Boolean = true) {
        settling?.removeAllListeners()
        settling?.cancel(); settling = null
        translationY = 0f
        zoom = 1f; transform.reset()
        drawable?.let { if (width > 0 && height > 0) transform.setRectToRect(RectF(0f, 0f, it.intrinsicWidth.toFloat(), it.intrinsicHeight.toFloat()), RectF(0f, 0f, width.toFloat(), height.toFloat()), Matrix.ScaleToFit.CENTER) }
        imageMatrix = transform
        androidx.core.view.ViewCompat.setStateDescription(this, "Zoom ${(zoom * 100).toInt()} percent")
        if (notify) zoomChanged(false)
    }
    private fun constrain() {
        val image = drawable ?: return
        val rect = RectF(0f, 0f, image.intrinsicWidth.toFloat(), image.intrinsicHeight.toFloat()); transform.mapRect(rect)
        fun correction(start: Float, end: Float, available: Float) = if (end - start <= available) (available - end - start) / 2 else if (start > 0) -start else if (end < available) available - end else 0f
        transform.postTranslate(correction(rect.left, rect.right, width.toFloat()), correction(rect.top, rect.bottom, height.toFloat()))
        imageMatrix = transform
        androidx.core.view.ViewCompat.setStateDescription(this, "Zoom ${(zoom * 100).toInt()} percent")
    }
    override fun setImageBitmap(bitmap: Bitmap?) { super.setImageBitmap(bitmap); resetZoom(false) }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); resetZoom(false) }
    override fun performClick(): Boolean = super.performClick()
    private fun settleDismiss(commit: Boolean) {
        val from = translationY
        val to = if (commit) height.toFloat() else 0f
        val animator = ValueAnimator.ofFloat(from, to).apply {
            duration = (240 * (kotlin.math.abs(to - from) / height.coerceAtLeast(1))).toLong().coerceIn(100L, 240L)
            interpolator = DecelerateInterpolator()
            addUpdateListener { translationY = it.animatedValue as Float }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    settling = null
                    if (commit) dismissed() else translationY = 0f
                }
            })
        }
        settling = animator
        animator.start()
    }
    override fun onDetachedFromWindow() {
        resetZoom(false)
        super.onDetachedFromWindow()
    }
    // Clicks are dispatched through GestureDetector.onSingleTapConfirmed, which calls performClick().
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (settling != null) return true
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            lastX = event.x; lastY = event.y; pinching = false
            downX = event.rawX; downY = event.rawY
            canSwipeClose = zoom <= 1.01f; swipingDown = false
        }
        if (event.pointerCount > 1 || zoom > 1.01f) {
            canSwipeClose = false
            if (swipingDown) { translationY = 0f; swipingDown = false }
        }
        if (event.pointerCount > 1 || zoom > 1f) { zoomChanged(true); parent.requestDisallowInterceptTouchEvent(true) }
        if (event.actionMasked == MotionEvent.ACTION_MOVE && canSwipeClose && !pinching) {
            val dx = kotlin.math.abs(event.rawX - downX)
            val dy = event.rawY - downY
            if (!swipingDown && maxOf(dx, kotlin.math.abs(dy)) > touchSlop) {
                if (dy > dx) {
                    swipingDown = true
                    val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                    try { gestures.onTouchEvent(cancel) } finally { cancel.recycle() }
                } else canSwipeClose = false
            }
            if (swipingDown) {
                parent.requestDisallowInterceptTouchEvent(true)
                translationY = dy.coerceIn(0f, height.toFloat())
            }
        }
        scaling.onTouchEvent(event)
        if (!swipingDown) gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_MOVE && !pinching && event.pointerCount == 1 && zoom > 1f) {
            transform.postTranslate(event.x - lastX, event.y - lastY); constrain()
        }
        lastX = event.x; lastY = event.y
        if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) pinching = true
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            val wasDragging = swipingDown
            val dragOffset = translationY
            val close = event.actionMasked == MotionEvent.ACTION_UP && canSwipeClose && swipingDown && !pinching &&
                event.rawY - downY >= 64f * resources.displayMetrics.density &&
                event.rawY - downY > kotlin.math.abs(event.rawX - downX)
            if (zoom <= 1.01f) resetZoom() else zoomChanged(true)
            parent.requestDisallowInterceptTouchEvent(false)
            canSwipeClose = false; swipingDown = false
            if (wasDragging) {
                translationY = dragOffset
                settleDismiss(close)
            }
        }
        return true
    }
}
