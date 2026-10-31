package com.gamefibre

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LibraryOrderingTest {
    private fun game(id: String, name: String, score: Int? = null, store: LibraryStore = LibraryStore.STEAM) =
        LibraryEntry(store, id, name, steam = LibraryGame(id.toInt(), name, 0, emptyList(), null,
            GameDetails(metascore = score)))

    @Test fun randomOrderIsAStablePermutationAndANewSeedReshuffles() {
        val games = (1..30).map { game(it.toString(), "Game $it") }
        val shuffled = orderLibrary(games, "Random", false, null, 123)
        assertEquals(games.toSet(), shuffled.toSet())
        assertNotEquals(games, shuffled)
        assertEquals(shuffled, orderLibrary(games.reversed(), "Random", false, null, 123))
        assertNotEquals(shuffled, orderLibrary(games, "Random", false, null, 456))
        val subset = games.filter { it.id.toInt() % 2 == 0 }
        assertEquals(shuffled.filter { it in subset }, orderLibrary(subset, "Random", false, null, 123))
    }

    @Test fun metascoreOrdersBothDirectionsWithMissingAndInvalidScoresLast() {
        val games = listOf(game("1", "Unknown"), game("2", "Lower", 70), game("3", "Higher", 95),
            game("4", "Zero", 0), game("5", "Invalid", 101))
        assertEquals(listOf("2", "3", "5", "1", "4"), orderLibrary(games, "Metascore", false, null).map { it.id })
        assertEquals(listOf("3", "2", "4", "1", "5"), orderLibrary(games, "Metascore", true, null).map { it.id })
    }

    @Test fun equalScoresUseTitleAndKeyForStableOrdering() {
        val games = listOf(game("3", "Zebra", 80), game("2", "The Alpha", 80), game("1", "The Alpha", 80))
        assertEquals(listOf("1", "2", "3"), orderLibrary(games, "Metascore", false, null).map { it.id })
        assertEquals(listOf("3", "1", "2"), orderLibrary(games, "Metascore", true, null).map { it.id })
    }

    @Test fun mergedCopiesAndLoadedPageDetailsSupplyScores() {
        val copies = mergeLibraryCopies(listOf(game("1", "Shared"), game("2", "Shared", 90, LibraryStore.GOG)))
        val games = copies + game("3", "Other", 80)
        assertEquals(listOf("1", "3"), orderLibrary(games, "Metascore", true, null).map { it.id })
        assertEquals(listOf("3", "1"), orderLibrary(games, "Metascore", true, null) {
            if (it.id == "3") GameDetails(metascore = 99) else it.copies.last().details
        }.map { it.id })
    }
}
