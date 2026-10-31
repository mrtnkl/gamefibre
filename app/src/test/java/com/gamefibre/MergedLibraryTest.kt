package com.gamefibre

import com.gamefibre.stores.StoreGame
import org.junit.Assert.*
import org.junit.Test

class MergedLibraryTest {
    private fun entry(store: LibraryStore, id: String, name: String) = LibraryEntry(store, id, name, remote = StoreGame(id, name))

    @Test fun currentAccountDataWinsOverImportedRecordsForTheSameKey() {
        val live = entry(LibraryStore.GOG, "1", "Live name")
        val imported = entry(LibraryStore.GOG, "1", "Imported name")
        val merged = mergeLibrary(emptyList(), listOf(live), listOf(imported), emptyList(), emptyList(), emptyList(), emptyList())
        assertEquals(listOf(live), merged.owned)
        assertEquals("Live name", merged.combined.single().name)
    }

    @Test fun rememberedGamesStayBrowsableButAreNotObserved() {
        val current = entry(LibraryStore.EPIC, "a", "Current")
        val remembered = entry(LibraryStore.EPIC, "b", "Remembered")
        val merged = mergeLibrary(emptyList(), listOf(current), emptyList(), listOf(remembered), emptyList(), emptyList(), emptyList())
        assertEquals(setOf("EPIC:a", "EPIC:b"), merged.ownedKeys)
        assertEquals(setOf("EPIC:a"), merged.observedKeys)
    }

    @Test fun wishlistDownloadsAndTransfersJoinCombinedWithoutBecomingOwned() {
        val owned = entry(LibraryStore.STEAM, "10", "Owned")
        val wish = entry(LibraryStore.STEAM, "20", "Wished")
        val downloaded = entry(LibraryStore.GOG, "30", "Downloaded")
        val transfer = entry(LibraryStore.EPIC, "40", "Transferring")
        val merged = mergeLibrary(listOf(owned), emptyList(), emptyList(), emptyList(), listOf(wish), listOf(downloaded), listOf(transfer))
        assertEquals(listOf(owned), merged.owned)
        assertEquals(listOf(owned, wish, downloaded, transfer).map { it.key }, merged.combined.map { it.key })
        assertEquals(setOf("STEAM:20"), merged.wishlistKeys)
        assertTrue(merged.observedKeys.containsAll(listOf("STEAM:10", "STEAM:20", "GOG:30", "EPIC:40")))
    }

    @Test fun ownedByTitleGroupsCopiesAcrossStores() {
        val steam = entry(LibraryStore.STEAM, "1", "Same Game")
        val gog = entry(LibraryStore.GOG, "x", "Same Game™")
        val merged = mergeLibrary(listOf(steam), listOf(gog), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        assertEquals(listOf(steam, gog), merged.ownedByTitle.getValue(steam.normalizedName))
    }
}
