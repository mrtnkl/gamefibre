package com.gamefibre

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Unframed, finger-tracked pages. Adjacent images load only while the gallery is in view. */
internal class ScreenshotCarousel(context: Context, parent: CoroutineScope, private val urls: List<String>,
    private val title: String, private val load: suspend (String) -> Bitmap?,
    private val open: (Int) -> Unit) : LinearLayout(context) {
    private val ui = NativeUi(context)
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val attached = linkedSetOf<Holder>()
    private val pager = ViewPager2(context).apply { offscreenPageLimit = 1 }
    private val dots = ScreenshotDots(context, urls.size, indicatorColor = ui.blue, drawShadow = false)
    private val scrollListener = ViewTreeObserver.OnScrollChangedListener { loadVisibleGallery() }
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var decidingDirection = false

    private class Holder(val image: ImageView) : RecyclerView.ViewHolder(image) {
        var url = ""
        var job: Job? = null
        var loaded = false
    }
    init {
        orientation = VERTICAL
        pager.adapter = object : RecyclerView.Adapter<Holder>() {
            override fun getItemCount() = urls.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = RecyclerView.LayoutParams(-1, -1)
            })
            override fun onBindViewHolder(holder: Holder, index: Int) {
                holder.job?.cancel(); holder.job = null
                holder.url = urls[index]; holder.loaded = false; holder.image.setImageDrawable(null)
                holder.image.contentDescription = "Screenshot ${index + 1} of ${urls.size} for $title"
                holder.image.setOnClickListener { open(index) }
                if (holder in attached) loadVisibleGallery()
            }
            override fun onViewAttachedToWindow(holder: Holder) { attached.add(holder); loadVisibleGallery() }
            override fun onViewDetachedFromWindow(holder: Holder) { attached.remove(holder); holder.job?.cancel(); holder.job = null }
            override fun onViewRecycled(holder: Holder) {
                attached.remove(holder); holder.job?.cancel(); holder.job = null
                holder.url = ""; holder.loaded = false; holder.image.setImageDrawable(null)
            }
        }
        addView(pager, LayoutParams(-1, ui.dp(200)))
        ui.add(this, dots, 36)
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(index: Int) { dots.selected = index; loadVisibleGallery() }
        })
        dots.selected = 0
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = event.x; touchStartY = event.y
                decidingDirection = event.x >= pager.left && event.x < pager.right &&
                    event.y >= pager.top && event.y < pager.bottom
                // The vertical ScrollView can intercept before ViewPager2 clears its paging slop.
                // Reserve screenshot touches until their direction is known.
                if (decidingDirection) parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> if (decidingDirection) {
                val dx = abs(event.x - touchStartX)
                val dy = abs(event.y - touchStartY)
                if (maxOf(dx, dy) > touchSlop) {
                    decidingDirection = false
                    parent?.requestDisallowInterceptTouchEvent(dx > dy)
                }
            }
        }
        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            decidingDirection = false
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return handled
    }
    private fun loadVisibleGallery() {
        if (!isShown || !getGlobalVisibleRect(Rect())) return
        attached.filter { !it.loaded && it.job?.isActive != true && it.url.isNotBlank() }.forEach { holder ->
            val url = holder.url
            holder.job = scope.launch {
                val bitmap = load(url)
                if (holder.url == url) { holder.image.setImageBitmap(bitmap); holder.loaded = true }
            }
        }
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        pager.layoutParams.height = minOf(width * 9 / 16, ui.dp(resources.configuration.screenHeightDp * 3 / 5)).coerceAtLeast(ui.dp(100))
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnScrollChangedListener(scrollListener)
        post { loadVisibleGallery() }
    }
    override fun onDetachedFromWindow() {
        if (viewTreeObserver.isAlive) viewTreeObserver.removeOnScrollChangedListener(scrollListener)
        scope.cancel(); attached.clear()
        super.onDetachedFromWindow()
    }
}
