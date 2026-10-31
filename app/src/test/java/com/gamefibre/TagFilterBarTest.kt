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
        assertEquals(1, bar.childCount)
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
    @Test fun discoveryTagsStayVisibleAfterClearingLastSelection() {
        val context = RuntimeEnvironment.getApplication()
        val bar = TagFilterBar(context, alwaysVisible = true)
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
