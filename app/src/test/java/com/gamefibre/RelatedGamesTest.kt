package com.gamefibre

import com.gamefibre.stores.StoreGame
import org.junit.Assert.*
import org.junit.Test

class RelatedGamesTest {
    private fun game(id: String, name: String, developer: String = "", publisher: String = "",
        store: LibraryStore = LibraryStore.STEAM) = LibraryEntry(store, id, name,
        remote = StoreGame(id, name, details = GameDetails(developer = developer, publisher = publisher)))

    @Test fun matchesWholeCompaniesSeparatelyAndSortsByTitle() {
        val current = game("1", "Current", "Studio · Partner", "Publisher")
        val games = listOf(current, game("2", "Zebra", "studio"), game("3", "Alpha", " Partner ", "publisher"),
            game("4", "Other", "Studio North", "Other Publisher"), game("5", "Crossed", "Publisher", "Studio"))
        val result = relatedLibraryGames(current, current.details, games) { it.details }
        assertEquals(listOf("Alpha", "Zebra"), result.developer.map { it.name })
        assertEquals(listOf("Alpha"), result.publisher.map { it.name })
    }

    @Test fun ignoresMissingCompaniesAndNormalizesEncodedAmpersands() {
        val current = game("1", "Current", "A &amp; B")
        val result = relatedLibraryGames(current, current.details,
            listOf(current, game("2", "Match", "a & b"), game("3", "Unknown"))) { it.details }
        assertEquals(listOf("Match"), result.developer.map { it.name })
        assertTrue(result.publisher.isEmpty())
    }

    @Test fun excludesCurrentGameAcrossStoresAndKeepsRelatedCopiesTogether() {
        val current = game("1", "Current", "Studio", store = LibraryStore.GOG)
        val games = mergeLibraryCopies(listOf(current, game("2", "Current", "Studio"),
            game("3", "Related", "Studio"), game("4", "Related", "Studio", store = LibraryStore.EPIC)))
        val result = relatedLibraryGames(current, current.details, games) { entry ->
            mergeGameDetails(entry.copies.ifEmpty { listOf(entry) }.map { it.details })
        }
        assertEquals(listOf("Related"), result.developer.map { it.name })
        assertEquals(2, result.developer.single().copies.size)
    }
}
