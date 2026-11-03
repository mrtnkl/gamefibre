package com.gamefibre

import android.app.Activity
import android.app.Dialog
import android.app.Instrumentation
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.gamefibre.steam.SteamEngine
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/** Exercises the normal ZIP progress observer while sending real navigation and scroll gestures. */
internal object DownloadUiAudit {
    fun run(instrumentation: Instrumentation, activity: Activity): String {
        fun field(target: Any, name: String) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
        fun <T> ui(block: () -> T): T {
            var result: Result<T>? = null
            instrumentation.runOnMainSync { result = runCatching(block) }
            return result!!.getOrThrow()
        }
        fun await(message: String, block: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + 30_000
            while (!ui(block)) { check(SystemClock.elapsedRealtime() < end) { message }; SystemClock.sleep(50) }
        }
        fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
        fun gesture(rect: Rect, horizontal: Boolean, forward: Boolean = true) {
            val down = SystemClock.uptimeMillis()
            val fromX = rect.left + rect.width() * if (horizontal && forward) .8f else .3f
            val fromY = rect.top + rect.height() * if (horizontal) .45f else .8f
            val toX = if (horizontal) rect.left + rect.width() * if (forward) .2f else .8f else fromX + rect.width() * .1f
            val toY = if (horizontal) fromY else rect.top + rect.height() * .2f
            fun event(action: Int, fraction: Float) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                    fromX + (toX - fromX) * fraction, fromY + (toY - fromY) * fraction, 0)
                instrumentation.sendPointerSync(event); event.recycle()
            }
            event(MotionEvent.ACTION_DOWN, 0f)
            repeat(20) { SystemClock.sleep(12); event(MotionEvent.ACTION_MOVE, (it + 1) / 20f) }
            event(MotionEvent.ACTION_UP, 1f)
        }
        val engine = SteamEngine.get(activity)
        val collection = field(activity, "collection") as CollectionData
        val originalList = collection.list
        val originalTransfer = engine.snapshot().optJSONObject("transfer")
        check(originalTransfer?.optString("phase") !in listOf("preparing", "downloading", "exporting")) { "Pause the real download before running the audit" }
        await("No prepared Steam game") { com.gamefibre.assets.AssetRepository.get(activity).library.value.cards.values.any { it.game.store == LibraryStore.STEAM } }
        val game = ui { com.gamefibre.assets.AssetRepository.get(activity).library.value.cards.values.first { it.game.store == LibraryStore.STEAM }.game }
        val update = engine.javaClass.getDeclaredMethod("update", Boolean::class.javaPrimitiveType, kotlin.jvm.functions.Function1::class.java).apply { isAccessible = true }
        fun publish(progress: Double) {
            val transfer = JSONObject().put("appId", game.id.toInt()).put("name", game.name).put("format", "zip")
                .put("phase", "downloading").put("message", "Downloading and verifying game files…")
                .put("depotPercent", progress).put("canPause", true)
            update.invoke(engine, true, { state: JSONObject -> state.put("transfer", transfer); Unit })
        }
        val running = AtomicBoolean(false)
        var producer: Thread? = null
        try {
            publish(.1)
            ui {
                collection.list = "Downloads"
                activity.javaClass.getDeclaredMethod("selectTab", String::class.java).apply { isAccessible = true }.invoke(activity, "library")
            }
            await("Downloads card did not become ready") {
                val list = field(activity, "libraryRecycler") as? RecyclerView
                collection.list == "Downloads" && list?.isShown == true && list.childCount > 0
            }
            val list = ui { field(activity, "libraryRecycler") as RecyclerView }
            val screen = ui { field(activity, "retainedLibrary")!! }
            val pager = field(screen, "pager") as ViewPager2
            val originalHeight = ui { list.layoutParams.height }
            val initialPage = ui { pager.currentItem }
            running.set(true)
            producer = Thread {
                var sequence = 0
                while (running.get()) { publish((sequence++ % 80 + 10) / 100.0); SystemClock.sleep(4) }
            }.apply { start() }
            var worstResponse = 0L
            repeat(12) {
                val started = SystemClock.elapsedRealtime()
                ui { check((field(activity, "state") as JSONObject).optJSONObject("transfer")?.optString("format") == "zip") }
                worstResponse = maxOf(worstResponse, SystemClock.elapsedRealtime() - started)
                SystemClock.sleep(25)
            }
            check(worstResponse < 1000) { "Progress starved input: ${worstResponse}ms" }
            // Make even a single download scrollable, retaining the actual card and native layout manager.
            ui {
                (field(activity, "appBar") as com.google.android.material.appbar.AppBarLayout).setExpanded(false, false)
                list.layoutParams = list.layoutParams.apply { height = (160 * activity.resources.displayMetrics.density).toInt() }
            }
            SystemClock.sleep(250)
            check(ui { list.canScrollVertically(1) }) { "Downloads fixture cannot scroll" }
            val before = ui { list.computeVerticalScrollOffset() }
            gesture(ui { Rect().also { check(list.getGlobalVisibleRect(it)) } }, false)
            SystemClock.sleep(300)
            check(ui { list.computeVerticalScrollOffset() } > before) {
                "Vertical gesture did not advance downloads: before=$before after=${ui { list.computeVerticalScrollOffset() }} top=${ui { (field(activity, "appBar") as View).top }}"
            }
            check(ui { pager.currentItem } == initialPage) { "Vertical download gesture changed pages" }
            ui { list.layoutParams = list.layoutParams.apply { height = originalHeight }; list.scrollToPosition(0) }
            SystemClock.sleep(250)
            ui {
                val root = activity.window.decorView
                val menu = views(root).first { it.isShown && it.contentDescription == "Menu" }
                check(menu.performClick())
                check((field(activity, "collectionMenu") as Dialog).isShowing)
                (field(activity, "collectionMenu") as Dialog).dismiss()
            }
            gesture(ui { Rect().also { check(pager.getGlobalVisibleRect(it)) } }, true, initialPage < ui { pager.adapter!!.itemCount - 1 })
            await("Horizontal swipe did not switch lists") { pager.currentItem != initialPage }
            ui {
                screen.javaClass.getDeclaredMethod("navigate", Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(screen, initialPage)
            }
            await("Could not return to Downloads") { collection.list == "Downloads" && list.isShown }
            running.set(false); producer.join(5000)
            publish(.75)
            await("Latest progress did not reach the card") {
                views(list).filterIsInstance<TextView>().any { it.text.contains("75%") } &&
                    views(list).filterIsInstance<ProgressBar>().any { it.progress == 750 }
            }
            ui {
                for (index in 0 until list.childCount) {
                    val holder = list.getChildViewHolder(list.getChildAt(index))
                    val art = field(holder, "art") as ImageView
                    val bitmap = (art.drawable as? BitmapDrawable)?.bitmap
                    check(bitmap != null && bitmap.width > 3 && bitmap.height > 5 && art.alpha == 1f) { "Download card lost prepared artwork" }
                }
            }
            return "PASS: ZIP progress updates, menu, list swipe, native vertical scroll without pager drift, prepared artwork; worst UI response ${worstResponse}ms"
        } finally {
            running.set(false); producer?.join(5000)
            update.invoke(engine, true, { state: JSONObject -> if (originalTransfer == null) state.remove("transfer") else state.put("transfer", originalTransfer); Unit })
            ui { collection.list = originalList }
        }
    }
}
