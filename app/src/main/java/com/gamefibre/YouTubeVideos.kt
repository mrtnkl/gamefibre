package com.gamefibre

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Color
import android.graphics.Rect
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.abs

/** The same unframed swipe gallery as screenshots, with YouTube's tap-to-play embeds. */
internal class YouTubeVideos(private val activity: AppCompatActivity, parent: CoroutineScope,
    private val search: YouTubeSearch, private val game: String, private val queries: List<String>,
    private val open: (String) -> Unit, private val fullscreenClosed: () -> Unit = {},
    private val settings: () -> Unit) : LinearLayout(activity) {
    private val ui = NativeUi(activity)
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val requests = Semaphore(2)
    private val pager = ViewPager2(activity).apply { offscreenPageLimit = 1 }
    private val media = FrameLayout(activity).apply { setBackgroundColor(ui.surface) }
    private val loading = android.widget.ProgressBar(activity)
    private val indicators = FrameLayout(activity)
    private var dots: ScreenshotDots? = null
    private var videos = emptyList<YouTubeVideo>()
    private val attached = linkedSetOf<Holder>()
    private val players = linkedSetOf<Holder>()
    private var retainingLayout = false
    private var started = false
    private var disposed = false
    private var paused = false
    private var fullscreen: Dialog? = null
    private var fullscreenWeb: WebView? = null
    val isFullscreen get() = fullscreen != null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private val scrollListener = ViewTreeObserver.OnScrollChangedListener { loadVisible() }
    private val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var decidingDirection = false

    private class Holder(val frame: FrameLayout) : RecyclerView.ViewHolder(frame) {
        var video: YouTubeVideo? = null
        var web: WebView? = null
    }

    private val pages = object : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = videos.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(object : FrameLayout(activity) {
            // Keep horizontal gestures available to the pager, including over the embedded player.
            override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) = Unit
        }.apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = RecyclerView.LayoutParams(-1, -1)
        })
        override fun onBindViewHolder(holder: Holder, index: Int) {
            if (holder.video != videos[index]) destroyPlayer(holder)
            holder.video = videos[index]
            holder.frame.contentDescription = "YouTube video ${index + 1} of ${videos.size} for $game"
            if (holder in attached) loadVisible()
        }
        override fun onViewAttachedToWindow(holder: Holder) { attached.add(holder); loadVisible() }
        override fun onViewDetachedFromWindow(holder: Holder) {
            attached.remove(holder)
            if (!retainingLayout && holder.web !== fullscreenWeb) destroyPlayer(holder)
        }
        override fun onViewRecycled(holder: Holder) {
            attached.remove(holder)
            if (!retainingLayout && holder.web !== fullscreenWeb) { destroyPlayer(holder); holder.video = null }
        }
    }

    init {
        orientation = VERTICAL
        ui.gap(this, 20)
        val heading = ui.row()
        heading.addView(ui.text("YouTube videos", 18, medium = true), LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(ui.iconButton(R.drawable.ic_settings, "YouTube searches", settings))
        ui.add(this, heading)
        pager.adapter = pages
        media.addView(pager, FrameLayout.LayoutParams(-1, -1))
        media.addView(loading, FrameLayout.LayoutParams(ui.dp(32), ui.dp(32), Gravity.CENTER))
        ui.add(this, media, 200)
        ui.add(this, indicators, 36)
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(index: Int) {
                dots?.selected = index
                attached.filter { it.bindingAdapterPosition != index }.forEach { pausePlayer(it.web) }
                loadVisible()
            }
            override fun onPageScrollStateChanged(state: Int) {
                if (state == ViewPager2.SCROLL_STATE_DRAGGING) attached.forEach { pausePlayer(it.web) }
            }
        })
    }

    private fun loadVisible() {
        if (disposed || !isShown || !media.getGlobalVisibleRect(Rect())) return
        if (!started) {
            started = true
            scope.launch {
                videos = coroutineScope {
                    queries.map { query -> async {
                        try { requests.withPermit { search.find(query, game) } }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { null }
                    } }.awaitAll().filterNotNull()
                }
                loading.visibility = GONE
                if (videos.isEmpty()) { visibility = GONE; return@launch }
                dots = ScreenshotDots(activity, videos.size, "YouTube video", ui.blue, drawShadow = false).also {
                    indicators.addView(it, FrameLayout.LayoutParams(-1, -1)); it.selected = 0
                }
                pages.notifyDataSetChanged()
            }
        }
        attached.filter { it.web == null }.forEach { holder ->
            holder.video?.let { holder.web = createPlayer(holder.frame, it).also { web -> if (paused) web.onPause() } }
            if (holder.web != null) players.add(holder)
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = event.x; touchStartY = event.y
                decidingDirection = event.x >= media.left && event.x < media.right &&
                    event.y >= media.top && event.y < media.bottom
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

    @SuppressLint("SetJavaScriptEnabled")
    private fun createPlayer(frame: FrameLayout, video: YouTubeVideo): WebView {
        val origin = "https://${activity.packageName}"
        val view = WebView(activity).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return false
                    val url = request.url
                    if (url.scheme in listOf("https", "http")) open(url.toString())
                    return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    fullscreenWeb = this@apply
                    showFullscreen(view, callback)
                }
                override fun onHideCustomView() { fullscreen?.dismiss() }
            }
        }
        frame.addView(view, FrameLayout.LayoutParams(-1, -1))
        // YouTube requires the embedding app's identity as the HTTPS Referer.
        view.loadDataWithBaseURL("$origin/", """<!doctype html><html><head>
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <meta name="referrer" content="strict-origin-when-cross-origin">
            <style>html,body,iframe{margin:0;width:100%;height:100%;border:0;background:#000;overflow:hidden}</style>
            </head><body><iframe id="video" src="https://www.youtube.com/embed/${video.id}?enablejsapi=1&amp;playsinline=1&amp;origin=$origin"
            allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; fullscreen" allowfullscreen></iframe>
            </body></html>""".trimIndent(), "text/html", "UTF-8", null)
        return view
    }

    private fun showFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (fullscreen != null) { callback.onCustomViewHidden(); return }
        fullscreenCallback = callback
        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        fullscreen = dialog
        val frame = FrameLayout(activity).apply {
            setBackgroundColor(Color.BLACK)
            addView(view, FrameLayout.LayoutParams(-1, -1))
            val close = ui.iconButton(R.drawable.ic_x, "Exit fullscreen video") { dialog.dismiss() }.apply {
                (getChildAt(0) as android.widget.ImageView).imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                background = ui.shape(0x66000000, 24)
            }
            addView(close, FrameLayout.LayoutParams(ui.dp(48), ui.dp(48), Gravity.TOP or Gravity.END))
        }
        dialog.setContentView(frame)
        dialog.setOnDismissListener {
            frame.removeAllViews()
            fullscreen = null
            fullscreenWeb = null
            val saved = fullscreenCallback; fullscreenCallback = null
            saved?.onCustomViewHidden()
            if (!disposed) fullscreenClosed()
        }
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        dialog.show()
        dialog.window?.let { window ->
            window.setLayout(-1, -1)
            WindowCompat.getInsetsController(window, frame).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    private fun pausePlayer(web: WebView?) {
        web?.evaluateJavascript("document.getElementById('video').contentWindow.postMessage(JSON.stringify({event:'command',func:'pauseVideo',args:[]}), 'https://www.youtube.com');", null)
    }

    fun pause() {
        paused = true
        fullscreen?.dismiss()
        attached.forEach { pausePlayer(it.web); it.web?.onPause() }
    }

    fun resume() { paused = false; attached.forEach { it.web?.onResume() } }

    private fun destroyPlayer(holder: Holder) {
        if (holder.web == null) return
        if (holder.web === fullscreenWeb) fullscreen?.dismiss()
        holder.web?.let { view ->
            holder.frame.removeView(view)
            view.stopLoading(); view.loadUrl("about:blank"); view.destroy()
        }
        holder.web = null
        players.remove(holder)
    }

    fun preserveForLayoutChange(change: () -> Unit) {
        retainingLayout = true
        try { change() } finally {
            post {
                retainingLayout = false
                players.filter { it !in attached }.forEach(::destroyPlayer)
                requestLayout()
            }
        }
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        scope.cancel(); fullscreen?.dismiss()
        players.toList().forEach(::destroyPlayer); attached.clear()
        pager.adapter = null
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        media.layoutParams.height = minOf(width * 9 / 16, ui.dp(resources.configuration.screenHeightDp * 3 / 5)).coerceAtLeast(ui.dp(100))
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnScrollChangedListener(scrollListener)
        post { loadVisible() }
    }

    override fun onDetachedFromWindow() {
        if (viewTreeObserver.isAlive) viewTreeObserver.removeOnScrollChangedListener(scrollListener)
        if (!retainingLayout) dispose()
        super.onDetachedFromWindow()
    }
}
