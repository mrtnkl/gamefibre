package com.gamefibre

import android.view.ViewGroup
import android.widget.CheckBox
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = App::class)
class StoreSearchStartupTest {
    @Test fun realTagNamesAndSelectionsAreAvailableBeforeAnyCatalogOrSearchLoads() {
        val context = RuntimeEnvironment.getApplication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val view = StoreSearchView(context, NativeUi(context), scope, open = {}, chooseArtwork = {})
        try {
            val strip = view.tagBar.getChildAt(0) as RecyclerView
            @Suppress("UNCHECKED_CAST")
            val adapter = strip.adapter as RecyclerView.Adapter<RecyclerView.ViewHolder>
            val labels = context.resources.getStringArray(R.array.steam_catalog_tag_names)
            assertTrue(labels.isNotEmpty())
            assertEquals(labels.size, adapter.itemCount)
            val holder = adapter.createViewHolder(strip, 0)
            adapter.bindViewHolder(holder, 0)
            val pill = (holder.itemView as ViewGroup).getChildAt(0) as CheckBox
            assertEquals(labels.first(), pill.text.toString())
            pill.performClick()
            assertEquals(listOf(labels.first()), view.snapshot().getStringArrayList("tags"))
            assertEquals(labels.size, adapter.itemCount)
            assertEquals(0, view.pendingCount)
        } finally { view.close(); scope.cancel() }
    }
}
