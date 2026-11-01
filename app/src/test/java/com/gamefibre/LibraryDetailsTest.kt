package com.gamefibre

import org.junit.Assert.*
import org.junit.Test

class LibraryDetailsTest {
    private fun game(id: String, store: LibraryStore = LibraryStore.STEAM, score: Int? = null) =
        LibraryEntry(store, id, "Game $id", steam = if (store == LibraryStore.STEAM)
            LibraryGame(id.toInt(), "Game $id", 0, emptyList(), null, GameDetails(metascore = score)) else null)

    @Test fun existingAndNewLibraryGamesSortWithoutOpeningPages() {
        val scores = mapOf(620 to 95, 400 to 90)
        fun details(game: LibraryEntry) = libraryDetails(listOf(game), scores, { null }, { null })
        val saved = listOf(game("620"), game("999"))
        assertEquals(95, details(saved.first()).metascore)
        val updated = saved + game("400")
        assertEquals(listOf("620", "400", "999"),
            orderLibrary(updated, "Metascore", true, null, details = ::details).map { it.id })
        assertEquals(listOf("400", "620", "999"),
            orderLibrary(updated, "Metascore", false, null, details = ::details).map { it.id })
    }

    @Test fun knownSteamMatchesSupplyOtherStoreAndMergedCopyScores() {
        val gog = game("gog-id", LibraryStore.GOG)
        assertEquals(95, libraryDetails(listOf(gog), mapOf(620 to 95), { 620 }, { null }).metascore)
        val steam = game("620").copy(name = gog.name)
        assertEquals(95, libraryDetails(listOf(gog, steam), mapOf(620 to 95), { null }, { null }).metascore)
        assertNull(libraryDetails(listOf(gog), mapOf(620 to 95), { null }, { null }).metascore)
    }

    @Test fun libraryAndPageScoresTakePrecedenceAndDetailsStayIntact() {
        val game = game("620", score = 96)
        val scores = mapOf(620 to 95)
        assertEquals(96, libraryDetails(listOf(game), scores, { null }, { null }).metascore)
        val page = GameDetails(description = "Description", metascore = 97)
        assertEquals(page, libraryDetails(listOf(game), scores, { null }, { page }))
        val unscored = GameDetails(description = "Description")
        assertEquals(unscored.copy(metascore = 95),
            libraryDetails(listOf(game("620")), scores, { null }, { unscored }))
        assertNull(libraryDetails(listOf(game("999")), scores, { null }, { null }).metascore)
    }
}
