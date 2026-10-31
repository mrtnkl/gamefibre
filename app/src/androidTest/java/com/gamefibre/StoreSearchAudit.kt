package com.gamefibre

import android.app.Activity
import android.app.Instrumentation
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Focused real-device search, pagination, prepared images, native scrolling and Back navigation. */
internal object StoreSearchAudit {
    fun run(instrumentation: Instrumentation, activity: Activity): String {
        fun <T> ui(action: () -> T): T {
            var result: Result<T>? = null
            instrumentation.runOnMainSync { result = runCatching(action) }
            return result!!.getOrThrow()
        }
        fun field(value: Any, name: String): Any? = value.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(value)
        fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
        fun waitFor(label: String, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 60_000
            while (SystemClock.elapsedRealtime() < deadline) {
                if (ui(condition)) {
                    instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stage", label) })
                    return
                }
                SystemClock.sleep(150)
            }
            error("Timed out: $label")
        }
        fun drag(x: Float, y: Float, dx: Float, dy: Float) {
            val start = SystemClock.uptimeMillis()
            fun event(action: Int, f: Float) {
                val motion = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x + dx * f, y + dy * f, 0)
                motion.source = InputDevice.SOURCE_TOUCHSCREEN
                try { instrumentation.sendPointerSync(motion) } finally { motion.recycle() }
            }
            event(MotionEvent.ACTION_DOWN, 0f)
            repeat(12) { SystemClock.sleep(16); event(MotionEvent.ACTION_MOVE, (it + 1) / 12f) }
            event(MotionEvent.ACTION_UP, 1f)
            SystemClock.sleep(500)
        }
        ui { activity.javaClass.getDeclaredMethod("selectTab", String::class.java).apply { isAccessible = true }.invoke(activity, "store") }
        lateinit var search: StoreSearchView
        waitFor("catalog page") {
            search = field(activity, "storeSearchView") as StoreSearchView
            field(search, "result") != null
        }
        val recycler = ui { field(search, "recycler") as RecyclerView }
        val page = ui { field(search, "result") as CatalogPage }
        check(page.games.size == 30 && page.total == 144498 && page.page == 0)
        check(page.games.zipWithNext().all { (a, b) -> a.metascore >= b.metascore })
        try {
            waitFor("decoded results") { (recycler.adapter?.itemCount ?: 0) >= 12 && recycler.childCount > 0 && recycler.isShown }
        } catch (error: Exception) {
            val diagnostic = ui { "items=${recycler.adapter?.itemCount} children=${recycler.childCount} shown=${recycler.isShown} size=${recycler.width}x${recycler.height} failures=${field(search, "failures")} focus=${activity.hasWindowFocus()}" }
            throw IllegalStateException(diagnostic, error)
        }
        ui {
            val header = field(activity, "header") as ViewGroup
            val count = field(activity, "searchGameCount") as TextView
            check((count.parent as ViewGroup).let(::views).any { it is android.widget.EditText }) { "Result count is outside the shared search bar" }
            check(count.text.toString() == "144498")
            val pills = field(activity, "listsRow") as View
            val searchBounds = android.graphics.Rect(); val pillBounds = android.graphics.Rect()
            check(header.getGlobalVisibleRect(searchBounds) && pills.getGlobalVisibleRect(pillBounds))
            check(searchBounds.top < pillBounds.top) { "Search must sit above the list pills" }
            val root = field(activity, "root") as View
            check(views(root).filterIsInstance<TextView>().none { it.text.contains("offline snapshot", true) || it.text.startsWith("Sort:") })
            check(header.findViewWithTag<View>("collection-options") != null)
        }
        ui {
            val root = field(activity, "root") as View
            val bitmap = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
            root.draw(android.graphics.Canvas(bitmap))
            java.io.File(activity.filesDir, "store-search-audit.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        val tagStrip = ui { field(search.tagBar, "chips") as RecyclerView }
        ui {
            check(views(tagStrip).filterIsInstance<android.widget.CheckBox>().all { !it.text.contains(" · ") })
        }
        fun panTagGap(bottomInset: Boolean) {
            ui { tagStrip.stopScroll(); tagStrip.scrollToPosition(0) }
            instrumentation.waitForIdleSync()
            val bounds = ui { android.graphics.Rect().also { check(tagStrip.getGlobalVisibleRect(it)) } }
            val start = ui {
                if (bottomInset) (bounds.right - 16f) to (bounds.bottom - 2f)
                else {
                    val child = tagStrip.getChildAt(0)
                    val xy = IntArray(2); child.getLocationOnScreen(xy)
                    val margin = (child.layoutParams as RecyclerView.LayoutParams).marginEnd
                    check(margin > 0)
                    (xy[0] + child.width + margin / 2f) to bounds.centerY().toFloat()
                }
            }
            val before = ui { tagStrip.computeHorizontalScrollOffset() }
            drag(start.first, start.second, -(start.first - bounds.left - 12f), 0f)
            check(ui { tagStrip.computeHorizontalScrollOffset() } > before) {
                "Tag strip did not pan from ${if (bottomInset) "the blank bottom inset" else "between pills"}"
            }
        }
        panTagGap(false); panTagGap(true)
        ui { tagStrip.stopScroll(); tagStrip.scrollToPosition(0) }
        ui {
            val row = recycler.getChildAt(0)
            val holder = recycler.getChildViewHolder(row)
            val card = field(holder, "views") as GameCardViews
            check((card.art.parent as View).clipToOutline) { "Store row lost library artwork rounding" }
            check(card.metric.isShown && card.metric.text.isNotEmpty()) { "Metascore is missing on the right" }
            check(card.subtitle.text.toString() == "Steam")
        }
        var frames = 0
        var bad = 0
        val observer = ViewTreeObserver.OnPreDrawListener {
            frames++
            views(recycler).filterIsInstance<ImageView>().filter { it.isShown }.forEach { image ->
                val bitmap = (image.drawable as? BitmapDrawable)?.bitmap
                if (bitmap == null || bitmap.width <= 3 || bitmap.height <= 5 || image.alpha < .99f) bad++
            }
            true
        }
        ui { recycler.viewTreeObserver.addOnPreDrawListener(observer) }
        fun swipe(dx: Float, dy: Float) {
            val bounds = android.graphics.Rect()
            ui { check(recycler.getGlobalVisibleRect(bounds)) }
            val x = bounds.left + bounds.width() * .5f
            val y = bounds.top + bounds.height() * .75f
            val safeDx = dx.coerceIn(-bounds.width() * .4f, bounds.width() * .4f)
            val safeDy = dy.coerceIn(-bounds.height() * .55f, bounds.height() * .2f)
            drag(x, y, safeDx, safeDy)
        }
        repeat(3) { swipe(5f, -ui { recycler.height * .55f }) }
        val offset = ui { recycler.computeVerticalScrollOffset() }
        check(offset > 0) { "Vertical gesture did not scroll the results" }
        check(ui { (field(search, "result") as CatalogPage).page } == 0) { "Vertical drag changed pages" }
        swipe(-ui { recycler.width * .4f }, 3f)
        check(ui { (field(search, "result") as CatalogPage).page } == 0) { "Horizontal drag changed logical pages" }
        check(ui { field(activity, "tab") } == "store") { "Horizontal drag opened a game instead of staying in the list" }
        ui { recycler.viewTreeObserver.removeOnPreDrawListener(observer) }
        check(frames > 0 && bad == 0) { "Unprepared image frames: $bad / $frames" }
        ui { (field(search, "next") as View).performClick() }
        waitFor("next page") { (field(search, "result") as CatalogPage).page == 1 }
        val second = ui { field(search, "result") as CatalogPage }
        check(second.games.size == 30 && second.games.none { game -> page.games.any { it.appId == game.appId } })
        ui { (field(search, "previous") as View).performClick() }
        waitFor("previous page") { (field(search, "result") as CatalogPage).page == 0 && (recycler.adapter?.itemCount ?: 0) > 0 }
        ui { recycler.scrollToPosition(0) }
        instrumentation.waitForIdleSync()
        ui { recycler.getChildAt(0).performClick() }
        waitFor("game details") { field(activity, "tab") == "game" }
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        ui { activity.recreate() }
        val recreated = instrumentation.waitForMonitorWithTimeout(monitor, 10_000) ?: error("Activity did not recreate")
        instrumentation.removeMonitor(monitor)
        waitFor("game restored after recreation") { field(recreated, "tab") == "game" && field(recreated, "selectedEntry") != null && recreated.hasWindowFocus() && (recreated as MainActivity).lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) }
        ui { check(field(recreated, "gameReturnTab") == "store") }
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        waitFor("search restored") { field(recreated, "tab") == "store" }
        waitFor("restored search page") {
            search = field(recreated, "storeSearchView") as StoreSearchView
            field(search, "result") != null
        }
        check(ui { (field(search, "result") as CatalogPage).page } == 0)
        waitFor("restored decoded results") { (field(search, "recycler") as RecyclerView).childCount > 0 }
        ui { search.setQuery("Half-Life") }
        waitFor("name filter") {
            (field(search, "result") as CatalogPage).games.all { it.name.contains("Half-Life", true) }
        }
        for (sort in listOf(CatalogSort.USER_SCORE, CatalogSort.REVIEWS, CatalogSort.RELEASE)) {
            val before = ui { field(search, "result") }
            ui {
                recreated.javaClass.getDeclaredMethod("showSortPicker").apply { isAccessible = true }.invoke(recreated)
                val dialog = field(recreated, "downloadDialog") as ContentDialog
                val choices = views(dialog.content).filterIsInstance<android.widget.RadioButton>()
                check(choices.map { it.text.toString() }.containsAll(listOf("Metascore", "Release date", "User rating", "Review count", "Name", "Ascending", "Descending")))
                choices.first { it.text.toString() == sort.label }.performClick()
                val apply = views(dialog.window!!.decorView).filterIsInstance<TextView>().first { it.text.toString() == "Apply" }
                (apply.parent as View).performClick()
            }
            waitFor("${sort.label} sort and right-hand value") {
                if (field(search, "result") === before) false else {
                    val list = field(search, "recycler") as RecyclerView
                    val row = list.getChildAt(0)
                    if (row == null) false else {
                        val holder = list.getChildViewHolder(row)
                        val card = field(holder, "views") as GameCardViews
                        val correct = card.metric.isShown && card.metric.tooltipText?.startsWith(sort.label) == true
                        if (correct) check(field(recreated, "tab") == "store")
                        correct
                    }
                }
            }
        }
        instrumentation.waitForIdleSync()
        ui {
            val root = field(recreated, "root") as View
            val bitmap = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
            root.draw(android.graphics.Canvas(bitmap))
            java.io.File(recreated.filesDir, "store-search-audit.png").outputStream().use {
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        }
        return "PASS: tag-gap and inset panning, shared library rows and right-hand metrics, shared header, inline count and overflow, user-rating/review-count/release sort Apply actions, 144498 catalog entries, Metascore default, 30-result disjoint pages, decoded images across $frames frames, vertical offset $offset, touch direction preserved, game recreation and Back restore search"
    }
}
