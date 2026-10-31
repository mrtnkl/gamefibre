package com.gamefibre

import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import com.gamefibre.stores.StoreGame
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryDiffTest {
    private fun game(id: String, name: String = "Game $id", image: String = "") =
        LibraryEntry(LibraryStore.GOG, id, name, remote = StoreGame(id, name, imageUrl = image))

    private fun updates(old: List<LibraryEntry>, next: List<LibraryEntry>, presentation: Boolean = false,
        oldScores: Map<String, Int?> = emptyMap(), nextScores: Map<String, Int?> = emptyMap()): List<String> {
        val events = mutableListOf<String>()
        DiffUtil.calculateDiff(LibraryDiff(old, next, presentation, oldScores, nextScores))
            .dispatchUpdatesTo(object : ListUpdateCallback {
                override fun onInserted(position: Int, count: Int) { events.add("insert:$position:$count") }
                override fun onRemoved(position: Int, count: Int) { events.add("remove:$position:$count") }
                override fun onMoved(fromPosition: Int, toPosition: Int) { events.add("move:$fromPosition:$toPosition") }
                override fun onChanged(position: Int, count: Int, payload: Any?) { events.add("change:$position:$count") }
            })
        return events
    }

    @Test fun unchangedRefreshDoesNotRebindAnyCards() {
        assertEquals(emptyList<String>(), updates(listOf(game("1"), game("2")), listOf(game("1"), game("2"))))
    }

    @Test fun addingAndRemovingGamesLeavesRemainingCardsAlone() {
        assertEquals(listOf("insert:2:1"), updates(listOf(game("1"), game("2")), listOf(game("1"), game("2"), game("3"))))
        assertEquals(listOf("remove:0:1"), updates(listOf(game("1"), game("2")), listOf(game("2"))))
    }

    @Test fun changedTitleOrArtworkOnlyRebindsThatGame() {
        val before = listOf(game("1"), game("2"), game("3"))
        assertEquals(listOf("change:1:1"), updates(before, listOf(game("1"), game("2", name = "New title"), game("3"))))
        assertEquals(listOf("change:1:1"), updates(before, listOf(game("1"), game("2", image = "https://example.com/new.jpg"), game("3"))))
    }

    @Test fun reorderingMovesExistingCardsWithoutRebinding() {
        assertEquals(listOf("move:0:1"), updates(listOf(game("1"), game("2")), listOf(game("2"), game("1"))))
    }

    @Test fun presentationAndLoadedScoresStillUpdateCards() {
        val games = listOf(game("1"), game("2"))
        assertEquals(listOf("change:0:2"), updates(games, games, presentation = true))
        assertEquals(listOf("change:1:1"), updates(games, games, nextScores = mapOf(games[1].key to 90)))
    }
}
