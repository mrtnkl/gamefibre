package com.gamefibre

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking

/** Focused native tag controls, direction/scroll checks, and one public Steam enrichment request. */
internal object TagFiltersAudit {
    fun run(instrumentation: Instrumentation, activity: Activity): String {
        fun <T> ui(action: () -> T): T {
            var result: Result<T>? = null
            instrumentation.runOnMainSync { result = runCatching(action) }
            return result!!.getOrThrow()
        }
        fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
        fun swipe(x1: Float, y1: Float, x2: Float, y2: Float) {
            val start = SystemClock.uptimeMillis()
            fun event(action: Int, fraction: Float) {
                val input = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action,
                    x1 + (x2 - x1) * fraction, y1 + (y2 - y1) * fraction, 0)
                input.source = InputDevice.SOURCE_TOUCHSCREEN
                try { instrumentation.sendPointerSync(input) } finally { input.recycle() }
            }
            event(MotionEvent.ACTION_DOWN, 0f)
            repeat(12) { SystemClock.sleep(16); event(MotionEvent.ACTION_MOVE, (it + 1) / 12f) }
            event(MotionEvent.ACTION_UP, 1f)
            instrumentation.waitForIdleSync()
            SystemClock.sleep(300)
        }

        val ui = ui { NativeUi(activity) }
        val sample = listOf(
            TagSearchGame("a", "Space Story", listOf("Space", "RPG", "First-Person") + (1..40).map { "Tag $it" }),
            TagSearchGame("b", "Space Duel", listOf("Space", "RPG", "Multiplayer")),
            TagSearchGame("c", "Earth", listOf("RPG", "First-Person")),
            TagSearchGame("d", "Unknown", emptyList()),
        )
        val search = LibraryTagSearch(sample)
        var filters = TagFilters()
        var selectedSearch: Set<String>? = null
        val selected = mutableSetOf<String>()
        lateinit var bar: TagFilterBar
        lateinit var pane: LinearLayout
        val dialog = ui {
            ContentDialog(activity, ui, "Tag controls audit").apply {
                bar = TagFilterBar(activity, ui) { next ->
                    filters = next
                    if (next.active) bar.update(search.facets("", filters), filters)
                }
                bar.update(search.facets("", filters), filters)
                ui.add(content, bar)
                pane = gameTagsPane(ui, (listOf("RPG", "Space") + (1..60).map { "Game tag $it" }), selected, false) {
                    selectedSearch = it
                }
                ui.add(content, pane)
                show()
            }
        }
        try {
            instrumentation.waitForIdleSync()
            fun pill(tag: String) = views(bar).first { it.contentDescription?.startsWith("$tag,") == true }
            ui { pill("RPG").performClick() }
            instrumentation.waitForIdleSync()
            check(search.keys("", filters).size == 3)
            ui { views(bar).filterIsInstance<RecyclerView>().single().scrollToPosition(
                search.facets("", filters).indexOfFirst { it.name == "Multiplayer" }) }
            instrumentation.waitForIdleSync()
            ui { pill("Multiplayer").performClick() }
            instrumentation.waitForIdleSync()
            check(search.keys("", filters).size == 1)
            ui { views(bar).filterIsInstance<RecyclerView>().single().scrollToPosition(0) }
            instrumentation.waitForIdleSync()
            ui { pill("Multiplayer").performClick() }
            instrumentation.waitForIdleSync()
            ui { pill("RPG").performClick() }
            check(!filters.active)
            check(ui { bar.visibility } == View.GONE)
            ui { bar.update(search.facets("", filters), filters) }
            val chips = ui { views(bar).filterIsInstance<RecyclerView>().single() }
            ui { chips.scrollToPosition(0) }
            instrumentation.waitForIdleSync()
            val bounds = ui { Rect().also { check(chips.getGlobalVisibleRect(it)) } }
            val beforeX = ui { chips.computeHorizontalScrollOffset() }
            swipe(bounds.right - 12f, bounds.centerY().toFloat(), bounds.left + 12f, bounds.centerY().toFloat())
            check(ui { chips.computeHorizontalScrollOffset() } > beforeX) { "Tag bar did not scroll horizontally" }
            ui { chips.stopScroll() }
            val scroll = ui { dialog.content.parent as ScrollView }
            val scrollBounds = ui { Rect().also { check(scroll.getGlobalVisibleRect(it)) } }
            val afterX = ui { chips.computeHorizontalScrollOffset() }
            swipe(scrollBounds.centerX().toFloat(), scrollBounds.bottom - 20f,
                scrollBounds.centerX().toFloat(), scrollBounds.top + 30f)
            check(ui { scroll.scrollY } > 0) { "Vertical touch did not scroll content" }
            check(ui { chips.computeHorizontalScrollOffset() } == afterX) { "Vertical touch changed horizontal tag position" }
            ui {
                val pills = views(pane).filterIsInstance<CheckBox>()
                pills[0].performClick(); pills[1].performClick()
                check(selectedSearch == null) { "Tag selection navigated before Search" }
                views(pane).first { it.contentDescription == "Search games with all selected tags" }.performClick()
                check(selectedSearch == setOf("RPG", "Space"))
            }
        } finally { ui { dialog.dismiss() } }

        val directory = File(activity.cacheDir, "tag-network-audit-${UUID.randomUUID()}")
        try {
            val live = LiveSteamTags(directory)
            runBlocking { live.load(); check(live.refresh(620)) { "Public Steam tag enrichment failed" } }
            check("First-Person" in live.tags(620).orEmpty())
            val restored = LiveSteamTags(directory)
            runBlocking { restored.load() }
            check(restored.tags(620) == live.tags(620))
        } finally { directory.deleteRecursively() }
        return "PASS: immediate library toggles, last-tag dismissal, multi-select then Search, horizontal progress, vertical direction, live Steam fetch and disk reload"
    }
}
