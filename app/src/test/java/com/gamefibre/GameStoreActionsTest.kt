package com.gamefibre

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPopupMenu

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = App::class)
class GameStoreActionsTest {
    @Test fun everyDownloadStoreOffersOpenDownloadsAndDownloadAgainAfterExport() {
        val lifecycle = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = lifecycle.get()
        try {
            for (store in listOf(LibraryStore.STEAM, LibraryStore.GOG, LibraryStore.EPIC)) {
                val game = LibraryEntry(store, "game", "Game")
                var exported = false
                var downloaded: LibraryEntry? = null
                var redownloaded: LibraryEntry? = null
                val actions = GameStoreActions(activity, NativeUi(activity), listOf(game), { game }, {},
                    { true }, { exported }, { downloaded = it }, redownload = { redownloaded = it })
                val column = actions.getChildAt(0) as android.widget.LinearLayout
                assertEquals(android.widget.LinearLayout.VERTICAL, column.orientation)
                assertEquals(listOf("Download from ${store.label}"), texts(actions))
                column.getChildAt(0).performClick()
                assertEquals(game, downloaded)
                exported = true; actions.update()
                assertEquals(listOf("Open Downloads", "Download again"), texts(actions))
                val pair = column.getChildAt(1) as ViewGroup
                downloaded = null
                pair.getChildAt(0).performClick()
                assertEquals(game, downloaded)
                pair.getChildAt(1).performClick()
                assertEquals(game, redownloaded)
            }
        } finally { lifecycle.pause().stop().destroy() }
    }

    /** Visible labels only, so hidden alternates never leak into expectations. */
    private fun texts(view: View): List<String> = if (view.visibility != View.VISIBLE) emptyList()
        else (if (view is TextView) listOf(view.text.toString()) else emptyList()) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else emptyList()

    @Test fun choosingStoreChangesTheActionAndDownloadTarget() {
        val lifecycle = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = lifecycle.get()
        val steam = LibraryEntry(LibraryStore.STEAM, "620", "Portal 2")
        val xbox = LibraryEntry(LibraryStore.XBOX, "xbox", "Portal 2")
        var source = steam
        var opened: LibraryEntry? = null
        val row = GameStoreActions(activity, NativeUi(activity), listOf(steam, xbox), { source },
            { source = it }, { true }, { false }, { opened = it })
        activity.setContentView(row)
        try {
            assertEquals(listOf("Steam", "Download"), texts(row))
            row.getChildAt(0).performClick()
            val menu = ShadowPopupMenu.getLatestPopupMenu()
            assertTrue(menu.menu.getItem(0).isChecked)
            menu.menu.performIdentifierAction(1, 0)
            assertEquals(listOf("Xbox", "View"), texts(row))
            (row.getChildAt(1) as ViewGroup).getChildAt(0).performClick()
            assertEquals(xbox, opened)
            menu.dismiss()
        } finally { lifecycle.pause().stop().destroy() }
    }

    @Test fun singleStoreKeepsOneButtonWithStoreNameAndNoSelector() {
        val lifecycle = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = lifecycle.get()
        try {
            for ((store, label) in listOf(LibraryStore.STEAM to "Download from Steam", LibraryStore.XBOX to "View on Xbox")) {
                val source = LibraryEntry(store, "one", "Game")
                val row = GameStoreActions(activity, NativeUi(activity), listOf(source), { source }, {}, { true }, { false }, {})
                assertEquals(1, row.childCount)
                assertEquals(listOf(label), texts(row))
            }
        } finally { lifecycle.pause().stop().destroy() }
    }

    @Test fun completionUpdatesInPlaceAndUnownedGamesUseView() {
        val lifecycle = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = lifecycle.get()
        val source = LibraryEntry(LibraryStore.GOG, "one", "Game")
        var ready = false
        var owned = true
        val row = GameStoreActions(activity, NativeUi(activity), listOf(source), { source }, {}, { owned }, { ready }, {})
        try {
            val column = row.getChildAt(0)
            ready = true
            row.update()
            assertEquals(listOf("Open Downloads", "Download again"), texts(row))
            assertSame(column, row.getChildAt(0))
            owned = false
            row.update()
            assertEquals(listOf("View on GOG"), texts(row))
        } finally { lifecycle.pause().stop().destroy() }
    }
}
