package com.gamefibre

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TagFilterBarTest {
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    @Test fun pillsApplyImmediatelyUseSharedCheckIconAndHideWhenLastIsRemoved() {
        val context = RuntimeEnvironment.getApplication()
        var filters = TagFilters()
        var applications = 0
        val bar = TagFilterBar(context) { filters = it; applications++ }
        val facets = LibraryTagSearch(listOf(TagSearchGame("a", "Game", listOf("RPG", "Space"))))
            .facets("", TagFilters())
        bar.update(facets, TagFilters())
        assertEquals(2, bar.childCount)
        val recycler = views(bar).filterIsInstance<RecyclerView>().single()
        @Suppress("UNCHECKED_CAST")
        val adapter = recycler.adapter as RecyclerView.Adapter<RecyclerView.ViewHolder>
        fun tap(position: Int): android.widget.CheckBox {
            val holder = adapter.createViewHolder(recycler, 0)
            adapter.bindViewHolder(holder, position)
            val pill = (holder.itemView as ViewGroup).getChildAt(0) as android.widget.CheckBox
            pill.performClick()
            return pill
        }
        val first = tap(0)
        assertTrue(first.isChecked)
        assertNotNull(first.compoundDrawablesRelative[0])
        assertFalse(first.text.contains("✓"))
        assertEquals(1, applications)
        tap(1)
        assertEquals(setOf("RPG", "Space"), filters.included)
        assertEquals(2, applications)
        tap(0)
        assertTrue(filters.active)
        assertEquals(View.VISIBLE, bar.visibility)
        tap(1)
        assertFalse(filters.active)
        assertEquals(4, applications)
        assertEquals(View.GONE, bar.visibility)
    }
    @Test fun clearButtonRemovesIncludedAndExcludedTagsAndClosesTheLibraryBar() {
        val context = RuntimeEnvironment.getApplication()
        val ui = NativeUi(context)
        var filters = TagFilters(included = setOf("RPG"), excluded = setOf("Space"))
        var applications = 0
        val search = LibraryTagSearch(listOf(
            TagSearchGame("a", "Game A", listOf("RPG")),
            TagSearchGame("b", "Game B", listOf("Space")),
            TagSearchGame("c", "Game C", emptyList())))
        val bar = TagFilterBar(context, ui) { filters = it; applications++ }
        bar.update(search.facets("", filters), filters)
        val clear = views(bar).single { it.contentDescription == "Clear tag filters" }
        assertTrue(clear.isFocusable)
        assertEquals("Clear tag filters", clear.tooltipText)
        assertEquals("android.widget.Button", clear.createAccessibilityNodeInfo().className)

        bar.measure(View.MeasureSpec.makeMeasureSpec(ui.dp(320), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(ui.dp(56), View.MeasureSpec.EXACTLY))
        bar.layout(0, 0, bar.measuredWidth, bar.measuredHeight)
        val recycler = views(bar).filterIsInstance<RecyclerView>().single()
        assertEquals(ui.dp(48), clear.width)
        assertEquals(ui.dp(48), clear.height)
        assertEquals(bar.width, clear.right)
        assertEquals(clear.left, recycler.right)

        clear.performClick()
        assertEquals(TagFilters(), filters)
        assertEquals(1, applications)
        assertEquals(setOf("a", "b", "c"), search.keys("", filters))
        assertEquals(View.GONE, bar.visibility)
    }

    @Test fun discoveryTagsStayVisibleAfterClearingLastSelection() {
        val context = RuntimeEnvironment.getApplication()
        val bar = TagFilterBar(context, alwaysVisible = true)
        assertEquals(1, bar.childCount)
        assertTrue(views(bar).none { it.contentDescription == "Clear tag filters" })
        val selection = TagFilters(setOf("RPG"))
        val facets = LibraryTagSearch(listOf(TagSearchGame("a", "Game", listOf("RPG")))).facets("", selection)
        bar.update(facets, selection)
        val recycler = views(bar).filterIsInstance<RecyclerView>().single()
        @Suppress("UNCHECKED_CAST")
        val adapter = recycler.adapter as RecyclerView.Adapter<RecyclerView.ViewHolder>
        val holder = adapter.createViewHolder(recycler, 0)
        adapter.bindViewHolder(holder, 0)
        ((holder.itemView as ViewGroup).getChildAt(0) as android.widget.CheckBox).performClick()
        assertEquals(View.VISIBLE, bar.visibility)
    }
    @Test fun storePillsOmitCountsWhileLibraryPillsRetainThem() {
        val context = RuntimeEnvironment.getApplication()
        val facets = LibraryTagSearch(listOf(TagSearchGame("a", "Game", listOf("RPG")))).facets("", TagFilters())
        fun label(showCounts: Boolean): android.widget.CheckBox {
            val bar = TagFilterBar(context, showCounts = showCounts)
            bar.update(facets, TagFilters())
            val recycler = views(bar).filterIsInstance<RecyclerView>().single()
            @Suppress("UNCHECKED_CAST")
            val adapter = recycler.adapter as RecyclerView.Adapter<RecyclerView.ViewHolder>
            val holder = adapter.createViewHolder(recycler, 0)
            adapter.bindViewHolder(holder, 0)
            return (holder.itemView as ViewGroup).getChildAt(0) as android.widget.CheckBox
        }
        assertEquals("RPG", label(false).text.toString())
        assertFalse(label(false).contentDescription.contains("games"))
        assertEquals("RPG · 1", label(true).text.toString())
        assertTrue(label(true).contentDescription.contains("1 games"))
    }
}
