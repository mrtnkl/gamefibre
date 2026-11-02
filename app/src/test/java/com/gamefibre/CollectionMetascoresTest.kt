package com.gamefibre

import com.gamefibre.stores.StoreGame
import org.junit.Assert.*
import org.junit.Test

class CollectionMetascoresTest {
    private fun steam(id: Int, name: String, score: Int? = null) = LibraryEntry(LibraryStore.STEAM, id.toString(), name,
        steam = LibraryGame(id, name, 42, listOf("windows"), 1234, GameDetails(description = "Saved description", metascore = score)))
    private fun remote(store: LibraryStore, id: String, name: String) = LibraryEntry(store, id, name,
        remote = StoreGame(id, name, imageUrl = "https://images.gog.com/cover.jpg"))
    private fun library(games: List<LibraryEntry>, wishlist: List<LibraryEntry> = emptyList()) =
        mergeLibrary(games.filter { it.store == LibraryStore.STEAM }, games.filter { it.store != LibraryStore.STEAM },
            emptyList(), emptyList(), wishlist, emptyList(), emptyList())

    @Test fun existingCollectionKeepsItsFirstSortAcrossColdStartsAndCatalogLoading() {
        val prefs = testPreferences()
        val games = listOf(steam(1, "Alpha"), steam(2, "Beta"), steam(3, "Unknown"))
        val scores = mapOf(1 to 80, 2 to 95)
        val first = CollectionData(prefs).enrichMetascores(library(games), scores) { null }
        val revision = prefs.getLong("data-revision", 0)
        val restarted = CollectionData(prefs)
        val beforeCatalog = restarted.enrichMetascores(library(games), emptyMap()) { error("No title lookup needed") }
        val afterCatalog = restarted.enrichMetascores(library(games), scores) { null }
        assertEquals(first.combined, beforeCatalog.combined)
        assertEquals(beforeCatalog.combined, afterCatalog.combined)
        assertEquals(listOf("2", "1", "3"), orderLibrary(beforeCatalog.owned, "Metascore", true, null).map { it.id })
        assertEquals(revision, prefs.getLong("data-revision", 0))
        assertNull(beforeCatalog.owned.last().details.metascore)
        assertEquals(games.first().steam!!.copy(details = games.first().details.copy(metascore = 80)), beforeCatalog.owned.first().steam)
    }

    @Test fun newGamesAndOtherStoresAndWishlistAreEnrichedAndRestored() {
        val prefs = testPreferences()
        val collection = CollectionData(prefs)
        val steam = steam(620, "Portal 2")
        val gog = remote(LibraryStore.GOG, "gog", "Portal 2")
        val epic = remote(LibraryStore.EPIC, "epic", "Matched title")
        val wish = remote(LibraryStore.XBOX, "9NBLGGH4R315", "Wishlist title")
        val games = listOf(steam, gog, epic)
        collection.addWishlist(listOf(wish))
        val enriched = collection.enrichMetascores(library(games, listOf(wish)), mapOf(620 to 95)) { 620 }
        assertTrue(enriched.combined.all { it.details.metascore == 95 })
        val restarted = CollectionData(prefs)
        assertEquals(enriched.combined, restarted.enrichMetascores(library(games, listOf(wish)), emptyMap()) { null }.combined)
        assertEquals(95, restarted.wishlistGames().single().details.metascore)
        val added = restarted.enrichMetascores(library(games + steam(400, "Portal")), mapOf(400 to 90)) { null }
        assertEquals(90, added.owned.single { it.id == "400" }.details.metascore)
        assertEquals(enriched.owned, added.owned.filter { it.id != "400" })
    }

    @Test fun pageScoresSurviveStaleAccountRefreshAndExportImport() {
        val prefs = testPreferences()
        val collection = CollectionData(prefs)
        val game = steam(620, "Portal 2", 90)
        collection.enrichMetascores(library(listOf(game)), mapOf(620 to 95)) { null }
        collection.rememberMetascores(mapOf(game.key to 97))
        val restarted = CollectionData(prefs)
        val restored = restarted.enrichMetascores(library(listOf(game)), emptyMap()) { null }
        assertEquals(97, restored.owned.single().details.metascore)
        val snapshot = CollectionSnapshot.create(restarted, restored.owned, emptyMap())
        val imported = CollectionData(testPreferences())
        imported.mergeBackup(snapshot)
        assertEquals(97, imported.libraryGames().single().details.metascore)
        assertEquals(listOf(game.key), restored.observedKeys.toList())
        assertTrue(restarted.enrichMetascores(library(emptyList()), emptyMap()) { null }.owned.isEmpty())
    }

    @Test fun signOutClearsScoresForOnlyThatStore() {
        val collection = CollectionData(testPreferences())
        collection.rememberMetascores(mapOf("STEAM:620" to 95, "GOG:123" to 90))
        assertEquals(mapOf("metascore:STEAM:620" to null), collectionChangesAfterSignOut(collection.preferences.all, "STEAM"))
    }
}
